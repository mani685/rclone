# Petabyte-Scale OBSv2 → AWS S3 Transfer: Complete Operational Guide

This directory contains production-ready infrastructure for transferring petabytes of data from Huawei OBSv2 to AWS S3 using rclone with advanced orchestration, parallelism, resilience, and monitoring.

## 📋 Quick Start

### 1. Prerequisites
- **Rclone** installed (v1.50+): [https://rclone.org/downloads/](https://rclone.org/downloads/)
- **PowerShell** 5.1+ (Windows) or adapt script for bash/python
- **AWS credentials** with S3 access
- **Huawei OBS credentials** with read access
- **Sufficient local storage** for rclone cache (~10-50GB recommended)

### 2. Configure Credentials
Copy `rclone.conf.example` to your rclone config location:
```powershell
# Windows
Copy-Item rclone.conf.example $env:APPDATA\rclone\rclone.conf

# Then edit and populate credentials
notepad $env:APPDATA\rclone\rclone.conf
```

Verify connectivity:
```powershell
rclone lsjson obs-source:your-obs-bucket | Select-Object -First 1
rclone lsjson s3-dest:your-s3-bucket | Select-Object -First 1
```

### 3. Run Dry Test
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 1 `
  -StartDate "2024-01-01" `
  -EndDate "2024-01-02" `
  -DryRun $true `
  -LogDir "C:\rclone_logs"
```

Expected output: Shows planned shards and rclone commands without transferring data.

### 4. Run Production Transfer
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -StartDate "2024-01-01" `
  -EndDate "2024-12-31" `
  -DryRun $false `
  -LogDir "C:\rclone_logs" `
  -MaxRetries 5
```

Monitor progress:
```powershell
Get-Content -Path "C:\rclone_logs\orchestrator.log" -Tail 50 -Wait
```

---

## 📁 Files in This Directory

| File | Purpose |
|------|---------|
| **README.md** (this file) | Overview, quick start, and troubleshooting |
| **orchestrate_pb_transfer.ps1** | Main PowerShell orchestrator script for parallel job management |
| **rclone.conf.example** | Template rclone configuration with OBS and S3 examples |
| **ADVANCED_GUIDE.md** | Deep dive into tuning, memory, cost, and advanced scenarios |

---

## 🏗️ Architecture Overview

### How It Works

```
orchestrate_pb_transfer.ps1
  ├─ Parse parameters (ShardCount, dates, concurrency, retry settings)
  ├─ Generate Shards (date ranges split evenly)
  │   └─ Shard-1: 2024-01-01 to 2024-01-15
  │   └─ Shard-2: 2024-01-16 to 2024-01-31
  │   └─ ...
  │   └─ Shard-N: 2024-12-16 to 2024-12-31
  │
  └─ Orchestrate Parallel Jobs (up to 4 concurrent PowerShell jobs)
      ├─ Job-1: rclone sync obs-source:bucket/2024-01/ → s3-dest:bucket/2024-01/
      ├─ Job-2: rclone sync obs-source:bucket/2024-02/ → s3-dest:bucket/2024-02/
      ├─ Job-3: rclone sync obs-source:bucket/2024-03/ → s3-dest:bucket/2024-03/
      └─ Job-4: rclone sync obs-source:bucket/2024-04/ → s3-dest:bucket/2024-04/
      
  (As jobs complete, new shards are queued; repeat until all shards processed)
  
  └─ Outputs
      ├─ orchestrator.log: Master control log
      ├─ shard_*.log: Individual transfer logs
      ├─ job_database.csv: Job tracking DB
      └─ transfer_summary.json: Stats summary
```

### Why Sharding?

**Problem**: Single rclone process on 1PB dataset may:
- Exhaust available memory (listing 1 billion files)
- Hit API rate limits from OBS/S3
- Fail entirely if network interruption occurs

**Solution**: Divide data into independent shards (date ranges) and process in parallel:
- Each shard runs in a separate rclone process
- Failures are isolated; other shards continue
- Easy to resume or retry individual shards
- Parallelism scales with available machines/cores

### Key Parameters

| Parameter | Purpose | Typical Value |
|-----------|---------|----------------|
| `ShardCount` | # of date-based shards | 12–48 (1–2 shards/month) |
| `TransfersPerWorker` | Concurrent files per worker | 32–128 |
| `S3ChunkSizeMB` | Multipart chunk size | 32–128 |
| `S3UploadConcurrency` | Parallel chunks per file | 4–16 |
| `MaxConcurrentJobs` | Parallel workers (hardcoded to 4) | 2–8 (depends on machine) |

---

## 🚀 Running the Orchestrator

### Basic Command
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -StartDate "2024-01-01" `
  -EndDate "2024-12-31" `
  -DryRun $false
```

### With Custom Concurrency & Tuning
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -StartDate "2024-01-01" `
  -EndDate "2024-12-31" `
  -SourceRemote "obs-source" `
  -SourceBucket "my-obs-bucket" `
  -DestRemote "s3-dest" `
  -DestBucket "my-s3-bucket" `
  -LogDir "C:\rclone_logs" `
  -TransfersPerWorker 64 `
  -CheckersPerWorker 32 `
  -S3ChunkSizeMB 128 `
  -S3UploadConcurrency 16 `
  -S3UploadCutoffMB 500 `
  -MaxRetries 10 `
  -MaxLowLevelRetries 20 `
  -RetriesSleep "30s" `
  -DryRun $false
```

### Resuming Failed Shards
If a job crashes, rerun the orchestrator:
```powershell
# Orchestrator will check job_database.csv and skip completed shards
.\orchestrate_pb_transfer.ps1 -ShardCount 24 -DryRun $false
```

It automatically resumes failed shards while skipping successfully completed ones.

---

## 📊 Monitoring & Observability

### Real-Time Monitoring

**Master log** (overall progress):
```powershell
Get-Content -Path "C:\rclone_logs\orchestrator.log" -Tail 50 -Wait
```

**Individual shard logs** (detailed transfer stats):
```powershell
Get-Content -Path "C:\rclone_logs\shard_1.log" -Tail 100 -Wait
```

**Job status dashboard** (PowerShell):
```powershell
Get-Job -Name "shard_*" | Select Name, State, @{Name="Runtime"; Expression={(Get-Date) - $_.PSBeginTime}}

# Examples:
# shard_1  Completed  00:45:32
# shard_2  Completed  01:12:15
# shard_3  Running    00:23:11
# shard_4  Queued     00:00:00
```

### Key Metrics to Watch
- **Throughput**: Bytes/sec per worker (from rclone stats)
- **In-flight objects**: # of files being transferred
- **Error rate**: Failed/retried objects (from logs)
- **Latency**: Time per shard (from job_database.csv)

### Sample Log Output
```
[2024-11-15 10:30:15] [ORCHESTRATOR] Starting transfer orchestration (24 shards, 4 concurrent workers)
[2024-11-15 10:30:15] [ORCHESTRATOR] Shard 1: obs-source:bucket/2024-01/ → s3-dest:bucket/2024-01/
[2024-11-15 10:30:15] [ORCHESTRATOR] Spawning Job: shard_1 (PID 1234)
[2024-11-15 10:30:20] [shard_1] Transferred 1,234 files, 456 MB, 98.5% (1.2 MB/s)
[2024-11-15 10:35:45] [shard_1] Transfer complete: 15,678 files, 5.6 GB in 5m30s
[2024-11-15 10:35:45] [ORCHESTRATOR] Shard 1 SUCCESS (5m30s)
[2024-11-15 10:35:45] [ORCHESTRATOR] Spawning Job: shard_5 (PID 1235)
```

---

## ✅ Post-Transfer Verification

### Step 1: Count Objects
```powershell
# On source
rclone lsjson obs-source:my-obs-bucket/ --recursive | Measure-Object

# On destination
rclone lsjson s3-dest:my-s3-bucket/ --recursive | Measure-Object

# Compare counts (should be identical)
```

### Step 2: Compare Sizes
```powershell
# Total bytes on source
rclone size obs-source:my-obs-bucket/

# Total bytes on destination
rclone size s3-dest:my-s3-bucket/

# Should match (within metadata overhead)
```

### Step 3: Spot-Check Hashes (Sampling)
```bash
# Check a few dates
rclone check obs-source:my-obs-bucket/2024-01/ s3-dest:my-s3-bucket/2024-01/ -v

# Or via md5sum
rclone md5sum obs-source:my-obs-bucket/2024-01/ > source_hashes.txt
rclone md5sum s3-dest:my-s3-bucket/2024-01/ > dest_hashes.txt
diff source_hashes.txt dest_hashes.txt
```

### Step 4: Check for Incomplete Objects
```powershell
# List 0-byte objects (data loss indicator)
rclone lsjson s3-dest:my-s3-bucket/ --recursive | Where-Object { $_.Size -eq 0 }

# Should be empty
```

### Step 5: Verify S3 Storage Class
```bash
# Sample objects should show correct storage class
aws s3api head-object --bucket my-s3-bucket --key 2024-01-01/file1.txt | grep StorageClass

# Example output:
# "StorageClass": "INTELLIGENT_TIERING"
```

---

## 🔧 Troubleshooting

### Issue: "Rclone command not found"
**Solution**: Ensure rclone is in PATH:
```powershell
# Test
rclone --version

# If not found, add to PATH
$env:PATH += ";C:\Program Files\rclone"
```

### Issue: "Permission denied" errors in logs
**Solution**: Verify credentials and bucket permissions:
```powershell
# Test read from OBS
rclone lsjson obs-source:bucket | head -1

# Test write to S3
rclone touch s3-dest:bucket/test.txt
rclone delete s3-dest:bucket/test.txt
```

### Issue: "Rate limit exceeded" (429 errors)
**Solution**: Reduce concurrency and add sleep between retries:
```powershell
.\orchestrate_pb_transfer.ps1 `
  -TransfersPerWorker 16 `
  -CheckersPerWorker 8 `
  -MaxRetries 10 `
  -RetriesSleep "30s"
```

### Issue: "Out of memory" errors
**Solution**: Reduce chunk size and concurrency:
```powershell
.\orchestrate_pb_transfer.ps1 `
  -S3ChunkSizeMB 32 `
  -S3UploadConcurrency 4 `
  -TransfersPerWorker 16
```

### Issue: Jobs not progressing; stuck or hanging
**Solution**: Check individual shard logs:
```powershell
Get-Content -Path "C:\rclone_logs\shard_*.log" | Select-String "ERROR\|timeout\|stall"

# If stuck, kill and retry
Stop-Job -Name "shard_*"
.\orchestrate_pb_transfer.ps1 -ShardCount 24 -DryRun $false
```

### Issue: Incomplete transfer; script exited with errors
**Solution**: Resume from last completed shard:
```powershell
# Check job database
Import-Csv -Path "C:\rclone_logs\job_database.csv" | Where-Object Status -ne "Completed"

# Rerun orchestrator (skips completed shards automatically)
.\orchestrate_pb_transfer.ps1 -ShardCount 24 -DryRun $false
```

---

## 💾 Configuration Details

### OBS Configuration (rclone.conf)
```ini
[obs-source]
type = s3
provider = Other
endpoint = obs.cn-north-1.myhuaweicloud.com
access_key_id = <YOUR_KEY>
secret_access_key = <YOUR_SECRET>
region = cn-north-1
```

**Alternative via environment variables** (more secure):
```powershell
$env:RCLONE_CONFIG_OBS_SOURCE_TYPE = "s3"
$env:RCLONE_CONFIG_OBS_SOURCE_PROVIDER = "Other"
$env:RCLONE_CONFIG_OBS_SOURCE_ENDPOINT = "obs.cn-north-1.myhuaweicloud.com"
$env:RCLONE_CONFIG_OBS_SOURCE_ACCESS_KEY_ID = "..."
$env:RCLONE_CONFIG_OBS_SOURCE_SECRET_ACCESS_KEY = "..."
```

### AWS S3 Configuration (rclone.conf)
```ini
[s3-dest]
type = s3
provider = AWS
region = us-east-1
storage_class = INTELLIGENT_TIERING
acl = private
```

**IAM Role** (recommended for production):
```ini
[s3-dest]
type = s3
provider = AWS
env_auth = true
region = us-east-1
```

### Checking Active Configuration
```powershell
# Show all remotes
rclone listremotes

# Show config for a remote
rclone config show obs-source
rclone config show s3-dest
```

---

## 📈 Performance Tuning Guide

### For Different Data Sizes

| Data Size | ShardCount | TransfersPerWorker | S3ChunkSizeMB | Example |
|-----------|------------|-------------------|---------------|---------|
| 10 GB | 1 | 8 | 32 | Small test run |
| 100 GB | 2 | 16 | 32 | Pilot project |
| 1 TB | 4 | 32 | 64 | Medium dataset |
| 10 TB | 12 | 64 | 128 | Large dataset |
| 100 TB | 24 | 96 | 128 | Enterprise scale |
| 1+ PB | 48+ | 128+ | 256+ | Hyper-scale |

### Memory Estimation
```
Per-worker memory ≈ Transfers × S3Concurrency × ChunkSize

Example:
  64 × 8 × 64MB = 32 GB per worker

With 4 concurrent workers:
  32 GB × 4 = 128 GB total

Mitigation: Use machine with 256GB+ RAM, or reduce concurrency
```

### Network Bandwidth Utilization
```
Theoretical max throughput per worker:
  Transfers × S3Concurrency × ChunkSize / Upload_Latency

Example (assuming 50ms latency):
  64 × 8 × 64MB / 0.05s ≈ 655 GB/s per worker

Practical: 1–10 GB/s per worker (varies by network, OBS/S3 API limits)

Tuning: Increase Transfers/ChunkSize if network isn't saturated
```

---

## 🔐 Security & Compliance

### Credential Management
- **Never commit** credentials to git
- Use **IAM roles** in production (set `env_auth = true`)
- Store credentials in **secure config files** with restricted permissions
- Consider **AWS Secrets Manager** or **HashiCorp Vault** for large deployments

### Encryption
```ini
# Enable encryption in transit (S3 default)
[s3-dest]
type = s3
sse_kms_key_id = arn:aws:kms:us-east-1:123456789:key/12345678-1234-1234-1234-123456789012
```

### Audit & Logging
- Logs automatically saved to `C:\rclone_logs\`
- Enable S3 access logging: AWS Console → Bucket Properties → Server Access Logging
- Monitor with CloudWatch: Set up alarms for transfer failures

---

## 💰 Cost Estimation

### Data Transfer Costs (AWS S3)
- **Intra-region**: Free (if OBS is in same region)
- **Cross-region**: ~$0.02/GB
- **To Internet**: ~$0.09/GB

### Storage Costs (Monthly)
| Class | Cost/GB | Use Case |
|-------|---------|----------|
| STANDARD | $0.023 | Frequently accessed |
| INTELLIGENT_TIERING | $0.0125 | Auto-tiering (recommended) |
| STANDARD_IA | $0.0125 | 30+ day archival |
| GLACIER | $0.004 | Long-term archive |

**Example: 1 PB with INTELLIGENT_TIERING**
- Initial upload: 1 PB × $0.023/GB = $23,000
- Monthly storage (avg tier): 1 PB × $0.0125/GB = $12,500
- **Total annual**: ~$150,000

**Cost optimization**:
1. Use `--s3-storage-class INTELLIGENT_TIERING`
2. Set S3 lifecycle rules (auto-transition to GLACIER after 90 days)
3. Delete old data (set expiration)

---

## 📖 Additional Resources

| Topic | Link |
|-------|------|
| Rclone Docs | https://rclone.org/docs/ |
| S3 Backend | https://rclone.org/s3/ |
| AWS S3 Multipart | https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html |
| Huawei OBS | https://support.huaweicloud.com/intl/en-us/usermanual-obs/ |
| S3 Lifecycle Rules | https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lifecycle-mgmt.html |

---

## 📝 Example: Full End-to-End Transfer

### Day 1: Setup
```powershell
# 1. Install rclone (if needed)
choco install rclone

# 2. Create log directory
New-Item -ItemType Directory -Force -Path "C:\rclone_logs"

# 3. Configure credentials
notepad $env:APPDATA\rclone\rclone.conf
# [Copy sections from rclone.conf.example and populate]

# 4. Test connectivity
rclone lsjson obs-source:my-obs-bucket | Select-Object -First 1
rclone lsjson s3-dest:my-s3-bucket | Select-Object -First 1

# 5. Create S3 bucket (if needed)
aws s3api create-bucket --bucket my-s3-bucket --region us-east-1

# 6. Set S3 lifecycle rules
# [Create lifecycle.json, then apply]
aws s3api put-bucket-lifecycle-configuration --bucket my-s3-bucket --lifecycle-configuration file://lifecycle.json
```

### Day 2: Pilot Run (1 TB)
```powershell
# Dry-run first
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 2 `
  -StartDate "2024-01-01" `
  -EndDate "2024-01-15" `
  -DryRun $true

# Review output, then run for real
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 2 `
  -StartDate "2024-01-01" `
  -EndDate "2024-01-15" `
  -DryRun $false

# Monitor
Get-Content -Path "C:\rclone_logs\orchestrator.log" -Tail 50 -Wait

# Verify
rclone check obs-source:my-obs-bucket/2024-01/ s3-dest:my-s3-bucket/2024-01/
```

### Days 3–30: Full Transfer (1 PB)
```powershell
# Run full orchestration
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -StartDate "2024-01-01" `
  -EndDate "2024-12-31" `
  -DryRun $false `
  -MaxRetries 5

# Monitor daily
Get-Content -Path "C:\rclone_logs\orchestrator.log" | tail -100
Import-Csv -Path "C:\rclone_logs\job_database.csv" | tail -20
```

### Day 31: Verification
```bash
# Compare counts
rclone size obs-source:my-obs-bucket/
rclone size s3-dest:my-s3-bucket/

# Spot-check
rclone check obs-source:my-obs-bucket/ s3-dest:my-s3-bucket/ -v --max-checkers 4

# Review cost (AWS Console)
# → Confirm storage class (INTELLIGENT_TIERING)
# → Verify lifecycle rules applied
```

---

## 🤝 Support & Contributing

### Issues or Questions?
1. Check logs: `Get-Content C:\rclone_logs\shard_*.log | Select-String "ERROR"`
2. Consult ADVANCED_GUIDE.md for tuning recommendations
3. Review rclone docs: https://rclone.org/docs/

### To Extend or Customize
- Edit `orchestrate_pb_transfer.ps1` to add custom logic
- Examples: add pre/post-transfer hooks, integrate with monitoring systems, etc.

---

## 📜 License & Attribution

This orchestrator uses **rclone** (MIT License): https://github.com/rclone/rclone

Designed for enterprise Petabyte-scale migrations. Feedback welcome!

---

**Last Updated**: 2024-11-15  
**Rclone Version**: 1.65+  
**Status**: Production Ready
