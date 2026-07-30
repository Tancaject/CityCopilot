[CmdletBinding()]
param(
    [switch]$InjectStockDrift,
    [long]$VoucherId = 100
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $root
try {
    Write-Host "1. Checking the full topology..." -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot "citycopilot.ps1") status
    if ($LASTEXITCODE -ne 0) { throw "Compose topology is not healthy" }

    Write-Host "2. Checking the current Commerce ownership gates..." -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot "migrations\commerce-runtime-drain-gate.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Commerce ownership gate failed" }

    Write-Host "3. Evidence already produced by the scripted load runner:" -ForegroundColor Cyan
    Write-Host "   docs/evidence/2026-07-26-e6-commerce-load.json"

    if ($InjectStockDrift) {
        Write-Host "4. Injecting a bounded Redis-only drift for voucher $VoucherId..." -ForegroundColor Yellow
        $databaseStock = "SELECT stock FROM commerce_seckill_voucher WHERE voucher_id=$VoucherId;" |
            docker compose exec -T commerce-mysql sh -c "mysql -N -B -u`"`$MYSQL_USER`" -p`"`$MYSQL_PASSWORD`" `"`$MYSQL_DATABASE`""
        if ($LASTEXITCODE -ne 0 -or -not $databaseStock) { throw "Unable to read Commerce stock" }
        $drift = [int64]$databaseStock + 1
        docker compose exec -T redis sh -c "export REDISCLI_AUTH=`"`$REDIS_PASSWORD`"; redis-cli SET commerce:seckill:stock:$VoucherId $drift; redis-cli DEL commerce:metrics:seckill:discrepancy-streak:$VoucherId commerce:event:stock-discrepancy:cooldown:$VoucherId"
        if ($LASTEXITCODE -ne 0) { throw "Unable to inject Redis drift" }
        Write-Host "   Wait for the Agent Case, then open http://127.0.0.1:8080/admin/ops-cases" -ForegroundColor Green
        Write-Host "   Review evidence -> create/inspect plan -> approve -> observe independent verification."
    } else {
        Write-Host "4. Read-only demo complete. Add -InjectStockDrift to exercise the HITL Ops Agent loop." -ForegroundColor Green
    }

    Write-Host "5. Trace UI: http://127.0.0.1:8080/admin/agent-traces" -ForegroundColor Cyan
} finally {
    Pop-Location
}
