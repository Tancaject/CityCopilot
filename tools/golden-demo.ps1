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

    Write-Host "2. Checking Commerce schema integrity..." -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot "migrations\verify-commerce-integrity.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Commerce integrity gate failed" }

    Write-Host "3. Evidence already produced by the scripted load runner:" -ForegroundColor Cyan
    Write-Host "   docs/evidence/2026-07-26-e6-commerce-load.json"

    if ($InjectStockDrift) {
        Write-Host "4. Injecting a bounded Redis-only drift for voucher $VoucherId..." -ForegroundColor Yellow
        $databaseStock = "SELECT stock FROM commerce_seckill_voucher WHERE voucher_id=$VoucherId;" |
            docker compose exec -T commerce-mysql sh -c "mysql -N -B -u`"`$MYSQL_USER`" -p`"`$MYSQL_PASSWORD`" `"`$MYSQL_DATABASE`""
        if ($LASTEXITCODE -ne 0 -or -not $databaseStock) { throw "Unable to read Commerce stock" }
        $drift = [int64]$databaseStock + 1
        docker compose exec -T redis sh -c "export REDISCLI_AUTH=`"`$REDIS_PASSWORD`"; redis-cli SET commerce:{commerce:v1:1:0}:seckill:stock:$VoucherId $drift; redis-cli DEL commerce:metrics:seckill:discrepancy-streak:$VoucherId commerce:event:stock-discrepancy:cooldown:$VoucherId"
        if ($LASTEXITCODE -ne 0) { throw "Unable to inject Redis drift" }
        Write-Host "   Reconciliation runs every 5s; once it sees the drift it fences new admissions for voucher $VoucherId." -ForegroundColor Green
        Write-Host "   As an admin (Authorization: <admin token>) against Core http://127.0.0.1:8081:"
        Write-Host "     GET  /admin/seckill/consistency/$VoucherId          -> read evidenceHash"
        Write-Host "     POST /admin/seckill/consistency/$VoucherId/repair   body {`"expectedEvidenceHash`":`"<hash>`"}"
        Write-Host "     GET  /admin/seckill/consistency/$VoucherId/verify   -> consistent=true, fence released"
    } else {
        Write-Host "4. Read-only demo complete. Add -InjectStockDrift to exercise reconciliation fence and admin repair." -ForegroundColor Green
    }
} finally {
    Pop-Location
}
