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

    $commerce = Query "commerce-mysql" "citycopilot_commerce" @"
SELECT COUNT(DISTINCT table_name)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'commerce_voucher',
    'commerce_seckill_voucher',
    'shop_summary_projection',
    'commerce_order',
    'commerce_reservation',
    'commerce_outbox_event',
    'commerce_inbox_event',
    'commerce_dead_letter',
    'commerce_order_recovery',
    'commerce_admission_ledger',
    'commerce_id_segment'
  );
SELECT COUNT(*) FROM commerce_seckill_voucher WHERE stock < 0;
SELECT COUNT(*) FROM commerce_id_segment
WHERE namespace='order' AND next_value>=0;
SELECT COUNT(*) FROM commerce_id_segment
WHERE namespace='voucher' AND next_value>=0;
"@
    $result = [ordered]@{
        requiredTables = [int64]$commerce[0]
        negativeStockRows = [int64]$commerce[1]
        orderIdSegmentSeeded = [int64]$commerce[2] -eq 1
        voucherIdSegmentSeeded = [int64]$commerce[3] -eq 1
    }
    $result["integrityGate"] = (
        $result.requiredTables -eq 11 -and
        $result.negativeStockRows -eq 0 -and
        $result.orderIdSegmentSeeded -and
        $result.voucherIdSegmentSeeded
    )
    $result | ConvertTo-Json -Depth 4
    if (-not $result.integrityGate) { exit 2 }
} finally {
    Pop-Location
}
