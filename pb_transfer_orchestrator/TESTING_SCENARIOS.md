# Testing & Verification Examples

This document provides concrete test scenarios, sample commands, and verification procedures for Petabyte-scale transfers.

## Test Scenario 1: Smoke Test (100 GB, 1-2 hours)

**Purpose**: Validate setup, credentials, and configuration before large-scale run.

### Setup
```powershell
# Create test data in OBS (if needed)
rclone mkdir obs-source:my-obs-bucket/test-2024-01-01
rclone mkdir obs-source:my-obs-bucket/test-2024-01-02

# Or copy ~50GB from existing data
rclone sync obs-source:my-obs-bucket/2024-01-01 obs-source:my-obs-bucket/test-2024-01-01 --transfers 16
```

### Dry Run
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 2 `
  -StartDate "2024-01-01" `
  -EndDate "2024-01-02" `
  -SourceBucket "my-obs-bucket" `
  -DestBucket "my-s3-bucket" `
  -LogDir "C:\rclone_logs\test" `
  -DryRun $true
```

**Expected output**: Lists 2 shards, shows rclone commands without executing.

### Execution
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 2 `
  -StartDate "2024-01-01" `
  -EndDate "2024-01-02" `
  -SourceBucket "my-obs-bucket" `
  -DestBucket "my-s3-bucket" `
  -LogDir "C:\rclone_logs\test" `
  -DryRun $false `
  -TransfersPerWorker 32 `
  -S3ChunkSizeMB 64 `
  -S3UploadConcurrency 8
```

### Verification
```powershell
# 1. Monitor logs
Get-Content -Path "C:\rclone_logs\test\orchestrator.log" -Tail 50

# 2. Check job status
Import-Csv -Path "C:\rclone_logs\test\job_database.csv"

# 3. Count objects
$sourceCount = (rclone lsjson obs-source:my-obs-bucket/test-2024-01-01/ --recursive | Measure-Object).Count
$destCount = (rclone lsjson s3-dest:my-s3-bucket/test-2024-01-01/ --recursive | Measure-Object).Count
Write-Host "Source: $sourceCount, Dest: $destCount"

# 4. Compare sizes
rclone size obs-source:my-obs-bucket/test-2024-01-01/
rclone size s3-dest:my-s3-bucket/test-2024-01-01/

# 5. Spot-check hashes
rclone check obs-source:my-obs-bucket/test-2024-01-01/ s3-dest:my-s3-bucket/test-2024-01-01/ -v

# 6. Verify storage class
aws s3api head-object --bucket my-s3-bucket --key test-2024-01-01/somefile.txt | jq .StorageClass
```

**Success criteria**:
- ✅ Object counts match
- ✅ Byte sizes match (within 0.1%)
- ✅ No hash mismatches
- ✅ Storage class is INTELLIGENT_TIERING (or configured value)
- ✅ No 0-byte files
- ✅ All jobs marked "Completed" in job_database.csv

---

## Test Scenario 2: Failure Recovery (1 TB)

**Purpose**: Validate resume logic and error handling.

### Setup
```powershell
# Run transfer with limited retry budget
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 4 `
  -StartDate "2024-01-01" `
  -EndDate "2024-01-04" `
  -SourceBucket "my-obs-bucket" `
  -DestBucket "my-s3-bucket" `
  -LogDir "C:\rclone_logs\test-recovery" `
  -DryRun $false `
  -MaxRetries 2 `
  -MaxConcurrentJobs 2
```

### Simulate Failure (Manual)
After 5-10 minutes, kill a shard job:
```powershell
# Get running jobs
Get-Job -Name "shard_*" | Where State -eq "Running"

# Kill one
Stop-Job -Name "shard_2"
Remove-Job -Name "shard_2"
```

### Resume
```powershell
# Rerun orchestrator; it should detect failed shard and resume
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 4 `
  -StartDate "2024-01-01" `
  -EndDate "2024-01-04" `
  -SourceBucket "my-obs-bucket" `
  -DestBucket "my-s3-bucket" `
  -LogDir "C:\rclone_logs\test-recovery" `
  -DryRun $false
```

### Verification
```powershell
# Check job database; shard_2 should be retried
Import-Csv -Path "C:\rclone_logs\test-recovery\job_database.csv" | Where ShardId -eq "shard_2"

# Verify final state shows all completed
Import-Csv -Path "C:\rclone_logs\test-recovery\job_database.csv" | Group-Object Status | Select Name, Count
# Expected: Status "Completed" with count=4
```

**Success criteria**:
- ✅ Failed shard detected in job database
- ✅ Orchestrator resumed without duplicating completed shards
- ✅ Final count matches source

---

## Test Scenario 3: Large Object Handling (100+ GB files)

**Purpose**: Validate multipart upload and large file transfer.

### Create Large Test File
```bash
# Create 200GB test file
dd if=/dev/zero of=/tmp/large_file.bin bs=1M count=204800

# Upload to OBS
rclone copy /tmp/large_file.bin obs-source:my-obs-bucket/test-large/
```

### Run Transfer
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 1 `
  -StartDate "2024-01-01" `
  -EndDate "2024-01-02" `
  -SourceBucket "my-obs-bucket" `
  -DestBucket "my-s3-bucket" `
  -LogDir "C:\rclone_logs\test-large" `
  -DryRun $false `
  -S3ChunkSizeMB 256 `
  -S3UploadConcurrency 16 `
  -TransfersPerWorker 1
```

### Verify Large File
```bash
# Check object size in S3
aws s3api head-object --bucket my-s3-bucket --key test-large/large_file.bin | jq '.ContentLength, .StorageClass'

# Compare hashes
rclone md5sum obs-source:my-obs-bucket/test-large/large_file.bin
rclone md5sum s3-dest:my-s3-bucket/test-large/large_file.bin

# Check multipart info (if available)
aws s3api list-multipart-uploads --bucket my-s3-bucket
```

**Success criteria**:
- ✅ Object size matches source (200GB)
- ✅ MD5 hash matches
- ✅ Took < 30 minutes (depends on network)

---

## Test Scenario 4: Mixed Workload (Many small files + large files)

**Purpose**: Validate performance with realistic data distribution.

### Create Mixed Data
```bash
# Create 1000 small files (1-100MB)
for i in {1..1000}; do
  dd if=/dev/zero of=/tmp/small_$i.bin bs=1K count=$((RANDOM % 100 + 1))
done

# Upload to OBS
rclone sync /tmp/ obs-source:my-obs-bucket/test-mixed/ --transfers 32

# Create 10 large files (1-10GB)
for i in {1..10}; do
  dd if=/dev/zero of=/tmp/large_$i.bin bs=1M count=$((RANDOM % 10000 + 1000))
done

# Upload
rclone sync /tmp/ obs-source:my-obs-bucket/test-mixed/ --transfers 4
```

### Run Transfer
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 2 `
  -StartDate "2024-01-01" `
  -EndDate "2024-01-02" `
  -SourceBucket "my-obs-bucket" `
  -DestBucket "my-s3-bucket" `
  -LogDir "C:\rclone_logs\test-mixed" `
  -DryRun $false `
  -TransfersPerWorker 64 `
  -S3ChunkSizeMB 64 `
  -S3UploadConcurrency 8
```

### Analyze Performance
```powershell
# Extract transfer rate from log
Get-Content "C:\rclone_logs\test-mixed\orchestrator.log" | Select-String "Transfer rate|GB/s"

# Check per-shard stats
Import-Csv -Path "C:\rclone_logs\test-mixed\job_database.csv" | Select ShardId, Duration, BytesTransferred

# Calculate throughput
$totalBytes = (rclone size obs-source:my-obs-bucket/test-mixed | Select-String "Total:" | ForEach-Object { $_.Split()[1] })
$totalSecs = 3600  # Example: 1 hour
$throughput = $totalBytes / $totalSecs / 1GB
Write-Host "Throughput: $throughput GB/s"
```

**Success criteria**:
- ✅ Small files transferred quickly (parallel efficiency)
- ✅ Large files showed multipart progress
- ✅ Overall throughput ≥ 500 MB/s (varies by network)
- ✅ No timeout on large files

---

## Test Scenario 5: Retry Resilience (Intermittent Failures)

**Purpose**: Verify retry logic handles transient errors.

### Simulate Transient Errors
```bash
# Use tc (traffic control) to introduce latency and packet loss
tc qdisc add dev eth0 root netem delay 1000ms loss 5%

# Run transfer
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 2 `
  -DryRun $false `
  -MaxRetries 5 `
  -MaxLowLevelRetries 20 `
  -RetriesSleep "10s"

# Clean up
tc qdisc del dev eth0 root
```

### Monitor Retries
```powershell
# Count retry attempts in logs
Get-Content "C:\rclone_logs\orchestrator.log" | Select-String "retry|Retry"

# Check error stats
Get-Content "C:\rclone_logs\shard_*.log" | Select-String "ERROR|error|timeout"
```

**Success criteria**:
- ✅ Retries triggered on transient errors
- ✅ Final transfer completes successfully
- ✅ No permanent data loss

---

## Verification Commands Reference

### Count Objects
```bash
# Count on source
rclone lsjson obs-source:bucket/ --recursive | jq '. | length'

# Count on destination
rclone lsjson s3-dest:bucket/ --recursive | jq '. | length'

# Compare
echo "Source: $(rclone count obs-source:bucket/), Dest: $(rclone count s3-dest:bucket/)"
```

### Compare Sizes
```bash
# Byte-for-byte comparison
rclone size obs-source:bucket/
rclone size s3-dest:bucket/

# With breakdown by directory
rclone lsjson obs-source:bucket/ --recursive | jq 'group_by(.Path | split("/")[0]) | map({dir: .[0].Path | split("/")[0], size: map(.Size) | add})'
```

### Hash Validation
```bash
# Full check (expensive for PB)
rclone check obs-source:bucket/ s3-dest:bucket/ -v

# Check specific prefix (faster)
rclone check obs-source:bucket/2024-01/ s3-dest:bucket/2024-01/ --max-checkers 4

# Sample-based check (10% random sample)
rclone lsjson obs-source:bucket/ --recursive | shuf | head -10000 | jq -r '.Path' | while read f; do
  rclone check obs-source:bucket/"$f" s3-dest:bucket/"$f" || echo "MISMATCH: $f"
done
```

### Performance Metrics
```powershell
# Parse orchestrator logs for throughput
Select-String "Transferred|MB|GB/s" "C:\rclone_logs\orchestrator.log"

# Calculate bytes transferred per job
Import-Csv "C:\rclone_logs\job_database.csv" | Select-Object ShardId, @{
  Name = "Throughput (MB/s)";
  Expression = {
    $bytes = [int]$_.BytesTransferred
    $secs = [TimeSpan]::Parse($_.Duration).TotalSeconds
    [math]::Round($bytes / 1MB / $secs, 2)
  }
}
```

### S3 Object Inspection
```bash
# Check storage class of sample objects
for key in $(aws s3api list-objects-v2 --bucket my-s3-bucket --max-items 10 --output text | awk '{print $NF}'); do
  aws s3api head-object --bucket my-s3-bucket --key "$key" | jq '{Key: .Key, StorageClass, Size: .ContentLength}'
done

# List objects by storage class
aws s3api list-objects-v2 --bucket my-s3-bucket | jq '.Contents[] | select(.StorageClass != null) | .StorageClass' | sort | uniq -c

# Check multipart uploads in progress
aws s3api list-multipart-uploads --bucket my-s3-bucket
```

---

## Post-Transfer Validation Checklist

- [ ] Object count matches (source vs. destination)
- [ ] Total byte size matches (within 0.1%)
- [ ] No 0-byte files in destination
- [ ] Storage class correctly assigned (INTELLIGENT_TIERING or configured value)
- [ ] Hash validation passed on spot-check
- [ ] All orchestrator jobs marked "Completed"
- [ ] No unhandled errors in shard logs
- [ ] S3 lifecycle rules applied and visible
- [ ] Cost estimate reviewed and within budget
- [ ] Disaster recovery plan in place (incremental syncs, backups, etc.)

---

## Common Test Failures & Remedies

| Failure | Cause | Remedy |
|---------|-------|--------|
| Object count mismatch | Incomplete transfer or extra files on dest | Rerun shards; check for manual uploads |
| Hash mismatch | Corruption or wrong source | Recheck source data; retry with `--ignore-checksum=false` |
| Storage class wrong | Not specified in config | Set `storage_class = INTELLIGENT_TIERING` in rclone.conf |
| Out of memory | Concurrency too high | Reduce `--transfers` and `--s3-upload-concurrency` |
| Slow transfer | Undersized chunks or low concurrency | Increase `--s3-chunk-size` and `--s3-upload-concurrency` |
| Stuck jobs | Network issue or deadlock | Kill job and resume; check network connectivity |
| High retry rate | Rate limiting | Add `--retries-sleep 30s`, reduce concurrency |

---

## Load Testing (Optional)

For very high-scale deployments, run load tests to identify bottlenecks:

```powershell
# Generate synthetic data
for ($i = 0; $i -lt 100000; $i++) {
  $content = [string](1..10000 | Get-Random)
  Set-Content -Path "C:\test_data\file_$i.txt" -Value $content
}

# Upload to OBS
rclone sync C:\test_data obs-source:bucket/load-test/ --transfers 128

# Run orchestrator with maxed concurrency
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 8 `
  -TransfersPerWorker 256 `
  -S3UploadConcurrency 32 `
  -DryRun $false

# Monitor resource usage
Get-Process rclone | Select Name, @{
  Name = "MemoryMB";
  Expression = {[math]::Round($_.WorkingSet / 1MB, 0)}
}, CPU
```

**Observe**:
- Peak memory usage per worker
- CPU saturation
- Network throughput
- Error rates at high concurrency

Adjust parameters based on findings.

---

## Cleanup

After testing, clean up test data:

```powershell
# Remove test objects from OBS
rclone purge obs-source:bucket/test-*

# Remove from S3
rclone purge s3-dest:bucket/test-*

# Archive logs
Get-ChildItem "C:\rclone_logs\test-*" | Compress-Archive -DestinationPath "C:\rclone_logs\test_archive_$(Get-Date -Format 'yyyyMMdd').zip" -Update

# Clean old logs
Remove-Item -Path "C:\rclone_logs\test-*" -Recurse -Force
```

---

## Performance Baseline

Expected throughput by configuration (network-dependent):

| Config | Files/s | Throughput | Notes |
|--------|---------|-----------|-------|
| Light (t=8, c=4) | 100–500 | 50–200 MB/s | Minimal resource usage |
| Medium (t=32, c=8) | 500–2000 | 200–1000 MB/s | Balanced |
| Heavy (t=128, c=16) | 2000–8000 | 1–5 GB/s | High resource usage |
| Extreme (t=256, c=32) | 5000–20000 | 5–20 GB/s | Requires fast network + large memory |

**Your results will vary based on**:
- Network speed (bandwidth, latency)
- OBS/S3 API response times
- Machine specs (CPU, RAM)
- Data composition (file size distribution)

Test and tune for your environment!
