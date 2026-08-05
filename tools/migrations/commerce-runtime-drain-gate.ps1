<#
.SYNOPSIS
Local Docker Compose rehearsal for the Commerce routing protocol.

.NOTES
This script has no distributed routing-operator lease and does not bind writes
to a Redis Cluster master connection with WAIT. Never use it against production
or run it concurrently with the Java commerce-routing-operator process.
#>
[CmdletBinding()]
param(
    [ValidateSet("Status", "Initialize", "BeginDrain", "VerifyDrain", "Activate")]
    [string]$Action = "VerifyDrain",

    [ValidatePattern("^[A-Za-z0-9_-]{1,24}$")]
    [string]$Generation = "v1",

    [ValidateRange(1, 1024)]
    [int]$PhysicalShardCount = 1,

    [string]$TargetGeneration = "",

    [ValidateRange(0, 1024)]
    [int]$TargetPhysicalShardCount = 0,

    [ValidateNotNullOrEmpty()]
    [string]$RedisService = "redis",

    [switch]$SkipIntegrityGate
)

$ErrorActionPreference = "Stop"
$ControlKey = "commerce:{commerce-runtime}:routing"
$ConsumerGroup = "commerce-order-workers"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function New-Revision {
    return [guid]::NewGuid().ToString("N")
}

function Get-ControlValue([string]$state, [string]$generation, [int]$shardCount, [string]$revision) {
    return "v1|$state|$generation|$shardCount|$revision"
}

function Get-GateValue([string]$state, [string]$generation, [int]$shardCount) {
    return "v1|$state|$generation|$shardCount"
}

function Get-ShardPrefix([string]$generation, [int]$shardCount, [int]$shard) {
    return "commerce:{commerce:$generation`:$shardCount`:$shard}:"
}

function Invoke-Redis {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowError
    )
    $dockerArguments = @(
        "compose", "exec", "-T", $RedisService,
        "sh", "-c",
        'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"',
        "redis-cli"
    ) + $Arguments
    $output = @(& docker @dockerArguments 2>&1 | ForEach-Object { "$_" })
    $errorReply = $output | Where-Object { $_ -match "^(ERR|NOGROUP|NOAUTH|NOPERM|WRONGTYPE)" } | Select-Object -First 1
    if ($LASTEXITCODE -ne 0 -or ($errorReply -and -not $AllowError)) {
        $detail = ($output -join " ").Trim()
        throw "Redis command failed: $detail"
    }
    return $output
}

function Read-RedisScalar([string[]]$arguments) {
    $output = Invoke-Redis -Arguments $arguments
    return ($output -join "`n").Trim()
}

function Get-CurrentControl {
    $raw = Read-RedisScalar @("GET", $ControlKey)
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    $fields = @($raw -split '\|')
    if ($fields.Count -ne 5 -or $fields[0] -ne "v1") {
        throw "Commerce routing control record is malformed: $raw"
    }
    $count = 0
    if (-not [int]::TryParse($fields[3], [ref]$count) -or $count -lt 1 -or $count -gt 1024) {
        throw "Commerce routing control shard count is invalid: $raw"
    }
    if ($fields[1] -notin @("ACTIVE", "DRAINING") -or [string]::IsNullOrWhiteSpace($fields[2]) -or [string]::IsNullOrWhiteSpace($fields[4])) {
        throw "Commerce routing control record is invalid: $raw"
    }
    return [pscustomobject]@{
        raw = $raw
        state = $fields[1]
        generation = $fields[2]
        physicalShardCount = $count
        revision = $fields[4]
    }
}

function Assert-Fingerprint($control, [string]$generation, [int]$shardCount) {
    if ($null -eq $control) { throw "Commerce routing control is missing" }
    if ($control.generation -ne $generation -or $control.physicalShardCount -ne $shardCount) {
        throw "Routing fingerprint mismatch: control=$($control.generation)/$($control.physicalShardCount), expected=$generation/$shardCount"
    }
}

function Compare-And-SetControl([string]$expected, [string]$next) {
    $lua = "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end redis.call('SET',KEYS[1],ARGV[2]) return 1"
    $changed = Read-RedisScalar @("EVAL", $lua, "1", $ControlKey, $expected, $next)
    if ($changed -ne "1") {
        $current = Get-CurrentControl
        $actual = if ($null -eq $current) { "missing" } else { $current.raw }
        throw "Commerce routing control changed concurrently; current=$actual"
    }
}

function Get-GateKey([string]$generation, [int]$shardCount, [int]$shard) {
    return "$(Get-ShardPrefix $generation $shardCount $shard)runtime:routing-gate"
}

function Set-GatesForInitialization([string]$generation, [int]$shardCount) {
    $active = Get-GateValue "ACTIVE" $generation $shardCount
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        $key = Get-GateKey $generation $shardCount $shard
        $current = Read-RedisScalar @("GET", $key)
        if ([string]::IsNullOrWhiteSpace($current)) {
            [void](Invoke-Redis -Arguments @("SET", $key, $active, "NX"))
            $current = Read-RedisScalar @("GET", $key)
        }
        if ($current -ne $active) {
            throw "Shard $shard routing gate is not safely initializable: $current"
        }
    }
}

function Set-SourceGatesDraining([string]$generation, [int]$shardCount) {
    $active = Get-GateValue "ACTIVE" $generation $shardCount
    $draining = Get-GateValue "DRAINING" $generation $shardCount
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        $key = Get-GateKey $generation $shardCount $shard
        $current = Read-RedisScalar @("GET", $key)
        if ($current -notin @($active, $draining)) {
            throw "Shard $shard routing gate is missing or invalid: $current"
        }
        [void](Invoke-Redis -Arguments @("SET", $key, $draining))
    }
}

function Assert-Gates([string]$state, [string]$generation, [int]$shardCount) {
    $expected = Get-GateValue $state $generation $shardCount
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        $key = Get-GateKey $generation $shardCount $shard
        $current = Read-RedisScalar @("GET", $key)
        if ($current -ne $expected) {
            throw "Shard $shard routing gate mismatch: actual=$current expected=$expected"
        }
    }
}

function Prepare-TargetGates([string]$generation, [int]$shardCount) {
    $draining = Get-GateValue "DRAINING" $generation $shardCount
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        $key = Get-GateKey $generation $shardCount $shard
        $current = Read-RedisScalar @("GET", $key)
        if ([string]::IsNullOrWhiteSpace($current)) {
            [void](Invoke-Redis -Arguments @("SET", $key, $draining, "NX"))
            $current = Read-RedisScalar @("GET", $key)
        }
        if ($current -ne $draining) {
            throw "Target shard $shard routing gate was already active or invalid: $current"
        }
    }
}

function Activate-TargetGates([string]$generation, [int]$shardCount) {
    $draining = Get-GateValue "DRAINING" $generation $shardCount
    $active = Get-GateValue "ACTIVE" $generation $shardCount
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        $key = Get-GateKey $generation $shardCount $shard
        $current = Read-RedisScalar @("GET", $key)
        if ($current -notin @($draining, $active)) {
            throw "Target shard $shard routing gate cannot be activated: $current"
        }
        [void](Invoke-Redis -Arguments @("SET", $key, $active))
    }
}

function Test-AnyGateDraining([string]$generation, [int]$shardCount) {
    $draining = Get-GateValue "DRAINING" $generation $shardCount
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        if ((Read-RedisScalar @("GET", (Get-GateKey $generation $shardCount $shard))) -eq $draining) {
            return $true
        }
    }
    return $false
}

function Test-AllGatesActive([string]$generation, [int]$shardCount) {
    $active = Get-GateValue "ACTIVE" $generation $shardCount
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        if ((Read-RedisScalar @("GET", (Get-GateKey $generation $shardCount $shard))) -ne $active) {
            return $false
        }
    }
    return $true
}

function Query([string]$service, [string]$database, [string]$sql) {
    $output = $sql | docker compose exec -T $service sh -c "mysql -N -B -u`"`$MYSQL_USER`" -p`"`$MYSQL_PASSWORD`" $database"
    if ($LASTEXITCODE -ne 0) { throw "Query failed for $service" }
    return @($output)
}

function Get-StreamState([string]$stream) {
    $exists = [int64](Read-RedisScalar @("EXISTS", $stream))
    if ($exists -eq 0) {
        return [pscustomobject]@{ pending = 0L; lag = 0L; exists = $false }
    }
    $length = [int64](Read-RedisScalar @("XLEN", $stream))
    $pendingOutput = @(Invoke-Redis -Arguments @("XPENDING", $stream, $ConsumerGroup) -AllowError)
    if ($pendingOutput | Where-Object { $_ -match "^(ERR|NOGROUP)" }) {
        if ($length -eq 0) {
            return [pscustomobject]@{ pending = 0L; lag = 0L; exists = $true }
        }
        throw "Stream $stream contains $length records but consumer group $ConsumerGroup is missing"
    }
    $pending = [int64]$pendingOutput[0]
    $groups = @(Invoke-Redis -Arguments @("XINFO", "GROUPS", $stream))
    $selected = $false
    $lastDelivered = $null
    $lagText = $null
    for ($index = 0; $index + 1 -lt $groups.Count; $index += 2) {
        $field = $groups[$index]
        $value = $groups[$index + 1]
        if ($field -eq "name") {
            $selected = $value -eq $ConsumerGroup
        } elseif ($selected -and $field -eq "last-delivered-id") {
            $lastDelivered = $value
        } elseif ($selected -and $field -eq "lag") {
            $lagText = $value
        }
    }
    if ($null -eq $lastDelivered) {
        if ($length -eq 0) {
            return [pscustomobject]@{ pending = 0L; lag = 0L; exists = $true }
        }
        throw "Stream $stream does not expose consumer group $ConsumerGroup"
    }
    $lag = 0L
    if (-not [int64]::TryParse($lagText, [ref]$lag)) {
        $tail = @(Invoke-Redis -Arguments @("XREVRANGE", $stream, "+", "-", "COUNT", "1"))
        $tailId = if ($tail.Count -eq 0) { $null } else { $tail[0] }
        $lag = if ($null -eq $tailId -or $lastDelivered -eq $tailId) { 0L } else { -1L }
    }
    return [pscustomobject]@{ pending = $pending; lag = $lag; exists = $true }
}

function Get-RedisDrainState([string]$generation, [int]$shardCount) {
    $pending = 0L
    $lag = 0L
    $inflight = 0L
    $metadataUpdateFences = 0L
    $perShard = @()
    $inflightByShard = @{}
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        $inflightByShard[$shard] = 0L
    }
    $voucherIds = @(Query "commerce-mysql" "citycopilot_commerce" "SELECT voucher_id FROM commerce_seckill_voucher ORDER BY voucher_id;")
    foreach ($voucherIdText in $voucherIds) {
        if ([string]::IsNullOrWhiteSpace("$voucherIdText")) { continue }
        $voucherId = [int64]$voucherIdText
        $voucherShard = Get-VoucherShard $voucherId $shardCount
        $voucherPrefix = Get-ShardPrefix $generation $shardCount $voucherShard
        $inflightKey = "${voucherPrefix}seckill:inflight:$voucherId"
        $inflightByShard[$voucherShard] += [int64](Read-RedisScalar @("SCARD", $inflightKey))
        $metadataUpdateFences += [int64](Read-RedisScalar @(
            "EXISTS", "${voucherPrefix}seckill:meta-update-fence:$voucherId"
        ))
    }
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        $prefix = Get-ShardPrefix $generation $shardCount $shard
        $stream = "${prefix}stream:orders"
        $streamState = Get-StreamState $stream
        $shardInflight = [int64]$inflightByShard[$shard]
        $pending += $streamState.pending
        $lag += $streamState.lag
        $inflight += $shardInflight
        $perShard += [ordered]@{
            shard = $shard
            stream = $stream
            pending = $streamState.pending
            lag = $streamState.lag
            inFlight = $shardInflight
        }
    }
    return [pscustomobject]@{
        pending = $pending
        lag = $lag
        inFlight = $inflight
        metadataUpdateFences = $metadataUpdateFences
        shards = $perShard
    }
}

function Get-RuntimeMutationCount {
    $key = "commerce:{commerce-runtime}:mutation-leases"
    $lua = "local t=redis.call('TIME'); local n=t[1]*1000+math.floor(t[2]/1000); redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',n); return redis.call('ZCARD',KEYS[1])"
    return [int64](Read-RedisScalar @("EVAL", $lua, "1", $key))
}

function Get-AdmissionMutationCount([string]$generation, [int]$shardCount) {
    $lua = "local t=redis.call('TIME'); local n=t[1]*1000+math.floor(t[2]/1000); redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',n); return redis.call('ZCARD',KEYS[1])"
    $total = 0L
    for ($shard = 0; $shard -lt $shardCount; $shard++) {
        $key = "$(Get-ShardPrefix $generation $shardCount $shard)runtime:admission-leases"
        $total += [int64](Read-RedisScalar @("EVAL", $lua, "1", $key))
    }
    return $total
}

function Get-DrainSnapshot([string]$generation, [int]$shardCount) {
    if (-not $SkipIntegrityGate) {
        & (Join-Path $PSScriptRoot "verify-commerce-integrity.ps1")
        if ($LASTEXITCODE -ne 0) { throw "Commerce integrity gate failed" }
    }
    $commerce = Query "commerce-mysql" "citycopilot_commerce" @"
SELECT COUNT(*) FROM commerce_outbox_event WHERE status <> 'PUBLISHED';
SELECT COUNT(*) FROM commerce_reservation WHERE status IN ('RESERVED','RELEASE_REQUIRED');
SELECT COUNT(*) FROM commerce_dead_letter WHERE replayed_at IS NULL;
SELECT COUNT(*) FROM commerce_order_recovery
WHERE phase IS NULL OR phase NOT IN ('ORDER_CREATED','DEAD_LETTERED','RESTORED');
SELECT COUNT(*) FROM commerce_admission_ledger
WHERE routing_generation='$generation' AND physical_shard_count=$shardCount
  AND status='ACCEPTED';
SELECT COUNT(*) FROM commerce_admission_ledger
WHERE routing_generation='$generation' AND physical_shard_count=$shardCount
  AND status='REPAIR_REQUIRED';
SELECT COUNT(*) FROM commerce_admission_ledger
WHERE routing_generation='$generation' AND physical_shard_count=$shardCount
  AND status='ACCEPTED' AND claim_token IS NOT NULL
  AND claimed_until>=CURRENT_TIMESTAMP(3);
"@
    $redisState = Get-RedisDrainState $generation $shardCount
    $gate = [ordered]@{
        generation = $generation
        physicalShardCount = $shardCount
        unpublishedOutbox = [int64]$commerce[0]
        inFlightReservations = [int64]$commerce[1]
        unreplayedDeadLetters = [int64]$commerce[2]
        commerceRedisPending = $redisState.pending
        commerceRedisLag = $redisState.lag
        commerceRedisInFlight = $redisState.inFlight
        unresolvedRecovery = [int64]$commerce[3]
        acceptedAdmissionLedger = [int64]$commerce[4]
        repairRequiredAdmissionLedger = [int64]$commerce[5]
        activeAdmissionLedgerClaims = [int64]$commerce[6]
        activeRuntimeMutations = Get-RuntimeMutationCount
        activeAdmissionMutations = Get-AdmissionMutationCount $generation $shardCount
        metadataUpdateFences = $redisState.metadataUpdateFences
        shards = $redisState.shards
    }
    $gate["safe"] = (
        $gate.unpublishedOutbox -eq 0 `
        -and $gate.inFlightReservations -eq 0 `
        -and $gate.unreplayedDeadLetters -eq 0 `
        -and $gate.commerceRedisPending -eq 0 `
        -and $gate.commerceRedisLag -eq 0 `
        -and $gate.commerceRedisInFlight -eq 0 `
        -and $gate.unresolvedRecovery -eq 0 `
        -and $gate.acceptedAdmissionLedger -eq 0 `
        -and $gate.repairRequiredAdmissionLedger -eq 0 `
        -and $gate.activeAdmissionLedgerClaims -eq 0 `
        -and $gate.activeRuntimeMutations -eq 0 `
        -and $gate.activeAdmissionMutations -eq 0 `
        -and $gate.metadataUpdateFences -eq 0
    )
    return $gate
}

function Get-VoucherShard([int64]$voucherId, [int]$shardCount) {
    $unsigned = [uint64]$voucherId
    $hashUnsigned = [uint64](($unsigned -bxor ($unsigned -shr 32)) -band [uint64]0xFFFFFFFF)
    $hashSigned = if ($hashUnsigned -ge 2147483648) {
        [int64]$hashUnsigned - 4294967296
    } else {
        [int64]$hashUnsigned
    }
    $shard = $hashSigned % $shardCount
    if ($shard -lt 0) { $shard += $shardCount }
    return [int]$shard
}

function Get-TargetPreflight([string]$generation, [int]$shardCount) {
    $voucherRows = @(Query "commerce-mysql" "citycopilot_commerce" @"
SELECT s.voucher_id,s.stock,v.type,v.status,
       CAST(UNIX_TIMESTAMP(s.begin_time) * 1000 AS UNSIGNED),
       CAST(UNIX_TIMESTAMP(s.end_time) * 1000 AS UNSIGNED),
       v.version,s.version
FROM commerce_seckill_voucher s
JOIN commerce_voucher v ON v.id=s.voucher_id
ORDER BY s.voucher_id;
"@)
    $buyerRows = @(Query "commerce-mysql" "citycopilot_commerce" @"
SELECT voucher_id,user_id FROM (
    SELECT voucher_id,user_id FROM commerce_order
    UNION
    SELECT voucher_id,user_id FROM commerce_reservation WHERE status='RESERVED'
) expected_buyers
ORDER BY voucher_id,user_id;
"@)
    $ledger = @(Query "commerce-mysql" "citycopilot_commerce" @"
SELECT COUNT(*) FROM commerce_admission_ledger
WHERE routing_generation='$generation' AND physical_shard_count=$shardCount
  AND status='ACCEPTED';
SELECT COUNT(*) FROM commerce_admission_ledger
WHERE routing_generation='$generation' AND physical_shard_count=$shardCount
  AND status='REPAIR_REQUIRED';
SELECT COUNT(*) FROM commerce_admission_ledger
WHERE routing_generation='$generation' AND physical_shard_count=$shardCount
  AND status='ACCEPTED' AND claim_token IS NOT NULL
  AND claimed_until>=CURRENT_TIMESTAMP(3);
"@)
    $expectedBuyers = @{}
    foreach ($row in $buyerRows) {
        if ([string]::IsNullOrWhiteSpace("$row")) { continue }
        $fields = @("$row" -split "`t")
        if ($fields.Count -ne 2) { throw "Unexpected buyer preflight row: $row" }
        $voucherKey = $fields[0]
        $expectedBuyers[$voucherKey] = @($expectedBuyers[$voucherKey]) + $fields[1]
    }

    $errors = [System.Collections.Generic.List[string]]::new()
    if ([int64]$ledger[0] -ne 0 -or [int64]$ledger[1] -ne 0 -or [int64]$ledger[2] -ne 0) {
        $errors.Add(
            "target admission ledger is not terminal: accepted=$($ledger[0]) repairRequired=$($ledger[1]) activeClaims=$($ledger[2])"
        )
    }
    $vouchers = @()
    foreach ($row in $voucherRows) {
        if ([string]::IsNullOrWhiteSpace("$row")) { continue }
        $fields = @("$row" -split "`t")
        if ($fields.Count -ne 8) { throw "Unexpected voucher preflight row: $row" }
        $voucherId = [int64]$fields[0]
        $databaseStock = [int64]$fields[1]
        $shard = Get-VoucherShard $voucherId $shardCount
        $prefix = Get-ShardPrefix $generation $shardCount $shard
        $stockKey = "${prefix}seckill:stock:$voucherId"
        $metaKey = "${prefix}seckill:meta:$voucherId"
        $buyersKey = "${prefix}seckill:buyers:$voucherId"
        $redisStockText = Read-RedisScalar @("GET", $stockKey)
        $stockMatches = $false
        $redisStock = 0L
        if ([int64]::TryParse($redisStockText, [ref]$redisStock)) {
            $stockMatches = $redisStock -eq $databaseStock
        }
        if (-not $stockMatches) {
            $errors.Add("voucher=$voucherId target stock mismatch: redis=$redisStockText database=$databaseStock")
        }

        $metadataActual = @(
            (Read-RedisScalar @("HGET", $metaKey, "voucherId")),
            (Read-RedisScalar @("HGET", $metaKey, "type")),
            (Read-RedisScalar @("HGET", $metaKey, "status")),
            (Read-RedisScalar @("HGET", $metaKey, "beginEpochMillis")),
            (Read-RedisScalar @("HGET", $metaKey, "endEpochMillis")),
            (Read-RedisScalar @("HGET", $metaKey, "sourceVersion"))
        )
        $metadataExpected = @(
            "$voucherId", $fields[2], $fields[3], $fields[4], $fields[5],
            "$($fields[6]):$($fields[7])"
        )
        $metadataMatches = ($metadataExpected -join "|") -eq ($metadataActual -join "|")
        if (-not $metadataMatches) {
            $errors.Add(
                "voucher=$voucherId target metadata mismatch: actual=$($metadataActual -join ',') expected=$($metadataExpected -join ',')"
            )
        }

        $voucherKey = "$voucherId"
        $databaseBuyers = if ($expectedBuyers.ContainsKey($voucherKey)) {
            @($expectedBuyers[$voucherKey] | Sort-Object -Unique)
        } else {
            @()
        }
        $redisBuyers = @(
            Invoke-Redis -Arguments @("SMEMBERS", $buyersKey) |
                Where-Object { -not [string]::IsNullOrWhiteSpace("$_") } |
                Sort-Object -Unique
        )
        $buyersMatch = ($databaseBuyers -join "|") -eq ($redisBuyers -join "|")
        if (-not $buyersMatch) {
            $errors.Add(
                "voucher=$voucherId target buyers mismatch: redis=$($redisBuyers.Count) database=$($databaseBuyers.Count)"
            )
        }
        $vouchers += [ordered]@{
            voucherId = $voucherId
            shard = $shard
            stockMatches = $stockMatches
            metadataMatches = $metadataMatches
            buyersMatch = $buyersMatch
            databaseBuyerCount = $databaseBuyers.Count
            redisBuyerCount = $redisBuyers.Count
        }
    }
    $redisState = Get-RedisDrainState $generation $shardCount
    $activeAdmissionMutations = Get-AdmissionMutationCount $generation $shardCount
    if ($redisState.pending -ne 0 -or $redisState.lag -ne 0 `
            -or $redisState.inFlight -ne 0 -or $redisState.metadataUpdateFences -ne 0) {
        $errors.Add(
            "target keyspace is not empty of work: pending=$($redisState.pending) lag=$($redisState.lag) inFlight=$($redisState.inFlight) metadataFences=$($redisState.metadataUpdateFences)"
        )
    }
    if ($activeAdmissionMutations -ne 0) {
        $errors.Add("target keyspace has $activeAdmissionMutations active admission mutations")
    }
    return [ordered]@{
        generation = $generation
        physicalShardCount = $shardCount
        safe = $errors.Count -eq 0
        errors = @($errors)
        admissionLedger = [ordered]@{
            accepted = [int64]$ledger[0]
            repairRequired = [int64]$ledger[1]
            activeClaims = [int64]$ledger[2]
        }
        vouchers = $vouchers
        redis = $redisState
        activeAdmissionMutations = $activeAdmissionMutations
    }
}

function Assert-TargetParameters {
    if ($TargetGeneration -notmatch "^[A-Za-z0-9_-]{1,24}$") {
        throw "-TargetGeneration must match ^[A-Za-z0-9_-]{1,24}$"
    }
    if ($TargetPhysicalShardCount -lt 1 -or $TargetPhysicalShardCount -gt 1024) {
        throw "-TargetPhysicalShardCount must be 1..1024"
    }
}

function Move-PreparedAdmissions(
    [string]$sourceGeneration,
    [int]$sourceShardCount,
    [string]$targetGeneration,
    [int]$targetShardCount
) {
    if ($sourceGeneration -eq $targetGeneration -and $sourceShardCount -eq $targetShardCount) {
        return [ordered]@{
            migrated = 0L
            sourcePreparedRemaining = 0L
            safe = $true
        }
    }
    $result = @(Query "commerce-mysql" "citycopilot_commerce" @"
UPDATE commerce_admission_ledger
SET routing_generation='$targetGeneration',physical_shard_count=$targetShardCount,
    updated_at=CURRENT_TIMESTAMP(3)
WHERE routing_generation='$sourceGeneration' AND physical_shard_count=$sourceShardCount
  AND status='PREPARED';
SELECT ROW_COUNT();
SELECT COUNT(*) FROM commerce_admission_ledger
WHERE routing_generation='$sourceGeneration' AND physical_shard_count=$sourceShardCount
  AND status='PREPARED';
"@)
    $migration = [ordered]@{
        migrated = [int64]$result[0]
        sourcePreparedRemaining = [int64]$result[1]
        safe = [int64]$result[1] -eq 0
    }
    if (-not $migration.safe) {
        $migration | ConvertTo-Json -Depth 4
        throw "Source PREPARED admissions appeared during routing activation"
    }
    return $migration
}

Push-Location $root
try {
    switch ($Action) {
        "Status" {
            $control = Get-CurrentControl
            [ordered]@{
                control = $control
                requestedGeneration = $Generation
                requestedPhysicalShardCount = $PhysicalShardCount
            } | ConvertTo-Json -Depth 6
        }
        "Initialize" {
            $control = Get-CurrentControl
            if ($null -eq $control) {
                Set-GatesForInitialization $Generation $PhysicalShardCount
                $next = Get-ControlValue "ACTIVE" $Generation $PhysicalShardCount (New-Revision)
                [void](Invoke-Redis -Arguments @("SET", $ControlKey, $next, "NX"))
                $control = Get-CurrentControl
            }
            Assert-Fingerprint $control $Generation $PhysicalShardCount
            if ($control.state -ne "ACTIVE") { throw "Existing routing control is not ACTIVE" }
            Compare-And-SetControl $control.raw $control.raw
            Set-GatesForInitialization $Generation $PhysicalShardCount
            [ordered]@{ action = $Action; control = $control; safe = $true } | ConvertTo-Json -Depth 5
        }
        "BeginDrain" {
            $control = Get-CurrentControl
            Assert-Fingerprint $control $Generation $PhysicalShardCount
            if ($control.state -eq "ACTIVE") {
                $next = Get-ControlValue "DRAINING" $Generation $PhysicalShardCount (New-Revision)
                Compare-And-SetControl $control.raw $next
                $control = Get-CurrentControl
            } elseif ($control.state -ne "DRAINING") {
                throw "Routing control cannot enter DRAINING from state $($control.state)"
            }
            Compare-And-SetControl $control.raw $control.raw
            Set-SourceGatesDraining $Generation $PhysicalShardCount
            Assert-Gates "DRAINING" $Generation $PhysicalShardCount
            [ordered]@{ action = $Action; control = $control; admissionClosed = $true } | ConvertTo-Json -Depth 5
        }
        "VerifyDrain" {
            $control = Get-CurrentControl
            Assert-Fingerprint $control $Generation $PhysicalShardCount
            if ($control.state -ne "DRAINING") { throw "BeginDrain must complete before VerifyDrain" }
            Assert-Gates "DRAINING" $Generation $PhysicalShardCount
            $gate = Get-DrainSnapshot $Generation $PhysicalShardCount
            $gate | ConvertTo-Json -Depth 8
            if (-not $gate.safe) { throw "Commerce runtime is not drained" }
        }
        "Activate" {
            Assert-TargetParameters
            $control = Get-CurrentControl
            $fingerprintChanged = (
                $Generation -ne $TargetGeneration `
                -or $PhysicalShardCount -ne $TargetPhysicalShardCount
            )
            $fullyActivated = $null -ne $control `
                -and $control.state -eq "ACTIVE" `
                -and $control.generation -eq $TargetGeneration `
                -and $control.physicalShardCount -eq $TargetPhysicalShardCount `
                -and (Test-AllGatesActive $TargetGeneration $TargetPhysicalShardCount)
            if ($fullyActivated) {
                [ordered]@{
                    action = $Action
                    control = $control
                    activated = $true
                    alreadyActivated = $true
                } | ConvertTo-Json -Depth 5
                break
            }
            $alreadyCommitted = $null -ne $control `
                -and $control.state -eq "ACTIVE" `
                -and $control.generation -eq $TargetGeneration `
                -and $control.physicalShardCount -eq $TargetPhysicalShardCount `
                -and ($fingerprintChanged -or (Test-AnyGateDraining $TargetGeneration $TargetPhysicalShardCount))
            if (-not $alreadyCommitted) {
                Assert-Fingerprint $control $Generation $PhysicalShardCount
                if ($control.state -ne "DRAINING") { throw "BeginDrain must complete before Activate" }
                Assert-Gates "DRAINING" $Generation $PhysicalShardCount
                $gate = Get-DrainSnapshot $Generation $PhysicalShardCount
                if (-not $gate.safe) {
                    $gate | ConvertTo-Json -Depth 8
                    throw "Commerce runtime is not drained"
                }
            }
            if (-not $alreadyCommitted) {
                $targetPreflightBeforeCas = Get-TargetPreflight $TargetGeneration $TargetPhysicalShardCount
                if (-not $targetPreflightBeforeCas.safe) {
                    $targetPreflightBeforeCas | ConvertTo-Json -Depth 10
                    throw "Target Commerce routing keyspace failed business-data preflight"
                }
                Prepare-TargetGates $TargetGeneration $TargetPhysicalShardCount
                $next = Get-ControlValue "ACTIVE" $TargetGeneration $TargetPhysicalShardCount (New-Revision)
                Compare-And-SetControl $control.raw $next
                $control = Get-CurrentControl
                $preparedMigration = Move-PreparedAdmissions `
                    $Generation $PhysicalShardCount `
                    $TargetGeneration $TargetPhysicalShardCount
                $targetPreflight = Get-TargetPreflight $TargetGeneration $TargetPhysicalShardCount
                if (-not $targetPreflight.safe) {
                    $targetPreflight | ConvertTo-Json -Depth 10
                    throw "Target Commerce routing keyspace changed after control CAS"
                }
            } else {
                if ($fingerprintChanged) {
                    Assert-Gates "DRAINING" $Generation $PhysicalShardCount
                }
                Set-SourceGatesDraining $TargetGeneration $TargetPhysicalShardCount
                Assert-Gates "DRAINING" $TargetGeneration $TargetPhysicalShardCount
                Compare-And-SetControl $control.raw $control.raw
                $preparedMigration = Move-PreparedAdmissions `
                    $Generation $PhysicalShardCount `
                    $TargetGeneration $TargetPhysicalShardCount
                $targetPreflight = Get-TargetPreflight $TargetGeneration $TargetPhysicalShardCount
                if (-not $targetPreflight.safe) {
                    $targetPreflight | ConvertTo-Json -Depth 10
                    throw "Target Commerce routing keyspace failed resume preflight"
                }
            }
            $postCasDrain = Get-DrainSnapshot $Generation $PhysicalShardCount
            if (-not $postCasDrain.safe) {
                $postCasDrain | ConvertTo-Json -Depth 8
                throw "Source Commerce runtime changed after control CAS"
            }
            # Final ledger move and target validation must finish before any
            # target gate opens. A CAS loser never reaches this branch.
            $preparedPostflight = Move-PreparedAdmissions `
                $Generation $PhysicalShardCount `
                $TargetGeneration $TargetPhysicalShardCount
            $targetPreflight = Get-TargetPreflight $TargetGeneration $TargetPhysicalShardCount
            if (-not $targetPreflight.safe) {
                $targetPreflight | ConvertTo-Json -Depth 10
                throw "Target Commerce routing keyspace failed final preflight"
            }
            Compare-And-SetControl $control.raw $control.raw
            Activate-TargetGates $TargetGeneration $TargetPhysicalShardCount
            Assert-Gates "ACTIVE" $TargetGeneration $TargetPhysicalShardCount
            [ordered]@{
                action = $Action
                control = $control
                sourceGeneration = $Generation
                sourcePhysicalShardCount = $PhysicalShardCount
                activated = $true
                resumedAfterCommittedCas = $alreadyCommitted
                targetPreflight = $targetPreflight
                targetPreflightBeforeCas = $targetPreflightBeforeCas
                postCasDrain = $postCasDrain
                preparedMigration = $preparedMigration
                preparedPostflight = $preparedPostflight
            } | ConvertTo-Json -Depth 6
        }
    }
} finally {
    Pop-Location
}
