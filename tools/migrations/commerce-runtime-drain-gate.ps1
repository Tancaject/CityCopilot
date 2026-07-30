[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $root
try {
    function Query([string]$service, [string]$database, [string]$sql) {
        $output = $sql | docker compose exec -T $service sh -c "mysql -N -B -u`"`$MYSQL_USER`" -p`"`$MYSQL_PASSWORD`" $database"
        if ($LASTEXITCODE -ne 0) { throw "Query failed for $service" }
        return @($output)
    }

    & (Join-Path $PSScriptRoot "verify-commerce-integrity.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Commerce integrity gate failed" }

    $commerce = Query "commerce-mysql" "citycopilot_commerce" @"
SELECT COUNT(*) FROM commerce_outbox_event WHERE status IN ('READY','CLAIMED','FAILED');
SELECT COUNT(*) FROM commerce_reservation WHERE status IN ('RESERVED','RELEASE_REQUIRED');
SELECT COUNT(*) FROM commerce_dead_letter WHERE replayed_at IS NULL;
SELECT COUNT(*) FROM commerce_order_recovery
WHERE phase IN ('RECEIVED','COMPENSATION_REQUIRED','RESTORE_PENDING','REPAIR_REQUIRED');
"@
    $commerceRedisState = docker compose exec -T redis sh -c @'
export REDISCLI_AUTH="$REDIS_PASSWORD"
pending=$(redis-cli --raw XPENDING commerce:stream:orders commerce-order-workers 2>&1 | head -n 1)
case "$pending" in
  *NOGROUP*|ERR*) exit 2 ;;
esac
lag=$(redis-cli --raw XINFO GROUPS commerce:stream:orders | awk '
  $0 == "name" { getline group }
  $0 == "lag" {
    getline value
    if (group == "commerce-order-workers") {
      print value
      found=1
    }
  }
  END { if (!found) exit 3 }
')
inflight=0
for key in $(redis-cli --raw --scan --pattern 'commerce:seckill:inflight:*'); do
  count=$(redis-cli --raw SCARD "$key")
  inflight=$((inflight + count))
done
printf '%s\n%s\n%s\n' "$pending" "$lag" "$inflight"
'@
    if ($LASTEXITCODE -ne 0) { throw "Unable to read Commerce Redis Stream pending/lag" }

    $gate = [ordered]@{
        unpublishedOutbox = [int64]$commerce[0]
        inFlightReservations = [int64]$commerce[1]
        unreplayedDeadLetters = [int64]$commerce[2]
        commerceRedisPending = [int64]$commerceRedisState[0]
        commerceRedisLag = [int64]$commerceRedisState[1]
        commerceRedisInFlight = [int64]$commerceRedisState[2]
        unresolvedRecovery = [int64]$commerce[3]
    }
    $gate["safe"] = (
        $gate.unpublishedOutbox -eq 0 `
        -and $gate.inFlightReservations -eq 0 `
        -and $gate.unreplayedDeadLetters -eq 0 `
        -and $gate.commerceRedisPending -eq 0 `
        -and $gate.commerceRedisLag -eq 0 `
        -and $gate.commerceRedisInFlight -eq 0 `
        -and $gate.unresolvedRecovery -eq 0
    )
    $gate | ConvertTo-Json -Depth 4
    if (-not $gate.safe) { throw "Commerce runtime is not drained" }
} finally {
    Pop-Location
}
