# Petabyte-scale OBSv2 → AWS S3 Transfer: Advanced Configuration & Reference
# This document provides complete reference for tuning and running massive transfers

## 1. ARCHITECTURE OVERVIEW

### High-Level Flow
```
[Rclone Orchestrator] 
  ↓
  → [Job Scheduler] (PowerShell Concurrent Jobs)
    ├→ [Shard Worker 1] (Prefix 2024-01/) → [rclone sync + multipart]
    ├→ [Shard Worker 2] (Prefix 2024-02/) → [rclone sync + multipart]
    └→ [Shard Worker N] (Prefix 2024-MM/) → [rclone sync + multipart]
  ↓
[AWS S3 Destination] + [Lifecycle Rules] → [Tiering/Archival]
```

### Core Components in rclone
- **fs/operations**: High-level copy/move/sync operations
- **fs/sync**: Bidirectional sync and walk logic
- **fs/march**: Parallel directory tree walker
- **backend/s3**: S3 client; handles multipart uploads, storage class, tagging
- **lib/pacer**: Rate limiter + exponential backoff
- **lib/rest**: HTTP wrapper for OAuth, SDK calls

### Transfer Pipeline for Each File
1. Source Object Listed → fs/march walker
2. Destination check (if exists, skip or delete based on flags)
3. For large files (> `--s3-upload-cutoff`): Multipart upload with `--s3-upload-concurrency` parts in parallel
4. For small files: Single-part upload
5. Retry on transient errors via `lib/pacer` (low-level) and `--retries` (high-level)
6. Checksum validation (optional)

---

## 2. CONFIGURATION FILE (rclone.conf)

### Huawei OBS Configuration
```ini
[obs-source]
type = s3
provider = Other
env_auth = false
access_key_id = <YOUR_OBS_ACCESS_KEY>
secret_access_key = <YOUR_OBS_SECRET_KEY>
endpoint = obs.cn-north-1.myhuaweicloud.com
region = cn-north-1
acl = private
storage_class = STANDARD
```

**Alternative: Swift Protocol (OpenStack compatible)**
```ini
[obs-source-swift]
type = swift
user = <YOUR_OBS_USERNAME>
key = <YOUR_OBS_PASSWORD>
auth_url = https://iam.myhuaweicloud.com:443/v3
tenant = <YOUR_PROJECT_ID>
region_name = cn-north-1
```

### AWS S3 Configuration
```ini
[s3-dest]
type = s3
provider = AWS
env_auth = false
access_key_id = <YOUR_AWS_KEY>
secret_access_key = <YOUR_AWS_SECRET>
region = us-east-1
acl = private
storage_class = INTELLIGENT_TIERING

# For archive scenario (lower cost):
[s3-dest-archive]
type = s3
provider = AWS
env_auth = false
access_key_id = <YOUR_AWS_KEY>
secret_access_key = <YOUR_AWS_SECRET>
region = us-west-2
acl = private
storage_class = GLACIER
```

**Pro Tip**: Use `env_auth = true` for production + IAM roles (avoids storing keys in plaintext)

---

## 3. TRANSFER FLAGS & TUNING

### Global Concurrency Flags
| Flag | Default | Recommendation | Impact |
|------|---------|-----------------|--------|
| `--transfers` | 4 | 32–128 per worker | # of files xferred in parallel |
| `--checkers` | 8 | 16–64 per worker | # of list/check ops in parallel |
| `--fast-list` | N/A | **Enable** | Faster directory listing (fewer API calls) |

### S3-Specific Flags
| Flag | Default | Recommendation | Impact |
|------|---------|-----------------|--------|
| `--s3-chunk-size` | 5Mi | 32–128M | Size of each multipart chunk |
| `--s3-upload-concurrency` | 4 | 8–16 | # of chunks uploaded in parallel per file |
| `--s3-upload-cutoff` | 200Mi | 100–500Mi | File size threshold to use multipart |
| `--s3-storage-class` | STANDARD | INTELLIGENT_TIERING or STANDARD_IA | Storage class for uploaded objects |
| `--s3-disable-checksum` | false | **true** for speed | Skip ETag validation (trust S3) |

### Retry & Resilience Flags
| Flag | Default | Recommendation | Impact |
|------|---------|-----------------|--------|
| `--retries` | 3 | 5–10 | High-level retry attempts |
| `--low-level-retries` | 10 | 10–20 | SDK-level (HTTP) retries |
| `--retries-sleep` | 0s | 5–10s | Sleep between retries (backoff) |

### Performance & Logging Flags
| Flag | Default | Recommendation | Impact |
|------|---------|-----------------|--------|
| `--buffer-size` | 16Mi | 32–256Mi | In-memory buffer for streaming |
| `--max-transfer` | N/A | Set if needed | Max bytes to transfer before stopping |
| `--log-file` | stdout | `/var/log/rclone_*.log` | Log file path per job |
| `--stats` | 1m | 10s | Stats update interval |
| `-P, --progress` | N/A | **Enable** | Show progress in real-time |
| `-vv` | N/A | **Enable** | Verbose logging (debug) |

---

## 4. PETABYTE-SCALE STRATEGIES

### Memory Footprint Calculation
```
Memory ≈ --transfers × --s3-upload-concurrency × --s3-chunk-size × Number_of_Workers

Example:
  Per-worker: 32 × 8 × 64M = 16GB
  With 4 workers: 16GB × 4 = 64GB total
  
Mitigation:
  - Reduce --s3-upload-concurrency to 4–8 per worker
  - Reduce --transfers to 16–32 per worker
  - Run fewer concurrent workers (use orchestrator to manage)
  - Run on large memory machines (128GB+)
```

### Multipart Upload: File Size Limits
```
AWS S3 Limits:
  - Max parts per upload: 10,000
  - Min part size: 5MB (except last part)
  - Max part size: 5GB
  
Calculation for large objects:
  For 5TB object:
    Min chunk size = 5TB / 10000 = 512MB
    Set --s3-chunk-size 512M

  For typical mixed-size dataset:
    Use --s3-chunk-size 64M (handles objects up to ~640GB)
    For larger objects, increase chunk size proportionally
```

### Bandwidth & Pacing
```
Rclone's pacer enforces request rate limits per backend:

Low-level retries (lib/pacer):
  - Exponential backoff on transient errors (503, 429)
  - Default: 10 retries with increasing backoff
  - Tunable via --low-level-retries

High-level retries:
  - Operation-level retries (full file transfer)
  - Sleep between attempts via --retries-sleep
  - Useful for quota/rate-limit scenarios
  
Example for throttled APIs:
  --retries 5 --retries-sleep 30s --low-level-retries 20
```

### Sharding Strategy for PB Scale
**Date-based Sharding (Recommended)**
```
Assumption: Data organized by date prefix (e.g., s3://bucket/2024-01-01/, 2024-01-02/, ...)

Shard 1: 2024-01-01 to 2024-01-15 (15 days)
Shard 2: 2024-01-16 to 2024-01-31 (16 days)
...
Shard N: 2024-12-16 to 2024-12-31 (16 days)

Benefits:
  - Even distribution if data is balanced by date
  - Easy to parallelize and track
  - Can resume individual shards
```

**Hash-based Sharding (Alternative)**
```
For non-hierarchical data:
  Split by object name hash to distribute evenly

  rclone sync SOURCE DEST --include "{'hash-prefix-00'}/**" 
  rclone sync SOURCE DEST --include "{'hash-prefix-01'}/**"
  ...
  
Requires:
  - Pre-computed hash values or wrapper logic
  - More complex to implement
```

---

## 5. LIFECYCLE & RETENTION MAPPING

### Approach 1: S3 Lifecycle Rules (Recommended for PB)
```
Set lifecycle rules on destination S3 bucket BEFORE transfer:

Rule 1: INTELLIGENT_TIERING
  - Upload all objects as STANDARD initially
  - S3 auto-transitions to cheaper tiers based on access patterns
  - No extra rclone logic needed

Rule 2: Transition to GLACIER after N days
  {
    "Rules": [
      {
        "Filter": { "Prefix": "archive/" },
        "Transitions": [
          {
            "Days": 30,
            "StorageClass": "STANDARD_IA"
          },
          {
            "Days": 365,
            "StorageClass": "GLACIER"
          }
        ],
        "Expiration": {
          "Days": 2555
        }
      }
    ]
  }

Advantage: Transparent, serverless, handles PB-scale automatically
```

### Approach 2: Tags + Retention at Upload (Alternative)
```
Rclone can tag objects via metadata:

# Upload with metadata (if S3 supports via backend):
rclone sync SOURCE DEST --metadata 'retention-period:30d,archive-class:standard-ia'

# Then set S3 lifecycle rules based on tags:
Rule: Objects tagged retention-period=30d → GLACIER after 30 days

Limitation: Requires custom metadata support; most backends don't expose S3 tagging via rclone flags
```

### Approach 3: Separate Bucket by Retention
```
Shard jobs to different S3 buckets based on retention:

Shard 1-6 (Jan-Jun):   → s3-dest (INTELLIGENT_TIERING)
Shard 7-9 (Jul-Sep):   → s3-dest-standard (STANDARD, will be archived later)
Shard 10-12 (Oct-Dec): → s3-dest-archive (GLACIER, deep archive)

Then, apply bucket-level lifecycle policies to each.
```

**Recommendation**: Use Approach 1 (Lifecycle Rules) for simplicity and scalability.

---

## 6. RUNNING THE ORCHESTRATOR

### Prerequisites
1. **Rclone installed** and in PATH:
   ```powershell
   rclone --version
   ```

2. **Credentials configured** in `~/.config/rclone/rclone.conf` (or set via environment):
   ```powershell
   $env:RCLONE_CONFIG = "C:\rclone\rclone.conf"
   ```

3. **Permissions**:
   - OBS: `ListBucket`, `GetObject` on source
   - S3: `ListBucket`, `PutObject`, `PutObjectAcl` on destination

### Run Orchestrator

**Dry-run first** (verify shards and paths):
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -StartDate "2024-01-01" `
  -EndDate "2024-12-31" `
  -DryRun $true `
  -LogDir "C:\rclone_logs" `
  -SourceRemote "obs-source" `
  -SourceBucket "my-obs-bucket" `
  -DestRemote "s3-dest" `
  -DestBucket "my-s3-bucket" `
  -TransfersPerWorker 32 `
  -CheckersPerWorker 16 `
  -S3ChunkSizeMB 64 `
  -S3UploadConcurrency 8 `
  -S3UploadCutoffMB 200
```

**Production run** (set DryRun to $false):
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -StartDate "2024-01-01" `
  -EndDate "2024-12-31" `
  -DryRun $false `
  -LogDir "C:\rclone_logs" `
  -SourceRemote "obs-source" `
  -SourceBucket "my-obs-bucket" `
  -DestRemote "s3-dest" `
  -DestBucket "my-s3-bucket" `
  -TransfersPerWorker 32 `
  -CheckersPerWorker 16 `
  -S3ChunkSizeMB 64 `
  -S3UploadConcurrency 8 `
  -S3UploadCutoffMB 200 `
  -MaxRetries 5 `
  -MaxLowLevelRetries 10 `
  -RetriesSleep "10s"
```

### Outputs
- `orchestrator.log`: Master log
- `shard_*.log`: Individual shard logs (detailed rclone output)
- `job_database.csv`: Job status and statistics
- `transfer_summary.json`: Overall transfer stats

---

## 7. MONITORING & VERIFICATION

### Real-time Monitoring
```powershell
# Watch logs in real-time
Get-Content -Path "C:\rclone_logs\orchestrator.log" -Tail 20 -Wait

# Check job status
Get-Job -Name "shard_*" | Select Name, State, @{Name="Runtime"; Expression={(Get-Date) - $_.PSBeginTime}}

# Stream individual shard logs
Get-Content -Path "C:\rclone_logs\shard_1.log" -Tail 50 -Wait
```

### Post-Transfer Verification
```bash
# 1. Count objects on source and destination
rclone lsjson obs-source:my-obs-bucket/ --recursive | jq '. | length'
rclone lsjson s3-dest:my-s3-bucket/ --recursive | jq '. | length'

# 2. Compare sizes
rclone size obs-source:my-obs-bucket/
rclone size s3-dest:my-s3-bucket/

# 3. Run checksum validation (if hashes match)
rclone check obs-source:my-obs-bucket/ s3-dest:my-s3-bucket/ -v

# 4. Spot-check samples
rclone md5sum obs-source:my-obs-bucket/2024-01/ | head -100 > source_hashes.txt
rclone md5sum s3-dest:my-s3-bucket/2024-01/ | head -100 > dest_hashes.txt
diff source_hashes.txt dest_hashes.txt
```

### Metrics to Track
- **Throughput**: Bytes/s per worker, aggregate
- **Concurrency**: # of in-flight transfers per worker
- **Error rate**: % of failed objects (retry-able vs. permanent)
- **Latency**: Time per shard, per object size bucket
- **Memory usage**: Peak RSS per worker

---

## 8. HANDLING FAILURES & RECOVERY

### Transient Errors (Retryable)
Examples: 503 Service Unavailable, 429 Rate Limit, timeout

**Rclone's handling**:
- Low-level retry via `lib/pacer` (automatically retries with exponential backoff)
- High-level retry via `--retries` (re-runs the entire file transfer)

**Mitigation**:
- Increase `--low-level-retries` (default 10, try 20)
- Increase `--retries-sleep` (default 0s, try 10s) to avoid hammering throttled APIs

### Permanent Errors (Non-retryable)
Examples: 403 Forbidden (permission denied), 404 Not Found, corrupted object

**Rclone's handling**:
- Logs error and continues to next file
- Error count tracked in transfer stats

**Mitigation**:
- Fix permissions before transfer
- Validate source data integrity
- Manually investigate and rerun failed shards

### Worker Crash / Job Failure
If a job crashes mid-transfer:

**Recovery**:
1. Check logs: `tail -f C:\rclone_logs\shard_*.log`
2. Identify failed shard (e.g., `shard_5`)
3. Rerun single shard manually:
   ```powershell
   rclone sync obs-source:my-obs-bucket/2024-05 s3-dest:my-s3-bucket/2024-05 \
     --transfers=32 --checkers=16 --s3-chunk-size=64M --s3-upload-concurrency=8
   ```
4. Verify completion, then update job database

---

## 9. COST OPTIMIZATION

### S3 Storage Class Selection
| Class | Cost | Use Case |
|-------|------|----------|
| STANDARD | Highest | Frequently accessed data |
| STANDARD_IA | Lower | Infrequently accessed, 30-day min |
| INTELLIGENT_TIERING | Medium | Auto-transitions based on access |
| GLACIER | Low | Archive, 90-day min, slow retrieval |
| DEEP_ARCHIVE | Lowest | Long-term archive, 180-day min |

**For PB-scale transfer**:
- Upload everything to **INTELLIGENT_TIERING** (rclone: `--s3-storage-class INTELLIGENT_TIERING`)
- Let S3 auto-transition to cheaper tiers
- Total cost: ~20% of STANDARD after 30 days

### S3 Lifecycle Rules Example
```json
{
  "Rules": [
    {
      "Id": "auto-tiering",
      "Status": "Enabled",
      "Transitions": [
        {
          "Days": 0,
          "StorageClass": "INTELLIGENT_TIERING"
        },
        {
          "Days": 90,
          "StorageClass": "GLACIER"
        },
        {
          "Days": 365,
          "StorageClass": "DEEP_ARCHIVE"
        }
      ],
      "Expiration": {
        "Days": 2555
      }
    }
  ]
}
```

### Data Transfer Cost
- **Within region**: Minimal/free
- **Cross-region**: $0.02/GB (highly variable)
- **To Internet**: $0.09/GB (expensive for PB)

**Recommendation**: Transfer into AWS region closest to OBS source, then use S3 replication to other regions if needed.

---

## 10. ADVANCED TUNING

### Scenario 1: Limited Bandwidth (< 10 Gbps)
```powershell
--transfers 8
--checkers 8
--s3-chunk-size 32M
--s3-upload-concurrency 4
--buffer-size 32M
```
**Rationale**: Reduce in-flight requests to avoid overwhelming slow link

### Scenario 2: Plenty of Bandwidth (> 100 Gbps)
```powershell
--transfers 128
--checkers 64
--s3-chunk-size 128M
--s3-upload-concurrency 16
--buffer-size 256M
```
**Rationale**: Maximize parallelism to saturate high-speed networks

### Scenario 3: Many Small Files (avg < 10MB)
```powershell
--transfers 256
--checkers 128
--s3-chunk-size 5M
--s3-upload-concurrency 1 (or 2 for safety)
--s3-upload-cutoff 50M
```
**Rationale**: Single-part uploads for most files; minimize overhead

### Scenario 4: Few Large Files (avg > 5GB)
```powershell
--transfers 4
--checkers 4
--s3-chunk-size 256M
--s3-upload-concurrency 16
--s3-upload-cutoff 1G
```
**Rationale**: Maximize parallel chunk uploads for large objects

---

## 11. OPERATIONAL CHECKLIST

### Pre-Transfer
- [ ] Credentials in `rclone.conf` (or env vars)
- [ ] Test connectivity: `rclone lsjson obs-source:bucket | head`
- [ ] Verify AWS S3 bucket exists and is writable
- [ ] Set S3 bucket lifecycle rules
- [ ] Dry-run orchestrator script
- [ ] Calculate storage cost
- [ ] Allocate machines/resources (CPU, RAM, network)

### During Transfer
- [ ] Monitor logs: `tail -f orchestrator.log`
- [ ] Watch shard-specific logs for errors
- [ ] Track transfer rate and ETA
- [ ] Check system resources (CPU, RAM, disk, network)
- [ ] Alert on failures

### Post-Transfer
- [ ] Verify byte counts match (size check)
- [ ] Run spot-check sampling (hash validation)
- [ ] Check for 0-byte or partially uploaded objects
- [ ] Verify S3 storage class assigned
- [ ] Clean up temporary files/cache
- [ ] Document transfer summary (duration, cost, throughput)

---

## 12. EXAMPLE: COMPLETE END-TO-END RUN

### Setup (one-time)
```powershell
# 1. Install rclone (if not present)
choco install rclone  # or download from rclone.org

# 2. Configure rclone.conf
notepad $env:APPDATA\rclone\rclone.conf
# [Add obs-source and s3-dest sections from examples above]

# 3. Test connectivity
rclone lsjson obs-source:my-obs-bucket | head -1
rclone lsjson s3-dest:my-s3-bucket | head -1

# 4. Create S3 bucket (if needed)
aws s3api create-bucket --bucket my-s3-bucket --region us-east-1

# 5. Set S3 lifecycle rules (save to lifecycle.json, then apply)
aws s3api put-bucket-lifecycle-configuration --bucket my-s3-bucket --lifecycle-configuration file://lifecycle.json
```

### Transfer Run
```powershell
cd C:\path\to\pb_transfer_orchestrator

# Dry-run
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -StartDate "2024-01-01" `
  -EndDate "2024-12-31" `
  -DryRun $true

# Review logs, then run for real
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -StartDate "2024-01-01" `
  -EndDate "2024-12-31" `
  -DryRun $false

# Monitor in separate terminal
Get-Content -Path "C:\rclone_logs\orchestrator.log" -Tail 30 -Wait
```

### Verification
```bash
# Check counts
rclone size obs-source:my-obs-bucket/
rclone size s3-dest:my-s3-bucket/

# Spot-check
rclone check obs-source:my-obs-bucket/2024-01/ s3-dest:my-s3-bucket/2024-01/

# Clean up
Remove-Item -Path "C:\rclone_logs\*" -Recurse -Confirm  # After verifying success
```

---

## REFERENCES

- **Rclone Docs**: https://rclone.org/docs/
- **S3 Backend Docs**: https://rclone.org/s3/
- **AWS S3 Multipart Docs**: https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html
- **Huawei OBS Docs**: https://support.huaweicloud.com/intl/en-us/usermanual-obs/
- **S3 Lifecycle Rules**: https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lifecycle-mgmt.html

