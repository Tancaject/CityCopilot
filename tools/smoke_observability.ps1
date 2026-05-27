param(
    [string]$BaseUrl = "http://localhost:8081",
    [int]$TimeoutSec = 10
)

$ErrorActionPreference = "Stop"

$normalizedBaseUrl = $BaseUrl.TrimEnd("/")
$metrics = Invoke-RestMethod -Method Get -Uri "$normalizedBaseUrl/actuator/prometheus" -TimeoutSec $TimeoutSec

if ([string]::IsNullOrWhiteSpace($metrics)) {
    throw "Prometheus endpoint returned an empty response."
}

$expectedMetrics = @(
    "citycopilot_tool_call_count_total",
    "citycopilot_tool_call_duration_seconds_count",
    "citycopilot_llm_call_count_total",
    "citycopilot_llm_call_duration_seconds_count"
)

$missing = @()
foreach ($metric in $expectedMetrics) {
    if ($metrics -notmatch [regex]::Escape($metric)) {
        $missing += $metric
    }
}

if ($missing.Count -gt 0) {
    throw "Prometheus endpoint is reachable, but missing metrics: $($missing -join ', ')"
}

Write-Host "Prometheus smoke ok: CityCopilot tool and LLM metrics are present."
