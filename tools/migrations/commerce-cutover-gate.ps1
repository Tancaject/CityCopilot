[CmdletBinding()]
param(
    [ValidateSet("Preflight", "Activate", "Rollback")]
    [string]$Action = "Preflight",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $root
try {
    function Query([string]$service, [string]$database, [string]$sql) {
        $output = $sql | docker compose exec -T $service sh -c "mysql -N -B -u`"`$MYSQL_USER`" -p`"`$MYSQL_PASSWORD`" $database"
        if ($LASTEXITCODE -ne 0) { throw "Query failed for $service" }
        return @($output)
    }

    & (Join-Path $PSScriptRoot "verify-commerce-cutover.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Migration verification gate failed" }

    $commerce = Query "commerce-mysql" "citycopilot_commerce" @"
SELECT COUNT(*) FROM commerce_outbox_event WHERE status IN ('READY','CLAIMED','FAILED');
SELECT COUNT(*) FROM commerce_reservation WHERE status IN ('RESERVED','RELEASE_REQUIRED');
SELECT COUNT(*) FROM commerce_dead_letter WHERE replayed_at IS NULL;
"@
    $redisState = docker compose exec -T redis sh -c 'export REDISCLI_AUTH="$REDIS_PASSWORD"; redis-cli XPENDING stream.coupon-orders g1 | head -n 1'
    if ($LASTEXITCODE -ne 0) { throw "Unable to read legacy Redis Stream pending count" }

    $gate = [ordered]@{
        action = $Action
        unpublishedOutbox = [int64]$commerce[0]
        inFlightReservations = [int64]$commerce[1]
        unreplayedDeadLetters = [int64]$commerce[2]
        legacyRedisPending = [int64]$redisState
    }
    $gate["safe"] = ($gate.unpublishedOutbox -eq 0 -and $gate.inFlightReservations -eq 0 -and $gate.unreplayedDeadLetters -eq 0 -and $gate.legacyRedisPending -eq 0)
    $gate | ConvertTo-Json -Depth 4
    if (-not $gate.safe) { throw "Cutover ownership gate is not drained" }

    if ($Action -eq "Activate") {
        Write-Host "Preflight passed. Activate by setting CITYCOPILOT_COMMERCE_ENABLED=true and recreating Core." -ForegroundColor Green
    } elseif ($Action -eq "Rollback") {
        if (-not $Force) {
            throw "Rollback is intentionally fenced. Re-run with -Force only inside a maintenance window after reverse synchronization."
        }
        Write-Host "Rollback gate passed. No data was deleted; preserve Commerce as read-only evidence and reverse-sync post-cutover writes before changing the writer." -ForegroundColor Yellow
    }
} finally {
    Pop-Location
}
