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

    $legacy = Query "mysql" "citycopilot" @"
SELECT COUNT(*) FROM coupon;
SELECT COUNT(*) FROM seckill_coupon;
SELECT COUNT(*) FROM coupon_order;
SELECT COALESCE(SUM(stock),0) FROM seckill_coupon;
SELECT COALESCE(MIN(id),0),COALESCE(MAX(id),0) FROM coupon;
SELECT COALESCE(MIN(id),0),COALESCE(MAX(id),0) FROM coupon_order;
SELECT COALESCE(SUM(pay_value),0),COALESCE(SUM(actual_value),0) FROM coupon;
SELECT COALESCE(SUM(CRC32(CONCAT_WS('|',id,shop_id,title,COALESCE(sub_title,''),COALESCE(rules,''),pay_value,actual_value,type,status))),0) FROM coupon;
SELECT COALESCE(SUM(CRC32(CONCAT_WS('|',id,user_id,voucher_id,status,version))),0) FROM coupon_order;
"@
    $commerce = Query "commerce-mysql" "citycopilot_commerce" @"
SELECT COUNT(*) FROM commerce_voucher WHERE id IN (1,100);
SELECT COUNT(*) FROM commerce_seckill_voucher WHERE voucher_id IN (100);
SELECT COUNT(*) FROM commerce_order WHERE id >= 70000000000000000;
SELECT COALESCE(SUM(stock),0) FROM commerce_seckill_voucher;
SELECT COUNT(*) FROM commerce_order;
SELECT COUNT(*) FROM commerce_reservation;
SELECT COUNT(*) FROM commerce_outbox_event WHERE status <> 'PUBLISHED';
SELECT COALESCE(MIN(id),0),COALESCE(MAX(id),0) FROM commerce_voucher WHERE id IN (1,100);
SELECT COALESCE(MIN(id),0),COALESCE(MAX(id),0) FROM commerce_order WHERE id >= 70000000000000000;
SELECT COALESCE(SUM(pay_value),0),COALESCE(SUM(actual_value),0) FROM commerce_voucher WHERE id IN (1,100);
SELECT COALESCE(SUM(CRC32(CONCAT_WS('|',id,shop_id,title,COALESCE(sub_title,''),COALESCE(rules,''),pay_value,actual_value,type,status))),0) FROM commerce_voucher WHERE id IN (1,100);
SELECT COALESCE(SUM(CRC32(CONCAT_WS('|',id,user_id,voucher_id,status,version))),0) FROM commerce_order WHERE id >= 70000000000000000;
"@
    $result = [ordered]@{
        legacy = [ordered]@{
            vouchers = [int64]$legacy[0]
            seckillVouchers = [int64]$legacy[1]
            orders = [int64]$legacy[2]
            stock = [int64]$legacy[3]
            voucherPrimaryKeyRange = @([int64]($legacy[4] -split "`t")[0], [int64]($legacy[4] -split "`t")[1])
            orderPrimaryKeyRange = @([int64]($legacy[5] -split "`t")[0], [int64]($legacy[5] -split "`t")[1])
            voucherPayValueSum = [int64]($legacy[6] -split "`t")[0]
            voucherActualValueSum = [int64]($legacy[6] -split "`t")[1]
            voucherSampleHash = [uint64]$legacy[7]
            orderSampleHash = [uint64]$legacy[8]
        }
        commerce = [ordered]@{
            migratedLegacyVouchers = [int64]$commerce[0]
            migratedLegacySeckillVouchers = [int64]$commerce[1]
            migratedLegacyOrders = [int64]$commerce[2]
            stock = [int64]$commerce[3]
            totalOrders = [int64]$commerce[4]
            reservations = [int64]$commerce[5]
            unpublishedOutbox = [int64]$commerce[6]
            voucherPrimaryKeyRange = @([int64]($commerce[7] -split "`t")[0], [int64]($commerce[7] -split "`t")[1])
            orderPrimaryKeyRange = @([int64]($commerce[8] -split "`t")[0], [int64]($commerce[8] -split "`t")[1])
            voucherPayValueSum = [int64]($commerce[9] -split "`t")[0]
            voucherActualValueSum = [int64]($commerce[9] -split "`t")[1]
            voucherSampleHash = [uint64]$commerce[10]
            orderSampleHash = [uint64]$commerce[11]
        }
    }
    $result["rowCountGate"] = (
        $result.legacy.vouchers -eq $result.commerce.migratedLegacyVouchers -and
        $result.legacy.seckillVouchers -eq $result.commerce.migratedLegacySeckillVouchers -and
        $result.legacy.orders -eq $result.commerce.migratedLegacyOrders -and
        ($result.legacy.voucherPrimaryKeyRange -join ",") -eq ($result.commerce.voucherPrimaryKeyRange -join ",") -and
        ($result.legacy.orderPrimaryKeyRange -join ",") -eq ($result.commerce.orderPrimaryKeyRange -join ",") -and
        $result.legacy.voucherPayValueSum -eq $result.commerce.voucherPayValueSum -and
        $result.legacy.voucherActualValueSum -eq $result.commerce.voucherActualValueSum -and
        $result.legacy.voucherSampleHash -eq $result.commerce.voucherSampleHash -and
        $result.legacy.orderSampleHash -eq $result.commerce.orderSampleHash
    )
    $result | ConvertTo-Json -Depth 5
    if (-not $result.rowCountGate) { exit 2 }
} finally {
    Pop-Location
}
