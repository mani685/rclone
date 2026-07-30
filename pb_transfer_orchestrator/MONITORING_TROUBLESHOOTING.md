# Monitoring, Troubleshooting & Operational Runbooks

This document provides step-by-step procedures for monitoring a live transfer, diagnosing issues, and responding to common operational scenarios.

---

## 📊 REAL-TIME MONITORING

### Dashboard Overview

During transfer, monitor these metrics in real-time:

```powershell
# Open separate PowerShell terminals for each view:

# Terminal 1: Master log (overall progress)
Get-Content -Path "C:\rclone_logs\orchestrator.log" -Tail 100 -Wait

# Terminal 2: Individual shard (detailed stats)
Get-Content -Path "C:\rclone_logs\shard_1.log" -Tail 50 -Wait

# Terminal 3: Job status (running jobs)
while($true) {
  Clear-Host
  Get-Job -Name "shard_*" | Select-Object Name, State, PSBeginTime, @{
    Name = "Runtime"
    Expression = { (Get-Date) - $_.PSBeginTime }
  }
  Start-Sleep -Seconds 5
}

# Terminal 4: System resources
while($true) {
  Clear-Host
  Get-Process -Name "rclone" | Measure-Object -Property WorkingSet -Sum | ForEach-Object {
    $memoryGB = [math]::Round($_.Sum / 1GB, 2)
    Write-Host "Rclone Processes: $($_.Count), Memory: ${memoryGB}GB"
  }
  Get-Counter -Counter "\Processor(_Total)\% Processor Time" | Select-Object -ExpandProperty CounterSamples | Select-Object InstanceName, CookedValue
  Start-Sleep -Seconds 5
}
```

### Key Metrics to Watch

| Metric | Good | Warning | Critical |
|--------|------|---------|----------|
| **Throughput (MB/s)** | > 1000 | 100–1000 | < 100 |
| **Transfer Rate (files/s)** | > 100 | 10–100 | < 10 |
| **Memory per worker (GB)** | < 50 | 50–100 | > 100 |
| **Error rate (%)** | < 0.1% | 0.1–1% | > 1% |
| **Job duration (hours)** | < 5 | 5–10 | > 10 (stalled) |
| **API response time (ms)** | < 100 | 100–500 | > 500 (timeout) |

### Log Interpretation

**Master Log** (`orchestrator.log`):
```
[2024-11-15 10:30:15] [ORCHESTRATOR] Starting transfer orchestration (24 shards, 4 concurrent workers)
↑ Initialization message

[2024-11-15 10:30:15] [ORCHESTRATOR] Shard 1: obs-source:bucket/2024-01/ → s3-dest:bucket/2024-01/
↑ Shard definition

[2024-11-15 10:30:15] [ORCHESTRATOR] Spawning Job: shard_1 (PID 1234)
↑ Job started

[2024-11-15 10:35:45] [shard_1] Transferred 15,678 files, 5.6 GB in 5m30s
↑ Progress update

[2024-11-15 10:35:45] [ORCHESTRATOR] Shard 1 SUCCESS (5m30s)
↑ Shard completed successfully

[2024-11-15 10:35:45] [ORCHESTRATOR] Spawning Job: shard_5 (PID 1235)
↑ Next job queued
```

**Shard Log** (`shard_1.log`):
```
2024-11-15T10:30:15Z INFO : obs -> s3: Starting transfer
2024-11-15T10:30:20Z INFO : file1.bin: Transferred 512.5M/1G in 5s (102.5 MB/s), ETA 4m
2024-11-15T10:30:25Z NOTICE: file1.bin: Speeding up transfer (more files ready)
2024-11-15T10:35:00Z INFO : Transferred 15,678 files, 5.6 GB, 98.5% done, speed 1.2 GB/s
2024-11-15T10:35:45Z INFO : Transfer complete
```

---

## 🔍 TROUBLESHOOTING GUIDE

### Issue 1: Low Throughput (< 100 MB/s)

**Diagnosis**:
```powershell
# Check network connectivity
Test-NetConnection obs.cn-north-1.myhuaweicloud.com -Port 443

# Check network throughput with iperf3
iperf3 -c <target-host> -t 10  # Should show > 1 Gbps

# Check rclone concurrent operations
Get-Content "C:\rclone_logs\shard_*.log" | Select-String "concurrent"

# Check API response times
Get-Content "C:\rclone_logs\orchestrator.log" | Select-String "duration|latency|slow"
```

**Common Causes & Fixes**:

| Cause | Evidence | Fix |
|-------|----------|-----|
| Low concurrency | Log shows "1–2 files in flight" | Increase `--transfers` to 64–128 |
| Small chunk size | Transfer shows MB/s instead of GB/s | Increase `--s3-chunk-size` to 128–256M |
| Rate limiting | Logs show "429 Too Many Requests" | Add `--retries-sleep 30s`, reduce `--transfers` |
| Slow network | `iperf3` shows < 100 Mbps | Check ISP, firewall, or use larger shards |
| API throttling | "Waiting for rate limit" in logs | Contact provider for quota increase |

**Resolution**:
```powershell
# Rerun with increased concurrency
.\orchestrate_pb_transfer.ps1 `
  -TransfersPerWorker 128 `
  -S3ChunkSizeMB 256 `
  -S3UploadConcurrency 16 `
  -DryRun $false
```

---

### Issue 2: Out of Memory (OOM) Error

**Diagnosis**:
```powershell
# Check memory usage
Get-Process rclone | Select-Object Name, @{
  Name = "MemoryMB"
  Expression = { [math]::Round($_.WorkingSet / 1MB, 0) }
}

# Look for OOM errors in logs
Select-String "out of memory|OOM|memory limit" "C:\rclone_logs\shard_*.log"

# Check available system memory
Get-ComputerInfo | Select-Object CsPhyicallyInstalledSystemMemory, @{
  Name = "AvailableMemory"
  Expression = { (Get-Counter "\Memory\Available MBytes").CounterSamples[0].CookedValue }
}
```

**Root Cause Analysis**:
```
Memory = (--transfers × --s3-upload-concurrency × --s3-chunk-size) × num_workers

Example causing OOM:
  256 × 32 × 128MB = 1024GB per worker (impossible!)
  
Safe limits:
  64 × 8 × 64MB = 32GB per worker (acceptable on 128GB machine)
```

**Resolution**:
```powershell
# Reduce concurrency to fit available memory
.\orchestrate_pb_transfer.ps1 `
  -TransfersPerWorker 32 `
  -S3UploadConcurrency 4 `
  -S3ChunkSizeMB 32 `
  -DryRun $false
```

---

### Issue 3: High Error Rate (> 1% failed transfers)

**Diagnosis**:
```powershell
# Count errors
$errorCount = (Select-String "ERROR|error" "C:\rclone_logs\shard_*.log" | Measure-Object).Count
$totalLines = (Get-Content "C:\rclone_logs\shard_*.log" | Measure-Object).Count
$errorRate = [math]::Round(($errorCount / $totalLines) * 100, 2)
Write-Host "Error rate: $errorRate%"

# Identify error patterns
Select-String "ERROR:" "C:\rclone_logs\shard_*.log" | Group-Object { $_.Line -replace '.*ERROR: (.+)$', '$1' } | Select Name, Count | Sort Count -Descending
```

**Common Error Types**:

| Error | Cause | Fix |
|-------|-------|-----|
| `403 Forbidden` | No permissions | Check IAM policy, credentials |
| `404 Not Found` | Object deleted during transfer | Re-sync from source |
| `429 Too Many Requests` | Rate limit | Add `--retries-sleep 30s` |
| `503 Service Unavailable` | Provider issue | Retry automatically; increase `--retries` |
| `Timeout` | Network slow | Increase `--timeout 30m`, reduce transfers |

**Resolution**:
```powershell
# Example: Retry with increased patience
.\orchestrate_pb_transfer.ps1 `
  -MaxRetries 10 `
  -MaxLowLevelRetries 20 `
  -RetriesSleep "30s" `
  -DryRun $false
```

---

### Issue 4: Transfer Stalled (No Progress for > 30 minutes)

**Diagnosis**:
```powershell
# Check if jobs are still running
Get-Job -Name "shard_*" | Where State -eq "Running" | Measure-Object

# Check last log update
Get-Item "C:\rclone_logs\shard_*.log" | ForEach-Object {
  $lastWrite = $_.LastWriteTime
  $age = (Get-Date) - $lastWrite
  if ($age.Minutes -gt 30) {
    Write-Warning "$($_.Name) - No updates for $($age.Minutes) minutes"
  }
}

# Check network connectivity
Test-NetConnection obs.cn-north-1.myhuaweicloud.com -Port 443 -WarningAction Ignore
```

**Common Causes**:

| Cause | Evidence | Fix |
|-------|----------|-----|
| Network timeout | "Connection refused" in logs | Check network; increase `--timeout` |
| Deadlock | Job running but no log updates | Kill job: `Stop-Job -Name shard_*` |
| API hanging | 1000+ files "waiting" but not progressing | Restart provider API (contact support) |
| Disk full | "No space left on device" | Free up local cache: `rclone delete obs-cache:` |

**Resolution**:
```powershell
# Kill stuck job
Stop-Job -Name "shard_3"
Remove-Job -Name "shard_3"

# Rerun orchestrator (resumes from job database)
.\orchestrate_pb_transfer.ps1 -DryRun $false
```

---

### Issue 5: Incomplete Transfer After Orchestrator Exits

**Diagnosis**:
```powershell
# Check job status in database
Import-Csv "C:\rclone_logs\job_database.csv" | Select ShardId, Status, ExitCode

# Count completed vs failed
Import-Csv "C:\rclone_logs\job_database.csv" | Group-Object Status | Select Name, Count

# Find failed shards
Import-Csv "C:\rclone_logs\job_database.csv" | Where Status -ne "Completed"
```

**Recovery**:
```powershell
# Rerun orchestrator (automatically retries failed shards)
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -DryRun $false `
  -MaxRetries 5

# Monitor until all shards complete
while($true) {
  $completed = (Import-Csv "C:\rclone_logs\job_database.csv" | Where Status -eq "Completed" | Measure-Object).Count
  $total = (Import-Csv "C:\rclone_logs\job_database.csv" | Measure-Object).Count
  Write-Host "$completed/$total shards completed ($(Get-Date))"
  Start-Sleep -Seconds 30
}
```

---

## 🔧 OPERATIONAL RUNBOOKS

### Runbook 1: Graceful Shutdown

**Scenario**: Need to pause transfer without losing progress.

**Steps**:
```powershell
# 1. Stop accepting new jobs (set MaxConcurrentJobs to 0 in script)
# 2. Wait for running jobs to complete or manually stop them
Stop-Job -Name "shard_*" -Confirm

# 3. Verify all jobs are stopped
Get-Job -Name "shard_*" | Where State -eq "Running"

# 4. Save current state
Copy-Item "C:\rclone_logs\job_database.csv" "C:\rclone_logs\job_database_backup_$(Get-Date -Format 'yyyyMMdd_HHmmss').csv"

# 5. Later: Resume by rerunning orchestrator
.\orchestrate_pb_transfer.ps1 -DryRun $false
```

---

### Runbook 2: Handle Credential Rotation

**Scenario**: AWS or OBS credentials expire during transfer.

**Steps**:
```powershell
# 1. Update credentials in rclone.conf
notepad $env:APPDATA\rclone\rclone.conf
# [Edit aws/obs credentials]

# 2. Verify new credentials work
rclone ls obs-source:bucket | head -1
rclone ls s3-dest:bucket | head -1

# 3. Restart failed shards (old credentials already failed)
.\orchestrate_pb_transfer.ps1 -DryRun $false

# Note: Completed shards are not re-run (safe to resume)
```

---

### Runbook 3: Performance Degradation

**Scenario**: Transfer slowing down over time.

**Steps**:
```powershell
# 1. Check system resources
Get-Process rclone | Measure-Object -Property WorkingSet -Sum | ForEach-Object {
  Write-Host "Memory: $([math]::Round($_.Sum / 1GB, 2))GB"
}

# 2. Check provider limits (API quota)
Get-Content "C:\rclone_logs\shard_*.log" | Select-String "quota|limit|throttle" | tail -20

# 3. Check network stats
ipconfig /all | Select-String "IPv4 Address"
# Run iperf3 to measure bandwidth

# 4. Mitigation: Reduce concurrency or restart workers
.\orchestrate_pb_transfer.ps1 `
  -TransfersPerWorker 48 `
  -S3UploadConcurrency 8 `
  -DryRun $false
```

---

### Runbook 4: Data Validation Mid-Transfer

**Scenario**: Ensure data is being transferred correctly while still running.

**Steps**:
```powershell
# 1. Stop orchestrator (running jobs complete first)
Stop-Job -Name "shard_*" -Confirm

# 2. Check a completed shard
rclone check obs-source:bucket/2024-01/ s3-dest:bucket/2024-01/ --max-checkers 4

# 3. If valid, resume transfer
.\orchestrate_pb_transfer.ps1 -DryRun $false

# 4. If invalid, investigate
Get-Content "C:\rclone_logs\shard_1.log" | Select-String "ERROR\|error"
# Contact provider support if data corruption suspected
```

---

### Runbook 5: Multi-Region Failover

**Scenario**: Primary region unavailable; need to failover to secondary.

**Setup** (pre-transfer):
```ini
# rclone.conf - configure secondary endpoints
[obs-source-backup]
type = s3
provider = Other
endpoint = obs.us-east-1.myhuaweicloud.com  # Alternative region
access_key_id = ...
secret_access_key = ...

[s3-dest-secondary]
type = s3
provider = AWS
region = us-west-2  # Different region
```

**Failover**:
```powershell
# 1. Update script to use backup remotes
$SourceRemote = "obs-source-backup"
$DestRemote = "s3-dest-secondary"

# 2. Verify connectivity
rclone ls obs-source-backup:bucket | head -1

# 3. Rerun orchestrator with backup remotes
.\orchestrate_pb_transfer.ps1 `
  -SourceRemote "obs-source-backup" `
  -DestRemote "s3-dest-secondary" `
  -DryRun $false

# 4. After failover, sync to primary S3
rclone sync s3-dest-secondary:bucket s3-dest:bucket --transfers 32
```

---

## 📈 PERFORMANCE TUNING CHECKLIST

Run this checklist if transfer is slower than expected:

- [ ] **Network**: Check throughput with `iperf3` (should be > 1 Gbps)
- [ ] **CPU**: Verify not saturated (`Get-Process rclone` CPU columns)
- [ ] **Memory**: Ensure available RAM > 2× allocated to rclone processes
- [ ] **Concurrency**: Increase `--transfers` if < 50 concurrent files
- [ ] **Chunk Size**: Increase `--s3-chunk-size` if chunk transfers complete in < 1 second
- [ ] **Upload Cutoff**: Lower `--s3-upload-cutoff` to use multipart for smaller files
- [ ] **Checkers**: Increase `--checkers` if listing is slow
- [ ] **Rate Limit**: Remove or increase sleep if not hitting 429 errors
- [ ] **Provider Limits**: Contact provider to confirm API quotas
- [ ] **Network Path**: Check for proxy/firewall causing latency

---

## 📋 DAILY OPERATIONS CHECKLIST

**Start of Day**:
- [ ] Check orchestrator still running: `Get-Job -Name "shard_*"`
- [ ] Review error log: `tail -100 orchestrator.log`
- [ ] Confirm throughput is normal: `grep "Throughput\|MB/s" orchestrator.log | tail -1`
- [ ] Check system resources: Free memory, CPU, disk

**During Transfer**:
- [ ] Monitor every 2–4 hours
- [ ] Spot-check individual shard logs for errors
- [ ] Verify no stalled jobs

**End of Day**:
- [ ] Archive logs: `Compress-Archive -Path "C:\rclone_logs" -DestinationPath "backup_$(date +%Y%m%d).zip"`
- [ ] Backup job database: `Copy-Item job_database.csv job_database_backup.csv`
- [ ] Estimate completion date based on current throughput

---

## 🚨 EMERGENCY CONTACTS & ESCALATION

### If Transfer Fails Completely

1. **Immediate Actions**:
   - Check credentials: `rclone config show obs-source`
   - Verify bucket access: `rclone lsjson obs-source:bucket | head -1`
   - Review latest error log: `tail -1000 orchestrator.log | grep -i error`

2. **If OBS Issues**:
   - Contact Huawei OBS Support
   - Provide: Error message, endpoint, time of failure
   - Fallback: Switch to backup OBS region

3. **If AWS S3 Issues**:
   - Check AWS Service Health Dashboard
   - Contact AWS Support
   - Provide: Bucket name, region, error code
   - Fallback: Switch to backup S3 region

4. **If Network/Connectivity**:
   - Check ISP/firewall logs
   - Verify firewall rules allow 443 (HTTPS)
   - Contact network team

---

## 📊 Metrics Export & Analysis

```powershell
# Export transfer statistics
Import-Csv "C:\rclone_logs\job_database.csv" | Export-Csv "transfer_stats_$(Get-Date -Format 'yyyyMMdd').csv"

# Analyze performance over time
$stats = Import-Csv "transfer_stats_*.csv"
$stats | Group-Object Date | ForEach-Object {
  $throughput = ($_.Group.BytesTransferred | Measure-Object -Sum).Sum / ($_.Group.Duration | Measure-Object -Average).Average
  Write-Host "$($.Name): $([math]::Round($throughput / 1GB, 2)) GB/s"
}

# Generate daily report
Get-Content "C:\rclone_logs\orchestrator.log" | Select-String "SUCCESS|FAILED|Duration" | tail -30 > daily_report_$(Get-Date -Format 'yyyyMMdd').txt
```

---

## 📞 Support & Escalation Matrix

| Issue | Owner | Action | Priority |
|-------|-------|--------|----------|
| Low throughput | Network Team | Check bandwidth, latency | High |
| OBS errors (403, 404) | OBS Admin | Verify credentials, permissions | Critical |
| S3 errors (no space) | AWS Team | Check bucket quota, lifecycle | Critical |
| Memory/CPU issues | Ops Team | Scale up machine or reduce concurrency | High |
| Persistent failures | Engineering | Review logs, debug with Rclone team | Medium |

---

**Last Updated**: 2024-11-15  
**Severity Levels**: 🔴 Critical | 🟠 High | 🟡 Medium | 🟢 Low
