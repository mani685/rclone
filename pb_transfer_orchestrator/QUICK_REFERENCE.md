# QUICK REFERENCE CARD

## One-Page Cheat Sheet for Petabyte-Scale Transfers

---

## 🚀 QUICK START (5 Minutes)

```powershell
# 1. Copy config
Copy-Item rclone.conf.example $env:APPDATA\rclone\rclone.conf

# 2. Edit credentials
notepad $env:APPDATA\rclone\rclone.conf

# 3. Test
rclone lsjson obs-source:bucket | head -1
rclone lsjson s3-dest:bucket | head -1

# 4. Run
.\orchestrate_pb_transfer.ps1 -ShardCount 24 -DryRun $false
```

---

## 📊 MEMORY & PERFORMANCE FORMULA

```
Memory per worker = Transfers × S3Concurrency × ChunkSizeMB (in MB)

Example:
  64 × 8 × 64 = 32,768 MB = 32 GB per worker
  × 4 workers = 128 GB total

Safe: Keep ≤ 50% of available RAM
```

---

## 🎛️ TUNING QUICK REFERENCE

| Scenario | Flag | Value |
|----------|------|-------|
| **Slow transfer** | `--transfers` | 64–128 |
| **Small files** | `--s3-chunk-size` | 5–32 MB |
| **Large files** | `--s3-chunk-size` | 64–256 MB |
| **Rate limit** | `--retries-sleep` | 10–30s |
| **Memory issue** | `--s3-upload-concurrency` | 2–4 |
| **Low latency** | `--s3-upload-cutoff` | 100–500 MB |

---

## 🔧 COMMAND EXAMPLES

### Dry Run (Safe Preview)
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 2 `
  -DryRun $true `
  -LogDir "C:\logs"
```

### Full Transfer (1 PB)
```powershell
.\orchestrate_pb_transfer.ps1 `
  -ShardCount 24 `
  -StartDate "2024-01-01" `
  -EndDate "2024-12-31" `
  -TransfersPerWorker 64 `
  -S3ChunkSizeMB 128 `
  -DryRun $false
```

### Resume Failed Shards
```powershell
# Just rerun; it auto-detects completed shards
.\orchestrate_pb_transfer.ps1 -ShardCount 24 -DryRun $false
```

---

## 📈 PERFORMANCE BASELINES

| Config | Throughput | 1 PB Time |
|--------|-----------|-----------|
| Light | 200 MB/s | 58 days |
| Medium | 1 GB/s | 12 days |
| Heavy | 5 GB/s | 2.4 days |
| Extreme | 20 GB/s | 14 hours |

---

## 🛠️ TROUBLESHOOTING QUICK FIXES

| Issue | Fix |
|-------|-----|
| **Low throughput** | Increase `--transfers` to 64–128 |
| **Out of memory** | Reduce `--s3-upload-concurrency` to 4 |
| **Rate limit (429)** | Add `--retries-sleep 30s` |
| **Job stalled** | Kill: `Stop-Job -Name "shard_*"` |
| **Stuck on list** | Increase `--checkers` to 32+ |

---

## 📊 MONITORING (Real-Time)

```powershell
# Terminal 1: Master log
Get-Content "C:\rclone_logs\orchestrator.log" -Tail 100 -Wait

# Terminal 2: Job status
Get-Job -Name "shard_*" | Select Name, State, PSBeginTime

# Terminal 3: Memory usage
Get-Process rclone | Select @{Name="MemGB"; Expression={$_.WorkingSet/1GB}}
```

---

## ✅ VERIFICATION AFTER TRANSFER

```bash
# Count objects
rclone count obs-source:bucket/
rclone count s3-dest:bucket/

# Compare sizes
rclone size obs-source:bucket/
rclone size s3-dest:bucket/

# Spot-check hashes
rclone check obs-source:bucket/2024-01/ s3-dest:bucket/2024-01/
```

---

## 💰 COST (1 PB + 1 Year)

| Component | Cost |
|-----------|------|
| Data transfer | $20–50K |
| Upload | $23K |
| Storage (INTELLIGENT_TIERING) | $150K |
| Compute | $5–15K |
| **TOTAL** | **$200–250K** |

**Save 50%**: Use lifecycle rules (transition to GLACIER after 90 days)

---

## 📋 CHECKLIST BEFORE STARTING

- [ ] Credentials in `rclone.conf`
- [ ] Connectivity verified (`rclone lsjson`)
- [ ] Dry-run successful
- [ ] Log directory created
- [ ] S3 lifecycle rules planned
- [ ] Network bandwidth > 100 Mbps
- [ ] Machine has 64+ GB RAM
- [ ] On-call team assigned

---

## 🚨 CRITICAL FLAGS

| Flag | Impact | Safe Default |
|------|--------|--------------|
| `--transfers` | File concurrency | 4 (increase to 64+) |
| `--s3-chunk-size` | Multipart size | 5M (increase to 64M+) |
| `--s3-upload-concurrency` | Chunks in parallel | 4 (increase to 8+) |
| `--retries` | High-level retries | 3 (increase to 5+) |
| `--low-level-retries` | Low-level retries | 10 (keep or increase to 20) |

---

## 📁 FILE QUICK MAP

| File | When to Read | Time |
|------|-------------|------|
| `README.md` | First (always) | 30m |
| `rclone.conf.example` | Before running | 10m |
| `orchestrate_pb_transfer.ps1` | When deploying | 15m |
| `ADVANCED_GUIDE.md` | For tuning | 1h |
| `TESTING_SCENARIOS.md` | For validation | 30m |
| `MONITORING_TROUBLESHOOTING.md` | If issues | 30m |
| `INDEX.md` | For navigation | 15m |

---

## 🎯 EXPECTED TIMELINE

| Phase | Time | Actions |
|-------|------|---------|
| Setup | 2–4h | Read docs, configure, test |
| Pilot | 1–2h | Run 100 GB smoke test |
| Production | 2–30 days | Run full transfer (depends on volume) |
| Verify | 2–4h | Count, compare, hash-check |

**Total**: 5–7 weeks for 1 PB

---

## 🔐 SECURITY CHECKLIST

- [ ] No credentials in code/git
- [ ] Use IAM roles (`env_auth = true`)
- [ ] S3 encryption enabled (default: AES-256)
- [ ] CloudTrail enabled for audit
- [ ] MFA on AWS account
- [ ] Rotate credentials every 90 days

---

## 💡 PRO TIPS

1. **Start small**: Test with 100 GB first (1–2 hours)
2. **Monitor daily**: Set alerts for > 1% error rate
3. **Use INTELLIGENT_TIERING**: Auto-transitions save 50% long-term
4. **Increase parallelism gradually**: Double transfers/concurrency if throughput is low
5. **Document everything**: Log all tuning parameters for future runs
6. **Automate verification**: Script the hash-check for large datasets
7. **Plan lifecycle rules**: Set up before transfer starts
8. **Keep logs**: Archive for 30 days minimum for troubleshooting

---

## 🆘 EMERGENCY CONTACTS

| Issue | Contact | Priority |
|-------|---------|----------|
| OBS errors | Huawei Support | Critical |
| S3 errors | AWS Support | Critical |
| Network issue | Network team | High |
| Performance | Engineering | Medium |

---

## 📞 QUICK HELP

**Q: Where do I start?**  
A: Read `README.md` (Quick Start section)

**Q: How do I configure it?**  
A: Copy `rclone.conf.example`, edit with your credentials

**Q: What if it's slow?**  
A: Increase `--transfers` to 64–128, `--s3-chunk-size` to 128M

**Q: What if it fails?**  
A: Check `MONITORING_TROUBLESHOOTING.md`, or just rerun script

**Q: How much will it cost?**  
A: ~$200–250K for 1 PB (see ADVANCED_GUIDE.md for details)

**Q: How do I verify?**  
A: Run `rclone check`, `rclone size` on source and dest

---

## ⏱️ TYPICAL DURATION

```
Setup:      1–2 hours (read, configure, test)
Dry-run:    0.5 hours (preview without transfer)
Pilot:      1–2 hours (100 GB test)
Full run:   2–30 days (depends on volume & network)
Verify:     2–4 hours (hash check, spot verify)

Total: ~5–7 weeks for 1 PB transfer
```

---

## 🎓 SKILL LEVELS

| Level | Start Here | Time |
|-------|-----------|------|
| Beginner | README.md | 1–2 hours |
| Intermediate | ADVANCED_GUIDE.md | 1–2 hours |
| Expert | Custom modifications | Varies |

---

**Keep this card handy during transfers!**

More details in: [INDEX.md](INDEX.md) (complete navigation guide)
