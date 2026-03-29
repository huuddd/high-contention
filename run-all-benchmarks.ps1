# =============================================================================
# High Contention Ticketing — Automated Benchmark Runner
# =============================================================================
# Runs benchmarks on all implemented strategy branches, parses k6 output,
# collects stats from /events/{id}/stats, and auto-fills docs/bench-final.md.
#
# Prerequisites:
#   - Docker Desktop running (PostgreSQL + Redis)
#   - Java 17+, Maven, k6 installed
#   - All impl/* branches merged from main (have load-test/ scripts)
#
# Usage:
#   .\run-all-benchmarks.ps1                    # Run all strategies
#   .\run-all-benchmarks.ps1 -Only naive        # Run single strategy
#   .\run-all-benchmarks.ps1 -SkipBurst         # Skip B3 (saves time)
# =============================================================================

param(
    [ValidateSet("naive", "pessimistic", "occ", "serializable", "reservation", "queue", "all")]
    [string]$Only = "all",
    [switch]$SkipBurst,
    [int]$AppStartTimeoutSec = 120,
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Continue"
$scriptRoot = $PSScriptRoot
if (-not $scriptRoot) { $scriptRoot = (Get-Location).Path }

# --- Strategy definitions ---------------------------------------------------
$allStrategies = @(
    @{Key="naive";        Label="A: Naive";        Branch="impl/naive";                BenchTarget="naive"},
    @{Key="pessimistic";  Label="B: Pessimistic";  Branch="impl/pessimistic-locking";  BenchTarget="pessimistic"},
    @{Key="occ";          Label="C: OCC";          Branch="impl/occ";                  BenchTarget="occ"},
    @{Key="serializable"; Label="D: SERIALIZABLE"; Branch="impl/serializable";         BenchTarget="serializable"},
    @{Key="reservation";  Label="E: Reservation";  Branch="impl/reservation-fencing";  BenchTarget="reservation"},
    @{Key="queue";        Label="F: Queue";         Branch="impl/queue-based";          BenchTarget="queue"}
)

# Filter strategies
if ($Only -ne "all") {
    $strategies = $allStrategies | Where-Object { $_.Key -eq $Only }
} else {
    $strategies = $allStrategies
}

# --- Timestamp & paths ------------------------------------------------------
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$rawResultsDir = Join-Path $scriptRoot "benchmark-raw-$timestamp"
$benchFinalPath = Join-Path (Join-Path $scriptRoot "docs") "bench-final.md"

# PS5-compatible: no ?? operator
$originalBranch = git rev-parse --abbrev-ref HEAD 2>$null
if (-not $originalBranch) { $originalBranch = "main" }

New-Item -ItemType Directory -Path $rawResultsDir -Force | Out-Null

# Dash placeholder (avoids encoding corruption of em dash)
$DASH = "-"

# =============================================================================
# Helper functions
# =============================================================================

function Write-Step { param([string]$msg) Write-Host "  $msg" -ForegroundColor Yellow }
function Write-Ok   { param([string]$msg) Write-Host "  $msg" -ForegroundColor Green }
function Write-Err  { param([string]$msg) Write-Host "  $msg" -ForegroundColor Red }
function Write-Info { param([string]$msg) Write-Host "  $msg" -ForegroundColor Gray }

function Test-BranchExists {
    param([string]$branch)
    git rev-parse --verify $branch 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Test-HasStrategyCode {
    param([string]$branch)
    # Check if branch has ANY java files under the ticketing source tree
    $files = git ls-tree $branch --name-only -r 2>$null | Where-Object { $_ -match "\.java$" }
    return ($null -ne $files -and @($files).Count -gt 0)
}

function Stop-AppProcess {
    $procs = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue |
             Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $procs) {
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
    Get-Process -Name "java" -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -match "spring-boot" -or $_.MainWindowTitle -match "ticketing"
    } | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

function Start-AppAndWait {
    param([int]$timeout = $AppStartTimeoutSec)

    Stop-AppProcess

    $appProc = Start-Process -FilePath "powershell" -ArgumentList @(
        "-NoProfile", "-Command",
        "Set-Location '$scriptRoot'; cd source/ticketing-service; mvn spring-boot:run"
    ) -PassThru -WindowStyle Minimized

    $waited = 0
    while ($waited -lt $timeout) {
        Start-Sleep -Seconds 3
        $waited += 3
        try {
            $r = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
            if ($r.StatusCode -eq 200) {
                Write-Ok "App ready (${waited}s)"
                return $appProc
            }
        } catch {}
        if ($waited % 15 -eq 0) { Write-Info "Waiting... ${waited}/${timeout}s" }
    }
    Write-Err "App failed to start within ${timeout}s"
    Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue
    return $null
}

function Reset-Database {
    Write-Step "[DB] Resetting..."
    make db-reset 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    make migrate 2>&1 | Out-Null
    make seed 2>&1 | Out-Null
    Write-Ok "[DB] Fresh: migrated + seeded"
}

function Run-K6 {
    param([string]$script, [string]$strategy, [string]$extraEnv = "")
    # Reservation strategy uses different k6 scripts (2-step flow)
    if ($strategy -eq "reservation") {
        $script = $script -replace "stock1\.js", "stock1-reservation.js"
        $script = $script -replace "hot-seat\.js", "hot-seat-reservation.js"
        $script = $script -replace "burst\.js", "burst-reservation.js"
    }
    $cmd = "k6 run $script -e BASE_URL=$BaseUrl -e STRATEGY=$strategy $extraEnv --summary-export=- 2>&1"
    $output = Invoke-Expression $cmd | Out-String
    return $output
}

function Parse-K6Output {
    param([string]$output)
    $result = @{
        Successes       = Parse-Line $output "Successes:"
        Conflicts       = Parse-Line $output "Conflicts:"
        Errors          = Parse-Line $output "Errors:"
        "503s"          = Parse-Line $output "503s:"
        p50             = Parse-Line $output "p50:"
        p95             = Parse-Line $output "p95:"
        p99             = Parse-Line $output "p99:"
        max             = Parse-Line $output "max:"
        SoldCount       = Parse-Line $output "DB soldCount:"
        Available       = Parse-Line $output "DB availableSeats:"
        Consistent      = Parse-Line $output "DB consistent:"
        MetricConflicts = Parse-Line $output "Metric conflicts:"
        MetricRetries   = Parse-Line $output "Metric retries:"
        MetricDeadlocks = Parse-Line $output "Metric deadlocks:"
        MetricTimeouts  = Parse-Line $output "Metric timeouts:"
        MetricOversells = Parse-Line $output "Metric oversells:"
        Oversell        = $false
    }
    if ($output -match "OVERSELL DETECTED") { $result.Oversell = $true }
    return $result
}

function Parse-Line {
    param([string]$text, [string]$label)
    $escaped = [regex]::Escape($label)
    if ($text -match "$escaped\s+(\S+)") {
        $val = $Matches[1] -replace "ms$", "" -replace '"', '' -replace "'", '' -replace ',', ''
        $val = $val.Trim()
        # Return only if looks like a number or valid value
        if ($val -match '^[\d\.]+$' -or $val -match '^(true|false|N/A|OK)$') {
            return $val
        }
        return $val  # Still return cleaned string even if not matching
    }
    return $DASH
}

function Get-StatsFromApi {
    try {
        $uri = $BaseUrl + "/events/1/stats"
        $r = Invoke-RestMethod -Uri $uri -UseBasicParsing -ErrorAction Stop
        return $r
    } catch {
        return $null
    }
}

function Determine-Oversell {
    param($k6, $stats, [string]$scenario)
    if ($scenario -eq "B1" -and $k6.Successes -ne $DASH -and $k6.Successes -match '^\d+$') {
        try {
            $successCount = [int]$k6.Successes
            if ($successCount -gt 1) { return "Yes ($($k6.Successes) sold)" }
        } catch {}
    }
    if ($stats -and $stats.metrics -and $stats.metrics.oversells -gt 0) {
        return "Yes ($($stats.metrics.oversells))"
    }
    if ($k6.Oversell) { return "Yes" }
    if ($stats -and -not $stats.consistent) { return "Inconsistent" }
    return "No"
}

# =============================================================================
# Main loop — run benchmarks for each strategy
# =============================================================================

$results = @{}

Write-Host "`n" -NoNewline
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  HIGH CONTENTION TICKETING - AUTOMATED BENCHMARK" -ForegroundColor Cyan
Write-Host "  Timestamp: $timestamp" -ForegroundColor Cyan
Write-Host "  Strategies: $($strategies | ForEach-Object { $_.Key }) " -ForegroundColor Cyan
$scenarioLine = "  Scenarios: B1 (Stock=1), B2 (Hot-Seat)"
if (-not $SkipBurst) { $scenarioLine += ", B3 (Burst)" }
Write-Host $scenarioLine -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

foreach ($strat in $strategies) {
    $key         = $strat.Key
    $branch      = $strat.Branch
    $label       = $strat.Label
    $benchTarget = $strat.BenchTarget

    Write-Host "`n------------------------------------------------------------" -ForegroundColor Magenta
    Write-Host "  Strategy: $label ($branch)" -ForegroundColor Magenta
    Write-Host "------------------------------------------------------------" -ForegroundColor Magenta

    if (-not (Test-BranchExists $branch)) {
        Write-Err "Branch $branch does not exist -- skipping"
        $results[$key] = @{ Status = "branch_missing" }
        continue
    }

    if (-not (Test-HasStrategyCode $branch)) {
        Write-Err "No strategy code on $branch -- skipping"
        $results[$key] = @{ Status = "not_implemented" }
        continue
    }

    Write-Step "[GIT] Checking out $branch..."
    git checkout $branch 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Failed to checkout $branch"
        $results[$key] = @{ Status = "checkout_failed" }
        continue
    }
    Write-Ok "[GIT] On $branch"

    $results[$key] = @{ Status = "ok" }

    # --- B1: Stock=1 ---
    Write-Host "`n  --- B1: Stock=1 (Extreme Contention) ---" -ForegroundColor White
    Reset-Database
    $appProc = Start-AppAndWait
    if ($appProc) {
        Write-Step "[K6] Running B1..."
        $b1Output = Run-K6 "load-test/stock1.js" $benchTarget
        $b1 = Parse-K6Output $b1Output
        $b1Stats = Get-StatsFromApi
        $b1.OversellResult = Determine-Oversell $b1 $b1Stats "B1"

        if ($b1Stats) {
            if ($b1.MetricDeadlocks -eq $DASH -and $b1Stats.metrics) { $b1.MetricDeadlocks = "$($b1Stats.metrics.deadlocks)" }
            if ($b1.MetricRetries   -eq $DASH -and $b1Stats.metrics) { $b1.MetricRetries   = "$($b1Stats.metrics.retries)"   }
        }

        $results[$key]["B1"] = $b1
        $b1Output | Out-File -FilePath (Join-Path $rawResultsDir "${key}-B1.txt") -Encoding UTF8

        Write-Ok "[B1] Successes=$($b1.Successes) Conflicts=$($b1.Conflicts) p95=$($b1.p95)ms Oversell=$($b1.OversellResult)"
        Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    } else {
        $results[$key]["B1"] = @{ Status = "app_failed" }
    }

    # --- B2: Hot-Seat ---
    Write-Host "`n  --- B2: Hot-Seat (Realistic Contention) ---" -ForegroundColor White
    Reset-Database
    $appProc = Start-AppAndWait
    if ($appProc) {
        Write-Step "[K6] Running B2..."
        $b2Output = Run-K6 "load-test/hot-seat.js" $benchTarget
        $b2 = Parse-K6Output $b2Output
        $b2Stats = Get-StatsFromApi
        $b2.OversellResult = Determine-Oversell $b2 $b2Stats "B2"

        if ($b2Stats) {
            if ($b2.MetricDeadlocks -eq $DASH -and $b2Stats.metrics) { $b2.MetricDeadlocks = "$($b2Stats.metrics.deadlocks)" }
            if ($b2.MetricRetries   -eq $DASH -and $b2Stats.metrics) { $b2.MetricRetries   = "$($b2Stats.metrics.retries)"   }
        }

        $results[$key]["B2"] = $b2
        $b2Output | Out-File -FilePath (Join-Path $rawResultsDir "${key}-B2.txt") -Encoding UTF8

        Write-Ok "[B2] Successes=$($b2.Successes) Conflicts=$($b2.Conflicts) p95=$($b2.p95)ms Oversell=$($b2.OversellResult)"
        Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    } else {
        $results[$key]["B2"] = @{ Status = "app_failed" }
    }

    # --- B3: Burst (optional) ---
    if (-not $SkipBurst) {
        Write-Host "`n  --- B3: Burst (Flash Sale) ---" -ForegroundColor White
        Reset-Database
        $appProc = Start-AppAndWait
        if ($appProc) {
            Write-Step "[K6] Running B3..."
            $b3Output = Run-K6 "load-test/burst.js" $benchTarget
            $b3 = Parse-K6Output $b3Output
            $b3Stats = Get-StatsFromApi
            $b3.OversellResult = Determine-Oversell $b3 $b3Stats "B3"

            if ($b3Stats) {
                if ($b3.MetricDeadlocks -eq $DASH -and $b3Stats.metrics) { $b3.MetricDeadlocks = "$($b3Stats.metrics.deadlocks)" }
                if ($b3.MetricRetries   -eq $DASH -and $b3Stats.metrics) { $b3.MetricRetries   = "$($b3Stats.metrics.retries)"   }
                if ($b3.MetricTimeouts  -eq $DASH -and $b3Stats.metrics) { $b3.MetricTimeouts  = "$($b3Stats.metrics.timeouts)"  }
            }

            $results[$key]["B3"] = $b3
            $b3Output | Out-File -FilePath (Join-Path $rawResultsDir "${key}-B3.txt") -Encoding UTF8

            Write-Ok "[B3] Successes=$($b3.Successes) Conflicts=$($b3.Conflicts) 503s=$($b3.'503s') p95=$($b3.p95)ms"
            Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
        } else {
            $results[$key]["B3"] = @{ Status = "app_failed" }
        }
    }
}

Stop-AppProcess

# =============================================================================
# Generate bench-final.md
# =============================================================================

Write-Host "`n================================================================" -ForegroundColor Cyan
Write-Host "  Generating docs/bench-final.md..." -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

function V {
    param($r, [string]$field)
    if ($r -and $r[$field] -and $r[$field] -ne $DASH) { return $r[$field] }
    return $DASH
}

function Format-B1B2Row {
    param($label, $r)
    if (-not $r -or $r.Status -eq "app_failed") {
        return "| $label | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH |"
    }
    $oversell  = if ($r.OversellResult) { $r.OversellResult } else { $DASH }
    $deadlocks = V $r "MetricDeadlocks"
    $retries   = V $r "MetricRetries"
    return "| $label | $(V $r 'Successes') | $(V $r 'Conflicts') | $(V $r 'Errors') | $(V $r 'p50') | $(V $r 'p95') | $(V $r 'p99') | $oversell | $deadlocks | $retries |"
}

function Format-B3Row {
    param($label, $r)
    if (-not $r -or $r.Status -eq "app_failed") {
        return "| $label | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH |"
    }
    $oversell  = if ($r.OversellResult) { $r.OversellResult } else { $DASH }
    $deadlocks = V $r "MetricDeadlocks"
    $timeouts  = V $r "MetricTimeouts"
    return "| $label | $(V $r 'Successes') | $(V $r 'Conflicts') | $(V $r '503s') | $(V $r 'Errors') | $(V $r 'p50') | $(V $r 'p95') | $(V $r 'p99') | $(V $r 'max') | $oversell | $deadlocks | $timeouts |"
}

function NotImpl-B1B2Row { param($label) return "| $label | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | **Not Implemented** | $DASH | $DASH |" }
function NotImpl-B3Row   { param($label) return "| $label | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | $DASH | **Not Implemented** | $DASH | $DASH |" }

function Get-Row {
    param([string]$key, [string]$label, [string]$scenario, [string]$rowType)
    $stratResult = $results[$key]
    if (-not $stratResult -or
        $stratResult.Status -eq "not_implemented" -or
        $stratResult.Status -eq "branch_missing") {
        if ($rowType -eq "B3") { return NotImpl-B3Row $label } else { return NotImpl-B1B2Row $label }
    }
    $scenarioData = $stratResult[$scenario]
    if ($rowType -eq "B3") { return Format-B3Row $label $scenarioData }
    else                   { return Format-B1B2Row $label $scenarioData }
}

# Best strategy for recommendations
$bestB1 = ""; $bestB1p95 = 999999
$bestB3 = ""; $bestB3p95 = 999999
foreach ($s in $allStrategies) {
    $k = $s.Key; $l = $s.Label
    if ($results[$k] -and $results[$k].Status -eq "ok") {
        $b1d = $results[$k]["B1"]
        if ($b1d -and $b1d.p95 -ne $DASH -and $b1d.OversellResult -eq "No") {
            $v = [double]$b1d.p95
            if ($v -lt $bestB1p95) { $bestB1p95 = $v; $bestB1 = "$l (p95=${v}ms)" }
        }
        if (-not $SkipBurst) {
            $b3d = $results[$k]["B3"]
            if ($b3d -and $b3d.p95 -ne $DASH -and $b3d.OversellResult -eq "No") {
                $v = [double]$b3d.p95
                if ($v -lt $bestB3p95) { $bestB3p95 = $v; $bestB3 = "$l (p95=${v}ms)" }
            }
        }
    }
}
if (-not $bestB1) { $bestB1 = "_Not enough data_" }
if (-not $bestB3) { $bestB3 = "_Not enough data (run without -SkipBurst)_" }

function Get-PerfRow {
    param([string]$key, [string]$label, [string]$failureMode, [string]$bestFor)
    $s = $results[$key]
    if (-not $s -or $s.Status -ne "ok") {
        return "| $label | $DASH | $DASH | Not Implemented | $DASH |"
    }
    $b3  = $s["B3"]
    $b1  = $s["B1"]
    $ref = if ($b3 -and $b3.p95 -ne $DASH) { $b3 } elseif ($b1 -and $b1.p95 -ne $DASH) { $b1 } else { $null }
    if (-not $ref) { return "| $label | $DASH | $DASH | $failureMode | $bestFor |" }
    $tp  = if ($ref.Successes -ne $DASH) { "$($ref.Successes) seats" } else { $DASH }
    $lat = if ($ref.p95       -ne $DASH) { "$($ref.p95)ms" }          else { $DASH }
    return "| $label | $tp | $lat | $failureMode | $bestFor |"
}

function Get-CorrectnessRow {
    param([string]$key, [string]$label)
    $s = $results[$key]
    if (-not $s -or $s.Status -ne "ok") {
        return "| $label | $DASH | $DASH | $DASH |"
    }
    $anyOversell = $false
    foreach ($scenario in @("B1","B2","B3")) {
        $d = $s[$scenario]
        if ($d -and $d.OversellResult -and $d.OversellResult -ne "No") { $anyOversell = $true }
    }
    if ($anyOversell) {
        return "| $label | :warning: YES | :warning: Possible | :x: |"
    }
    return "| $label | :white_check_mark: No | :white_check_mark: No | :white_check_mark: |"
}

# Build B3 section string separately to avoid here-string nesting issues
if (-not $SkipBurst) {
    $b3Section = @"

## B3 - Burst (Flash Sale Simulation)

> Ramp 0 to 1000 VUs in 5s, sustained 30s, ramp down 5s. 100 seats.

| Strategy | Successes | Conflicts | 503s | Errors | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) | Oversell? | Deadlocks | Timeouts |
|----------|-----------|-----------|------|--------|----------|----------|----------|----------|-----------|-----------|----------|
$(Get-Row "naive"        "A: Naive"        "B3" "B3")
$(Get-Row "pessimistic"  "B: Pessimistic"  "B3" "B3")
$(Get-Row "occ"          "C: OCC"          "B3" "B3")
$(Get-Row "serializable" "D: SERIALIZABLE" "B3" "B3")
$(Get-Row "reservation"  "E: Reservation"  "B3" "B3")
$(Get-Row "queue"        "F: Queue"        "B3" "B3")

### Phan tich B3

**Ky vong:**
- **Pessimistic**: connection pool exhaustion -> timeout spike khi VUs > pool size (50)
- **OCC**: retry storm -> exponential backoff giam load nhung p99 tang
- **Queue**: back-pressure -> 503 khi queue day, nhung DB an toan
- **Naive**: oversell ngay tu burst dau tien

---
"@
} else {
    $b3Section = @"

## B3 - Burst (Flash Sale Simulation)

> Skipped (run without -SkipBurst to include)

---
"@
}

$k6Version   = k6 version 2>$null
if (-not $k6Version) { $k6Version = "unknown" }

$cpuName     = (Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue).Name
if (-not $cpuName) { $cpuName = "unknown" }
$ramGB       = [math]::Round((Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue).TotalPhysicalMemory / 1GB)
$machineInfo = "$env:OS, $cpuName, ${ramGB}GB RAM"

$genDate = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

$md = @"
# Benchmark Final - So sanh 6 Strategy (A-F)

> Auto-generated by run-all-benchmarks.ps1 on $genDate

---

## Moi truong benchmark

| Parameter | Value |
|-----------|-------|
| Machine | $machineInfo |
| PostgreSQL | 16 (Docker) |
| Redis | 7-alpine (Docker) |
| HikariCP pool | 50 connections |
| JVM | Java 17, Spring Boot 3.2.5 |
| k6 | $k6Version |

---

## B1 - Stock=1 (Extreme Contention)

> 200 VUs, 1 seat, moi VU gui 1 request

| Strategy | Successes | Conflicts | Errors | p50 (ms) | p95 (ms) | p99 (ms) | Oversell? | Deadlocks | Retries |
|----------|-----------|-----------|--------|----------|----------|----------|-----------|-----------|---------|
$(Get-Row "naive"        "A: Naive"        "B1" "B1")
$(Get-Row "pessimistic"  "B: Pessimistic"  "B1" "B1")
$(Get-Row "occ"          "C: OCC"          "B1" "B1")
$(Get-Row "serializable" "D: SERIALIZABLE" "B1" "B1")
$(Get-Row "reservation"  "E: Reservation"  "B1" "B1")
$(Get-Row "queue"        "F: Queue"        "B1" "B1")

### Phan tich B1

**Ky vong:**
- **Naive (A)**: oversell - nhieu hon 1 success -> chung minh race condition
- **Pessimistic (B)**: p95 cao nhat - blocking, sequential lock acquisition
- **OCC (C)**: retry storm - nhieu retries nhung fast fail -> p95 trung binh
- **SERIALIZABLE (D)**: tuong tu OCC - PostgreSQL retry serialization failures
- **Reservation (E)**: 2-step process -> latency cao hon OCC/SERIALIZABLE
- **Queue (F)**: sequential by design -> latency phu thuoc queue depth

---

## B2 - Hot-Seat (Realistic Contention)

> 500 VUs, 100 seats, 80% traffic -> A1-A10 (hot seats)

| Strategy | Successes | Conflicts | Errors | p50 (ms) | p95 (ms) | p99 (ms) | Oversell? | Deadlocks | Retries |
|----------|-----------|-----------|--------|----------|----------|----------|-----------|-----------|---------|
$(Get-Row "naive"        "A: Naive"        "B2" "B2")
$(Get-Row "pessimistic"  "B: Pessimistic"  "B2" "B2")
$(Get-Row "occ"          "C: OCC"          "B2" "B2")
$(Get-Row "serializable" "D: SERIALIZABLE" "B2" "B2")
$(Get-Row "reservation"  "E: Reservation"  "B2" "B2")
$(Get-Row "queue"        "F: Queue"        "B2" "B2")

### Phan tich B2

**Ky vong:**
- Hot seats (A1-A10) se co contention cao -> conflict rate cao hon cold seats
- OCC/SERIALIZABLE co the tot hon Pessimistic vi cold seats khong bi block
- Naive se oversell tren hot seats nhung cold seats co the OK

---
$b3Section
## Tong hop - Strategy Selection Guide

### Correctness

| Strategy | Oversell? | Double-book? | Consistent? |
|----------|-----------|-------------|-------------|
$(Get-CorrectnessRow "naive"        "A: Naive")
$(Get-CorrectnessRow "pessimistic"  "B: Pessimistic")
$(Get-CorrectnessRow "occ"          "C: OCC")
$(Get-CorrectnessRow "serializable" "D: SERIALIZABLE")
$(Get-CorrectnessRow "reservation"  "E: Reservation")
$(Get-CorrectnessRow "queue"        "F: Queue")

### Performance Summary

| Strategy | Throughput | p95 Latency | Failure Mode | Best For |
|----------|-----------|-------------|-------------|----------|
$(Get-PerfRow "naive"        "A: Naive"        "Oversell"                   "Never use in production")
$(Get-PerfRow "pessimistic"  "B: Pessimistic"  "Pool exhaustion, deadlock"  "Low contention, simple audit")
$(Get-PerfRow "occ"          "C: OCC"          "Retry storm"                "Medium contention, fast reads")
$(Get-PerfRow "serializable" "D: SERIALIZABLE" "Serialization retry"        "Strong consistency needs")
$(Get-PerfRow "reservation"  "E: Reservation"  "TTL expiry complexity"      "UX-first: hold your seat")
$(Get-PerfRow "queue"        "F: Queue"        "Back-pressure 503"          "Flash sale, strict fairness")

### Khuyen nghi

#### Boi canh 1: Single DB, High Contention (e.g., flash sale 1000+ concurrent)
- **Khuyen nghi**: $bestB3
- **Ly do**: Lowest p95 latency without oversell under burst load

#### Boi canh 2: Multi-instance, UX-oriented (e.g., e-commerce checkout)
- **Khuyen nghi**: $bestB1
- **Ly do**: Best latency under extreme contention with correctness guaranteed

---

## Cach chay benchmark day du

\`\`\`bash
# Automatic (recommended) -- from main branch:
.\run-all-benchmarks.ps1                    # Run all strategies
.\run-all-benchmarks.ps1 -Only occ          # Run single strategy
.\run-all-benchmarks.ps1 -SkipBurst         # Skip B3 (saves ~5 min per strategy)

# Manual -- for a single strategy:
git checkout impl/pessimistic-locking
make db-reset && make migrate && make seed
make app-run                    # Terminal 1
make bench:pessimistic          # Terminal 2
curl http://localhost:8080/events/1/stats
\`\`\`

> **Luu y:** Script tu dong reset DB giua moi benchmark run.
> Raw k6 output saved to: benchmark-raw-$timestamp/
"@

# Ensure docs directory exists
$docsDir = Split-Path $benchFinalPath -Parent
if (-not (Test-Path $docsDir)) { New-Item -ItemType Directory -Path $docsDir -Force | Out-Null }

$md | Out-File -FilePath $benchFinalPath -Encoding UTF8
Write-Ok "bench-final.md written to: $benchFinalPath"

# Return to original branch
Write-Step "Returning to branch: $originalBranch"
git checkout $originalBranch 2>&1 | Out-Null

# --- Final summary ---
Write-Host "`n================================================================" -ForegroundColor Cyan
Write-Host "  BENCHMARK COMPLETE" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  Results: docs/bench-final.md (auto-filled)" -ForegroundColor White
Write-Host "  Raw output: $rawResultsDir" -ForegroundColor White
Write-Host ""
foreach ($s in $allStrategies) {
    $k = $s.Key; $l = $s.Label
    $r = $results[$k]
    $status = if ($r) { $r.Status } else { "skipped" }
    if (-not $r -or $r.Status -ne "ok") {
        Write-Info "  $l -- $status"
    } else {
        $b1s = if ($r["B1"]) { "B1:OK" } else { "B1:-" }
        $b2s = if ($r["B2"]) { "B2:OK" } else { "B2:-" }
        $b3s = if ($r["B3"]) { "B3:OK" } elseif ($SkipBurst) { "B3:skip" } else { "B3:-" }
        Write-Ok "  $l -- $b1s $b2s $b3s"
    }
}
Write-Host "`nDone!" -ForegroundColor Green