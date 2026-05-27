param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$TaskType = "daily-report",
    [string]$PayloadJson = "{}",
    [int]$TimeoutSec = 10
)

$ErrorActionPreference = "Stop"

function Invoke-CityCopilotJson {
    param(
        [string]$Method,
        [string]$Uri,
        [object]$Body = $null
    )

    $parameters = @{
        Method = $Method
        Uri = $Uri
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = ($Body | ConvertTo-Json -Depth 20)
    }
    Invoke-RestMethod @parameters
}

$normalizedBaseUrl = $BaseUrl.TrimEnd("/")
$payload = if ([string]::IsNullOrWhiteSpace($PayloadJson) -or $PayloadJson.Trim() -eq "{}") {
    @{}
} else {
    $PayloadJson | ConvertFrom-Json
}

$taskPath = switch ($TaskType) {
    "geo-calibration" { "geo-calibration" }
    "order-anomalies" { "order-anomalies" }
    "daily-report" { "daily-report" }
    default { throw "Unsupported task type '$TaskType'. Use geo-calibration, order-anomalies, or daily-report." }
}

$queued = Invoke-CityCopilotJson -Method "Post" -Uri "$normalizedBaseUrl/agent/ops/tasks/$taskPath" -Body $payload
if ($queued.queued -ne $true) {
    throw "Ops task enqueue failed: $($queued | ConvertTo-Json -Depth 20)"
}
Write-Host "Ops enqueue ok: $TaskType recordId=$($queued.recordId)"

$processed = Invoke-CityCopilotJson -Method "Post" -Uri "$normalizedBaseUrl/agent/ops/poll-once"
if ($processed.processed -ne $true) {
    throw "Ops poll-once did not process a task: $($processed | ConvertTo-Json -Depth 20)"
}
Write-Host "Ops poll-once ok: $($processed.type) mode=$($processed.executionMode)"

$results = Invoke-CityCopilotJson -Method "Get" -Uri "$normalizedBaseUrl/agent/ops/results?limit=5"
if ($results.available -ne $true) {
    throw "Ops results unavailable: $($results | ConvertTo-Json -Depth 20)"
}
if (@($results.results).Count -lt 1) {
    throw "Ops results endpoint returned no stored results."
}

Write-Host "Ops results ok: $(@($results.results).Count) rows from $($results.key)"
