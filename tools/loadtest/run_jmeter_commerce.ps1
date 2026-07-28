param(
    [ValidateRange(1, 200000)]
    [int]$Users = 5000,

    [ValidateRange(1, 5000)]
    [int]$Threads = 200,

    [ValidateRange(0, 600)]
    [int]$RampSeconds = 10,

    [ValidateRange(30, 1800)]
    [int]$DrainTimeoutSeconds = 180,

    [switch]$BrokerOutage,

    [switch]$RequireOutboxDrain,

    [string]$JMeterHome = "D:\program software\apache-jmeter-5.6.3"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$python = "C:\Users\Orion\AppData\Local\Programs\Python\Python312\python.exe"
$jmeter = Join-Path $JMeterHome "bin\jmeter.bat"
$testPlan = Join-Path $PSScriptRoot "CityCopilot-Seckill.jmx"
$tokens = Join-Path $PSScriptRoot "commerce-tokens.csv"
$runId = "{0}-u{1}-t{2}{3}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), $Users, $Threads, $(if ($BrokerOutage) { "-mq-outage" } else { "" })
$resultRoot = Join-Path $PSScriptRoot "results\$runId"
$jtl = Join-Path $resultRoot "samples.jtl"
$html = Join-Path $resultRoot "html"
$summaryFile = Join-Path $resultRoot "summary.json"
$metricsBefore = Join-Path $resultRoot "commerce-metrics-before.txt"
$metricsAfter = Join-Path $resultRoot "commerce-metrics-after.txt"
$statsBefore = Join-Path $resultRoot "docker-stats-before.txt"
$statsAfter = Join-Path $resultRoot "docker-stats-after.txt"

if (-not (Test-Path -LiteralPath $jmeter)) {
    throw "JMeter executable not found: $jmeter"
}
if (-not (Test-Path -LiteralPath $python)) {
    throw "Python executable not found: $python"
}

New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null

function Read-CommerceMetrics {
    param([string]$Destination)
    $content = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:8083/actuator/prometheus" -TimeoutSec 10).Content
    $lines = $content -split "`n" | Where-Object {
        $_ -match "^citycopilot_commerce_" -or $_ -match "^jvm_" -or $_ -match "^process_"
    }
    [System.IO.File]::WriteAllLines($Destination, $lines, [System.Text.UTF8Encoding]::new($false))
}

function Get-UnpublishedOutbox {
    $sql = @"
SELECT COUNT(*)
FROM commerce_outbox_event
WHERE aggregate_type='commerce_order'
  AND aggregate_id IN (
    SELECT CAST(id AS CHAR) FROM commerce_order WHERE voucher_id=200
  )
  AND status <> 'PUBLISHED';
"@
    $value = $sql | docker compose exec -T commerce-mysql sh -c 'mysql -N -B -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to query Commerce Outbox"
    }
    return [int]($value | Select-Object -Last 1)
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percent
    )
    if (-not $Values -or $Values.Count -eq 0) {
        return 0
    }
    $ordered = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Min($ordered.Count - 1, [Math]::Ceiling($Percent * $ordered.Count) - 1))
    return [Math]::Round($ordered[$index], 2)
}

$proxyStopped = $false
try {
    docker compose ps --status running --services | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose is unavailable"
    }

    & $python (Join-Path $PSScriptRoot "seed_commerce_loadtest.py") --stock $Users --users $Users
    if ($LASTEXITCODE -ne 0) {
        throw "Commerce load-test seeding failed"
    }

    Read-CommerceMetrics -Destination $metricsBefore
    docker stats --no-stream --format "{{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}" | Set-Content -LiteralPath $statsBefore -Encoding utf8

    if ($BrokerOutage) {
        docker compose stop rocketmq-proxy
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to stop RocketMQ Proxy"
        }
        $proxyStopped = $true
    }

    $tokenProperty = $tokens.Replace("\", "/")
    $arguments = @(
        "-n",
        "-t", $testPlan,
        "-Jthreads=$Threads",
        "-Jramp=$RampSeconds",
        "-Jtokens=$tokenProperty",
        "-Jpath=/voucher-order/seckill/200",
        "-l", $jtl,
        "-e",
        "-o", $html
    )
    & $jmeter @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "JMeter exited with code $LASTEXITCODE"
    }

    $mustDrainOutbox = $RequireOutboxDrain -or $BrokerOutage
    $outboxAtRecovery = Get-UnpublishedOutbox
    if ($BrokerOutage) {
        docker compose up -d rocketmq-proxy
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to restore RocketMQ Proxy"
        }
        $proxyStopped = $false
    }

    $drainStarted = Get-Date
    $unpublished = $outboxAtRecovery
    if ($mustDrainOutbox) {
        do {
            $unpublished = Get-UnpublishedOutbox
            if ($unpublished -eq 0) {
                break
            }
            Start-Sleep -Seconds 2
        } while (((Get-Date) - $drainStarted).TotalSeconds -lt $DrainTimeoutSeconds)
        if ($unpublished -ne 0) {
            throw "Outbox did not drain within $DrainTimeoutSeconds seconds; remaining=$unpublished"
        }
    }
    $drainSeconds = [Math]::Round(((Get-Date) - $drainStarted).TotalSeconds, 3)

    $verifyArguments = @((Join-Path $PSScriptRoot "seed_commerce_loadtest.py"), "--stock", $Users, "--verify")
    if (-not $mustDrainOutbox) {
        $verifyArguments += "--allow-unpublished"
    }
    & $python @verifyArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Post-load business invariant verification failed"
    }

    $samples = @(Import-Csv -LiteralPath $jtl)
    $elapsed = @($samples | ForEach-Object { [double]$_.elapsed })
    $successCount = @($samples | Where-Object { $_.success -eq "true" }).Count
    $errorCount = $samples.Count - $successCount
    $firstTimestamp = ($samples | Measure-Object -Property timeStamp -Minimum).Minimum
    $lastTimestamp = ($samples | Measure-Object -Property timeStamp -Maximum).Maximum
    $lastElapsed = ($samples | Where-Object { $_.timeStamp -eq $lastTimestamp } | Measure-Object -Property elapsed -Maximum).Maximum
    $durationSeconds = if ($samples.Count -gt 0) {
        (([double]$lastTimestamp + [double]$lastElapsed) - [double]$firstTimestamp) / 1000
    } else {
        0
    }

    Read-CommerceMetrics -Destination $metricsAfter
    docker stats --no-stream --format "{{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}" | Set-Content -LiteralPath $statsAfter -Encoding utf8

    $summary = [ordered]@{
        runId = $runId
        timestamp = (Get-Date).ToString("o")
        scenario = if ($BrokerOutage) {
            "Commerce seckill while RocketMQ Proxy is unavailable, followed by recovery"
        } elseif ($mustDrainOutbox) {
            "Commerce seckill with RocketMQ relay required"
        } else {
            "Commerce seckill with local RocketMQ relay optional"
        }
        users = $Users
        threads = $Threads
        rampSeconds = $RampSeconds
        samples = $samples.Count
        successes = $successCount
        errors = $errorCount
        errorRate = if ($samples.Count) { [Math]::Round($errorCount / $samples.Count, 6) } else { 1 }
        durationSeconds = [Math]::Round($durationSeconds, 3)
        throughputRps = if ($durationSeconds -gt 0) { [Math]::Round($samples.Count / $durationSeconds, 2) } else { 0 }
        latencyMs = [ordered]@{
            mean = if ($elapsed.Count) { [Math]::Round(($elapsed | Measure-Object -Average).Average, 2) } else { 0 }
            p50 = Get-Percentile -Values $elapsed -Percent 0.50
            p95 = Get-Percentile -Values $elapsed -Percent 0.95
            p99 = Get-Percentile -Values $elapsed -Percent 0.99
            max = if ($elapsed.Count) { [Math]::Round(($elapsed | Measure-Object -Maximum).Maximum, 2) } else { 0 }
        }
        outbox = [ordered]@{
            drainRequired = $mustDrainOutbox
            unpublishedAtRecovery = $outboxAtRecovery
            unpublishedAfterDrain = $unpublished
            drainSeconds = $drainSeconds
        }
        artifacts = [ordered]@{
            jtl = $jtl
            html = Join-Path $html "index.html"
            metricsBefore = $metricsBefore
            metricsAfter = $metricsAfter
            dockerStatsBefore = $statsBefore
            dockerStatsAfter = $statsAfter
        }
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryFile -Encoding utf8
    $summary | ConvertTo-Json -Depth 8

    if ($errorCount -gt 0 -or $samples.Count -ne $Users) {
        throw "Load test failed acceptance: samples=$($samples.Count), users=$Users, errors=$errorCount"
    }
}
finally {
    if ($proxyStopped) {
        docker compose up -d rocketmq-proxy | Out-Null
    }
    if (Test-Path -LiteralPath $tokens) {
        Remove-Item -LiteralPath $tokens -Force
    }
}
