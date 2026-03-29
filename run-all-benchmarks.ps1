# PowerShell script to run benchmarks on all implemented strategy branches
# This script will:
# 1. Checkout each impl/* branch
# 2. Reset DB, migrate, seed
# 3. Start app, run benchmark, collect results
# 4. Save results to a summary file

$strategies = @(
    @{Name="naive"; Branch="impl/naive"},
    @{Name="pessimistic"; Branch="impl/pessimistic-locking"},
    @{Name="occ"; Branch="impl/occ"}
)

$resultsFile = "benchmark-results-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt"
$summaryFile = "benchmark-summary-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"

Write-Host "=== Automated Benchmark Runner ===" -ForegroundColor Cyan
Write-Host "Testing strategies: naive, pessimistic, occ" -ForegroundColor Yellow
Write-Host "Results will be saved to: $resultsFile" -ForegroundColor Yellow
Write-Host ""

$allResults = @{}

foreach ($strategy in $strategies) {
    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "Testing: $($strategy.Name) ($($strategy.Branch))" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
    # Checkout branch
    Write-Host "`n[1/6] Checking out branch: $($strategy.Branch)..." -ForegroundColor Yellow
    git checkout $strategy.Branch
    if ($LASTEXITCODE -ne 0) {
        Write-Host "   ERROR: Failed to checkout branch" -ForegroundColor Red
        continue
    }
    
    # Reset DB
    Write-Host "[2/6] Resetting database..." -ForegroundColor Yellow
    make db-reset | Out-Null
    Start-Sleep -Seconds 3
    
    # Migrate
    Write-Host "[3/6] Running migrations..." -ForegroundColor Yellow
    make migrate | Out-Null
    
    # Seed
    Write-Host "[4/6] Seeding data..." -ForegroundColor Yellow
    make seed | Out-Null
    
    # Start app in background
    Write-Host "[5/6] Starting application..." -ForegroundColor Yellow
    $appJob = Start-Job -ScriptBlock {
        param($workDir)
        Set-Location $workDir
        make app-run
    } -ArgumentList (Get-Location).Path
    
    # Wait for app to be ready
    Write-Host "   Waiting for application to start..." -ForegroundColor Gray
    $maxWait = 90
    $waited = 0
    $ready = $false
    
    while ($waited -lt $maxWait -and -not $ready) {
        Start-Sleep -Seconds 3
        $waited += 3
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
            if ($response.StatusCode -eq 200) {
                $ready = $true
                Write-Host "   Application ready! (took $waited seconds)" -ForegroundColor Green
            }
        } catch {
            Write-Host "   Still waiting... ($waited/$maxWait seconds)" -ForegroundColor Gray
        }
    }
    
    if (-not $ready) {
        Write-Host "   ERROR: Application failed to start within $maxWait seconds" -ForegroundColor Red
        Stop-Job -Job $appJob
        Remove-Job -Job $appJob
        continue
    }
    
    # Run benchmark
    Write-Host "[6/6] Running benchmark..." -ForegroundColor Yellow
    $benchTarget = "bench:$($strategy.Name)"
    $benchOutput = & make $benchTarget 2>&1 | Out-String
    
    # Collect results
    Write-Host "   Collecting results..." -ForegroundColor Gray
    try {
        $stats = Invoke-RestMethod -Uri "http://localhost:8080/events/1/stats" -UseBasicParsing
        
        Write-Host "`nResults for $($strategy.Name):" -ForegroundColor Green
        Write-Host "  Strategy: $($stats.strategy)" -ForegroundColor White
        Write-Host "  Total Seats: $($stats.totalSeats)" -ForegroundColor White
        Write-Host "  Available: $($stats.availableSeats)" -ForegroundColor White
        Write-Host "  Sold: $($stats.soldCount)" -ForegroundColor White
        Write-Host "  Consistent: $($stats.consistent)" -ForegroundColor White
        if ($stats.metrics) {
            Write-Host "  Conflicts: $($stats.metrics.conflicts)" -ForegroundColor White
            Write-Host "  Retries: $($stats.metrics.retries)" -ForegroundColor White
            Write-Host "  Oversells: $($stats.metrics.oversells)" -ForegroundColor White
        }
        
        # Save to results
        $allResults[$strategy.Name] = @{
            branch = $strategy.Branch
            stats = $stats
            benchmarkOutput = $benchOutput
            timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
        }
        
        # Append to results file
        Add-Content -Path $resultsFile -Value "`n========================================`n"
        Add-Content -Path $resultsFile -Value "Strategy: $($strategy.Name) ($($strategy.Branch))`n"
        Add-Content -Path $resultsFile -Value "Timestamp: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`n"
        Add-Content -Path $resultsFile -Value "Stats:`n"
        Add-Content -Path $resultsFile -Value ($stats | ConvertTo-Json -Depth 3)
        Add-Content -Path $resultsFile -Value "`nBenchmark Output:`n"
        Add-Content -Path $resultsFile -Value $benchOutput
        
    } catch {
        Write-Host "   ERROR: Failed to collect results: $_" -ForegroundColor Red
    }
    
    # Stop app
    Write-Host "   Stopping application..." -ForegroundColor Gray
    Stop-Job -Job $appJob
    Remove-Job -Job $appJob
    Start-Sleep -Seconds 2
    
    Write-Host "   Complete!" -ForegroundColor Green
}

# Save summary JSON
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Saving summary to: $summaryFile" -ForegroundColor Yellow
$allResults | ConvertTo-Json -Depth 5 | Out-File -FilePath $summaryFile -Encoding UTF8

Write-Host "`n=== All Benchmarks Complete ===" -ForegroundColor Cyan
Write-Host "Results saved to:" -ForegroundColor Yellow
Write-Host "  - $resultsFile (detailed)" -ForegroundColor White
Write-Host "  - $summaryFile (JSON summary)" -ForegroundColor White
Write-Host "`nComparison:" -ForegroundColor Yellow

foreach ($strategy in $strategies) {
    if ($allResults.ContainsKey($strategy.Name)) {
        $result = $allResults[$strategy.Name]
        Write-Host "`n$($strategy.Name):" -ForegroundColor Cyan
        Write-Host "  Sold: $($result.stats.soldCount)/$($result.stats.totalSeats)" -ForegroundColor White
        Write-Host "  Consistent: $($result.stats.consistent)" -ForegroundColor White
        if ($result.stats.metrics) {
            Write-Host "  Conflicts: $($result.stats.metrics.conflicts)" -ForegroundColor White
            Write-Host "  Retries: $($result.stats.metrics.retries)" -ForegroundColor White
            Write-Host "  Oversells: $($result.stats.metrics.oversells)" -ForegroundColor White
        }
    }
}

Write-Host "`nDone!" -ForegroundColor Green
