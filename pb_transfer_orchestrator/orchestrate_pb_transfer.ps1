# Petabyte-scale OBSv2 → AWS S3 Transfer Orchestrator
# Windows PowerShell Script
# Usage: .\orchestrate_pb_transfer.ps1 -ShardCount 24 -StartDate "2024-01-01" -EndDate "2024-12-31" -DryRun $false

param(
    [Parameter(Mandatory=$false)]
    [int]$ShardCount = 24,  # Number of parallel workers (shards/jobs)
    
    [Parameter(Mandatory=$false)]
    [string]$StartDate = "2024-01-01",  # First date/prefix to process
    
    [Parameter(Mandatory=$false)]
    [string]$EndDate = "2024-12-31",  # Last date/prefix to process
    
    [Parameter(Mandatory=$false)]
    [bool]$DryRun = $true,  # Set to $false to actually run transfers
    
    [Parameter(Mandatory=$false)]
    [string]$LogDir = "C:\rclone_logs",  # Directory for logs and state
    
    [Parameter(Mandatory=$false)]
    [string]$RcloneConfig = "$env:APPDATA\rclone\rclone.conf",  # Path to rclone config
    
    [Parameter(Mandatory=$false)]
    [int]$TransfersPerWorker = 32,  # Concurrent file transfers per worker
    
    [Parameter(Mandatory=$false)]
    [int]$CheckersPerWorker = 16,  # Concurrent checkers per worker
    
    [Parameter(Mandatory=$false)]
    [string]$SourceRemote = "obs-source",  # Rclone remote name for source
    
    [Parameter(Mandatory=$false)]
    [string]$DestRemote = "s3-dest",  # Rclone remote name for destination
    
    [Parameter(Mandatory=$false)]
    [string]$SourceBucket = "source-bucket",  # Source bucket name
    
    [Parameter(Mandatory=$false)]
    [string]$DestBucket = "dest-bucket",  # Destination bucket name
    
    [Parameter(Mandatory=$false)]
    [int]$S3ChunkSizeMB = 64,  # S3 multipart chunk size in MB
    
    [Parameter(Mandatory=$false)]
    [int]$S3UploadConcurrency = 8,  # Concurrent parts per file upload
    
    [Parameter(Mandatory=$false)]
    [int]$S3UploadCutoffMB = 200,  # Cutoff for multipart upload in MB
    
    [Parameter(Mandatory=$false)]
    [int]$MaxRetries = 5,  # Maximum retries per operation
    
    [Parameter(Mandatory=$false)]
    [int]$MaxLowLevelRetries = 10,  # Low-level SDK retries
    
    [Parameter(Mandatory=$false)]
    [string]$RetriesSleep = "10s"  # Sleep between retries
)

# Strict mode for better error handling
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Create log directory if it doesn't exist
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    Write-Host "Created log directory: $LogDir" -ForegroundColor Green
}

# Define state file and job database
$StateFile = Join-Path $LogDir "transfer_state.json"
$JobDbFile = Join-Path $LogDir "job_database.csv"
$StatsSummaryFile = Join-Path $LogDir "transfer_summary.json"

# ============================================================================
# CONFIGURATION & CONSTANTS
# ============================================================================

$RclonePath = "rclone"  # Assumes rclone is in PATH
$RcloneVersion = & $RclonePath version

Write-Host "Rclone Version: $RcloneVersion" -ForegroundColor Cyan
Write-Host "Transfer Orchestrator Starting" -ForegroundColor Green
Write-Host "Parameters:" -ForegroundColor Cyan
Write-Host "  ShardCount: $ShardCount"
Write-Host "  DateRange: $StartDate to $EndDate"
Write-Host "  DryRun: $DryRun"
Write-Host "  LogDir: $LogDir"
Write-Host ""

# ============================================================================
# UTILITY FUNCTIONS
# ============================================================================

function Write-Log {
    param(
        [string]$Message,
        [string]$Level = "INFO",
        [string]$JobId = ""
    )
    $Timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $LogMessage = "[$Timestamp] [$Level] $JobId $Message"
    Write-Host $LogMessage
    $LogMessage | Add-Content -Path (Join-Path $LogDir "orchestrator.log")
}

function Get-DateRange {
    param(
        [string]$Start,
        [string]$End,
        [int]$Days
    )
    $StartDate = [datetime]::ParseExact($Start, "yyyy-MM-dd", $null)
    $EndDate = [datetime]::ParseExact($End, "yyyy-MM-dd", $null)
    $DateList = @()
    
    for ($i = 0; $i -le ($EndDate - $StartDate).Days; $i += $Days) {
        $CurrentDate = $StartDate.AddDays($i)
        if ($CurrentDate -le $EndDate) {
            $DateList += $CurrentDate.ToString("yyyy-MM-dd")
        }
    }
    return $DateList
}

function Initialize-JobDatabase {
    $JobData = @()
    if (Test-Path $JobDbFile) {
        $JobData = @(Import-Csv -Path $JobDbFile)
    }
    return $JobData
}

function Save-JobDatabase {
    param($JobData)
    $JobData | Export-Csv -Path $JobDbFile -NoTypeInformation -Force
}

function Add-Job {
    param(
        [string]$JobId,
        [string]$Prefix,
        [string]$Status,
        [string]$StartTime,
        [int]$BytesTransferred = 0,
        [int]$ObjectsTransferred = 0,
        [string]$ErrorMessage = ""
    )
    $JobRecord = [PSCustomObject]@{
        JobId = $JobId
        Prefix = $Prefix
        Status = $Status
        StartTime = $StartTime
        EndTime = ""
        BytesTransferred = $BytesTransferred
        ObjectsTransferred = $ObjectsTransferred
        ErrorMessage = $ErrorMessage
        Duration = ""
    }
    return $JobRecord
}

function Build-RcloneArgs {
    param(
        [string]$SourceRemote,
        [string]$SourceBucket,
        [string]$DestRemote,
        [string]$DestBucket,
        [string]$Prefix,
        [int]$Transfers,
        [int]$Checkers,
        [int]$S3ChunkSize,
        [int]$S3UploadConcurrency,
        [int]$S3UploadCutoff,
        [string]$LogFile
    )
    
    $Args = @(
        "sync",
        "$($SourceRemote):$SourceBucket/$Prefix",
        "$($DestRemote):$DestBucket/$Prefix",
        "--transfers=$Transfers",
        "--checkers=$Checkers",
        "--s3-chunk-size=${S3ChunkSize}M",
        "--s3-upload-concurrency=$S3UploadConcurrency",
        "--s3-upload-cutoff=${S3UploadCutoff}M",
        "--retries=$MaxRetries",
        "--low-level-retries=$MaxLowLevelRetries",
        "--retries-sleep=$RetriesSleep",
        "--fast-list",
        "--stats=10s",
        "--stats-one-line",
        "--log-file=$LogFile",
        "-vv",
        "-P"
    )
    
    if ($DryRun) {
        $Args += "--dry-run"
    }
    
    return $Args
}

function Invoke-RcloneJob {
    param(
        [string]$JobId,
        [string]$SourceRemote,
        [string]$SourceBucket,
        [string]$DestRemote,
        [string]$DestBucket,
        [string]$Prefix,
        [string]$LogFile
    )
    
    Write-Log "Starting job: $JobId, Prefix: $Prefix" "INFO" $JobId
    
    $RcloneArgs = Build-RcloneArgs `
        -SourceRemote $SourceRemote `
        -SourceBucket $SourceBucket `
        -DestRemote $DestRemote `
        -DestBucket $DestBucket `
        -Prefix $Prefix `
        -Transfers $TransfersPerWorker `
        -Checkers $CheckersPerWorker `
        -S3ChunkSize $S3ChunkSizeMB `
        -S3UploadConcurrency $S3UploadConcurrency `
        -S3UploadCutoff $S3UploadCutoffMB `
        -LogFile $LogFile
    
    try {
        $ProcessInfo = New-Object System.Diagnostics.ProcessStartInfo
        $ProcessInfo.FileName = $RclonePath
        $ProcessInfo.Arguments = $RcloneArgs -join ' '
        $ProcessInfo.RedirectStandardOutput = $true
        $ProcessInfo.RedirectStandardError = $true
        $ProcessInfo.UseShellExecute = $false
        $ProcessInfo.CreateNoWindow = $true
        
        $Process = [System.Diagnostics.Process]::Start($ProcessInfo)
        $OutputData = $Process.StandardOutput.ReadToEnd()
        $ErrorData = $Process.StandardError.ReadToEnd()
        $Process.WaitForExit()
        
        if ($Process.ExitCode -eq 0) {
            Write-Log "Completed successfully: $JobId" "SUCCESS" $JobId
            return @{ Success = $true; ExitCode = 0; Output = $OutputData }
        } else {
            Write-Log "Failed with exit code $($Process.ExitCode): $ErrorData" "ERROR" $JobId
            return @{ Success = $false; ExitCode = $Process.ExitCode; Error = $ErrorData }
        }
    } catch {
        Write-Log "Exception: $($_)" "ERROR" $JobId
        return @{ Success = $false; ExitCode = -1; Error = $_.ToString() }
    }
}

function Generate-Shards {
    param(
        [int]$Count,
        [string]$Start,
        [string]$End
    )
    
    # Strategy: Date-based sharding (one shard per day or N days depending on ShardCount)
    # Adjust interval based on desired shard count
    $DaysBetween = ([datetime]::ParseExact($End, "yyyy-MM-dd", $null) - [datetime]::ParseExact($Start, "yyyy-MM-dd", $null)).Days + 1
    $DaysPerShard = [math]::Max(1, [math]::Ceiling($DaysBetween / $Count))
    
    $Shards = @()
    $CurrentDate = [datetime]::ParseExact($Start, "yyyy-MM-dd", $null)
    $EndDate = [datetime]::ParseExact($End, "yyyy-MM-dd", $null)
    
    $ShardIndex = 1
    while ($CurrentDate -le $EndDate) {
        $ShardEnd = $CurrentDate.AddDays($DaysPerShard - 1)
        if ($ShardEnd -gt $EndDate) { $ShardEnd = $EndDate }
        
        $Shard = [PSCustomObject]@{
            ShardId = "shard_$ShardIndex"
            Prefix = "$($CurrentDate.ToString('yyyy/MM/dd'))"
            DateStart = $CurrentDate.ToString("yyyy-MM-dd")
            DateEnd = $ShardEnd.ToString("yyyy-MM-dd")
            Status = "PENDING"
            Retries = 0
            LastError = ""
        }
        $Shards += $Shard
        
        $CurrentDate = $ShardEnd.AddDays(1)
        $ShardIndex++
    }
    
    return $Shards
}

# ============================================================================
# MAIN ORCHESTRATION LOGIC
# ============================================================================

function Start-TransferOrchestration {
    Write-Log "Initializing transfer orchestration..." "INFO"
    
    # Generate shards
    $Shards = Generate-Shards -Count $ShardCount -Start $StartDate -End $EndDate
    Write-Host "Generated $($Shards.Count) shards for processing" -ForegroundColor Cyan
    
    $Shards | ForEach-Object { Write-Host "  $($_.ShardId): $($_.DateStart) to $($_.DateEnd) (prefix: $($_.Prefix))" }
    Write-Host ""
    
    # Initialize job database
    $Jobs = Initialize-JobDatabase
    $TotalStats = @{
        JobsCompleted = 0
        JobsFailed = 0
        JobsInProgress = 0
        TotalBytesTransferred = 0
        TotalObjectsTransferred = 0
    }
    
    # Process shards in parallel (up to max workers)
    $MaxConcurrentJobs = [math]::Min($ShardCount, 4)  # Limit to 4 concurrent PowerShell jobs to avoid overwhelming system
    $RunningJobs = @()
    
    foreach ($Shard in $Shards) {
        $JobId = $Shard.ShardId
        $LogFile = Join-Path $LogDir "$JobId.log"
        
        # Wait if we have too many running jobs
        while ($RunningJobs.Count -ge $MaxConcurrentJobs) {
            $CompletedJobs = $RunningJobs | Where-Object { $_.State -eq "Completed" }
            foreach ($Job in $CompletedJobs) {
                $Result = $Job | Receive-Job
                Write-Log "Job completed: $($Job.Name), Result: $($Result.Success)" "INFO"
                $RunningJobs = $RunningJobs | Where-Object { $_.Id -ne $Job.Id }
            }
            Start-Sleep -Seconds 5
        }
        
        # Start new job
        $Job = Start-Job -Name $JobId -ScriptBlock {
            param($JobId, $SourceRemote, $SourceBucket, $DestRemote, $DestBucket, $Prefix, $LogFile, $RclonePath, $TransfersPerWorker, $CheckersPerWorker, $S3ChunkSizeMB, $S3UploadConcurrency, $S3UploadCutoffMB, $MaxRetries, $MaxLowLevelRetries, $RetriesSleep, $DryRun, $LogDir)
            
            # Import function within job context
            function Invoke-RcloneJob {
                param(
                    [string]$JobId,
                    [string]$SourceRemote,
                    [string]$SourceBucket,
                    [string]$DestRemote,
                    [string]$DestBucket,
                    [string]$Prefix,
                    [string]$LogFile,
                    [string]$RclonePath,
                    [int]$Transfers,
                    [int]$Checkers,
                    [int]$S3ChunkSize,
                    [int]$S3UploadConcurrency,
                    [int]$S3UploadCutoff,
                    [int]$MaxRetries,
                    [int]$MaxLowLevelRetries,
                    [string]$RetriesSleep,
                    [bool]$DryRun
                )
                
                $Args = @(
                    "sync",
                    "$($SourceRemote):$SourceBucket/$Prefix",
                    "$($DestRemote):$DestBucket/$Prefix",
                    "--transfers=$Transfers",
                    "--checkers=$Checkers",
                    "--s3-chunk-size=${S3ChunkSize}M",
                    "--s3-upload-concurrency=$S3UploadConcurrency",
                    "--s3-upload-cutoff=${S3UploadCutoff}M",
                    "--retries=$MaxRetries",
                    "--low-level-retries=$MaxLowLevelRetries",
                    "--retries-sleep=$RetriesSleep",
                    "--fast-list",
                    "--stats=10s",
                    "--stats-one-line",
                    "--log-file=$LogFile",
                    "-vv",
                    "-P"
                )
                
                if ($DryRun) {
                    $Args += "--dry-run"
                }
                
                & $RclonePath $Args
                return @{ ExitCode = $LASTEXITCODE }
            }
            
            $Result = Invoke-RcloneJob -JobId $JobId -SourceRemote $SourceRemote -SourceBucket $SourceBucket -DestRemote $DestRemote -DestBucket $DestBucket -Prefix $Prefix -LogFile $LogFile -RclonePath $RclonePath -Transfers $TransfersPerWorker -Checkers $CheckersPerWorker -S3ChunkSize $S3ChunkSizeMB -S3UploadConcurrency $S3UploadConcurrency -S3UploadCutoff $S3UploadCutoffMB -MaxRetries $MaxRetries -MaxLowLevelRetries $MaxLowLevelRetries -RetriesSleep $RetriesSleep -DryRun $DryRun
            return $Result
        } -ArgumentList $JobId, $SourceRemote, $SourceBucket, $DestRemote, $DestBucket, $Shard.Prefix, $LogFile, $RclonePath, $TransfersPerWorker, $CheckersPerWorker, $S3ChunkSizeMB, $S3UploadConcurrency, $S3UploadCutoffMB, $MaxRetries, $MaxLowLevelRetries, $RetriesSleep, $DryRun, $LogDir
        
        $RunningJobs += $Job
        Write-Log "Queued job: $JobId for prefix $($Shard.Prefix)" "INFO"
        Start-Sleep -Milliseconds 500  # Small delay between job starts
    }
    
    # Wait for all remaining jobs to complete
    Write-Host "Waiting for all jobs to complete..." -ForegroundColor Cyan
    Get-Job -Name "shard_*" | Wait-Job | Receive-Job | Out-Null
    
    # Collect final results
    Write-Log "All jobs completed" "INFO"
    Write-Host "Transfer orchestration complete. Check logs in $LogDir" -ForegroundColor Green
}

# Run orchestration
Start-TransferOrchestration

Write-Host ""
Write-Host "Summary:" -ForegroundColor Cyan
Write-Host "  Logs directory: $LogDir"
Write-Host "  Job database: $JobDbFile"
Write-Host "  See individual shard_*.log files for detailed transfer logs"
Write-Host ""
