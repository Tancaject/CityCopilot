param(
    [string]$BaseUrl = "http://localhost:8090",
    [string]$ToolName = "list_shop_types",
    [string]$ArgumentsJson = "{}",
    [switch]$SkipToolCall,
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
$tools = Invoke-CityCopilotJson -Method "Get" -Uri "$normalizedBaseUrl/mcp/tools/list"
$toolCount = @($tools).Count
if ($toolCount -lt 1) {
    throw "MCP tools/list returned no tools."
}

Write-Host "MCP tools/list ok: $toolCount tools"

if ($SkipToolCall) {
    exit 0
}

$arguments = if ([string]::IsNullOrWhiteSpace($ArgumentsJson) -or $ArgumentsJson.Trim() -eq "{}") {
    @{}
} else {
    $ArgumentsJson | ConvertFrom-Json
}

$result = Invoke-CityCopilotJson -Method "Post" -Uri "$normalizedBaseUrl/mcp/tools/call" -Body @{
    name = $ToolName
    arguments = $arguments
}

if ($result.ok -ne $true) {
    $errorCode = if ($null -ne $result.error) { $result.error.code } else { "unknown" }
    $errorMessage = if ($null -ne $result.error) { $result.error.message } else { ($result | ConvertTo-Json -Depth 20) }
    throw "MCP tools/call failed for '$ToolName': $errorCode $errorMessage"
}

Write-Host "MCP tools/call ok: $ToolName"
