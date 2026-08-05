[CmdletBinding()]
param(
    [ValidateSet("start", "stop", "restart", "status", "logs", "reset")]
    [string]$Action = "start",
    [string]$Service,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ComposeFile = Join-Path $ProjectRoot "compose.yaml"
$EnvFile = Join-Path $ProjectRoot ".env"
$InfrastructureServices = @(
    "mysql",
    "agent-mysql",
    "commerce-mysql",
    "redis",
    "rocketmq-namesrv",
    "rocketmq-broker",
    "rocketmq-proxy"
)
$ApplicationServices = @(
    "core",
    "commerce",
    "agent",
    "mcp",
    "frontend"
)

function New-CityCopilotSecret {
    param([int]$ByteCount = 32)
    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Ensure-EnvironmentFile {
    if (Test-Path -LiteralPath $EnvFile) {
        $existingKeys = @{}
        foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
            $parts = $line.Split("=", 2)
            if ($parts.Count -eq 2) {
                $existingKeys[$parts[0]] = $true
            }
        }
        $newCredentials = @()
        foreach ($key in @(
            "CITYCOPILOT_INTERNAL_AGENT_TOKEN",
            "CITYCOPILOT_INTERNAL_OPS_TOKEN",
            "CITYCOPILOT_INTERNAL_OPS_READER_TOKEN",
            "CITYCOPILOT_INTERNAL_OPS_EXECUTOR_TOKEN",
            "CITYCOPILOT_INTERNAL_MCP_TOKEN",
            "CITYCOPILOT_AGENT_DB_PASSWORD",
            "CITYCOPILOT_AGENT_MYSQL_ROOT_PASSWORD",
            "CITYCOPILOT_COMMERCE_DB_PASSWORD",
            "CITYCOPILOT_COMMERCE_MYSQL_ROOT_PASSWORD",
            "CITYCOPILOT_COMMERCE_REDIS_PASSWORD",
            "CITYCOPILOT_INTERNAL_COMMERCE_TOKEN"
        )) {
            if (-not $existingKeys.ContainsKey($key)) {
                $newCredentials += "$key=$(New-CityCopilotSecret 48)"
            }
        }
        foreach ($setting in @(
            "CITYCOPILOT_AGENT_MYSQL_PORT=3308",
            "CITYCOPILOT_COMMERCE_MYSQL_PORT=3309",
            "CITYCOPILOT_COMMERCE_REDIS_USERNAME=commerce",
            "CITYCOPILOT_ROCKETMQ_ENABLED=true"
        )) {
            $parts = $setting.Split("=", 2)
            if (-not $existingKeys.ContainsKey($parts[0])) {
                $newCredentials += $setting
            }
        }
        if ($newCredentials.Count -gt 0) {
            $writer = New-Object System.IO.StreamWriter(
                $EnvFile,
                $true,
                (New-Object System.Text.UTF8Encoding($false))
            )
            try {
                foreach ($credential in $newCredentials) {
                    $writer.WriteLine($credential)
                }
            } finally {
                $writer.Dispose()
            }
            Write-Host "Added missing local environment settings to .env." -ForegroundColor Green
        }
        return
    }
    $lines = @(
        "COMPOSE_PROJECT_NAME=citycopilot",
        "CITYCOPILOT_BIND_ADDRESS=127.0.0.1",
        "CITYCOPILOT_TIMEZONE=Asia/Singapore",
        "CITYCOPILOT_HTTP_PORT=8080",
        "CITYCOPILOT_CORE_PORT=8081",
        "CITYCOPILOT_AGENT_PORT=8082",
        "CITYCOPILOT_MCP_PORT=8090",
        "CITYCOPILOT_MYSQL_PORT=3307",
        "CITYCOPILOT_AGENT_MYSQL_PORT=3308",
        "CITYCOPILOT_COMMERCE_MYSQL_PORT=3309",
        "CITYCOPILOT_COMMERCE_PORT=8083",
        "CITYCOPILOT_REDIS_PORT=6380",
        "CITYCOPILOT_MYSQL_MEMORY_LIMIT=1g",
        "CITYCOPILOT_AGENT_MYSQL_MEMORY_LIMIT=512m",
        "CITYCOPILOT_COMMERCE_MYSQL_MEMORY_LIMIT=512m",
        "CITYCOPILOT_COMMERCE_MEMORY_LIMIT=768m",
        "CITYCOPILOT_REDIS_MEMORY_LIMIT=256m",
        "CITYCOPILOT_CORE_MEMORY_LIMIT=1g",
        "CITYCOPILOT_AGENT_MEMORY_LIMIT=768m",
        "CITYCOPILOT_MCP_MEMORY_LIMIT=512m",
        "CITYCOPILOT_FRONTEND_MEMORY_LIMIT=128m",
        "CITYCOPILOT_DB_USERNAME=citycopilot",
        "CITYCOPILOT_DB_PASSWORD=$(New-CityCopilotSecret 24)",
        "CITYCOPILOT_MYSQL_ROOT_PASSWORD=$(New-CityCopilotSecret 24)",
        "CITYCOPILOT_AGENT_DB_USERNAME=citycopilot_agent",
        "CITYCOPILOT_AGENT_DB_PASSWORD=$(New-CityCopilotSecret 24)",
        "CITYCOPILOT_AGENT_MYSQL_ROOT_PASSWORD=$(New-CityCopilotSecret 24)",
        "CITYCOPILOT_COMMERCE_DB_USERNAME=citycopilot_commerce",
        "CITYCOPILOT_COMMERCE_DB_PASSWORD=$(New-CityCopilotSecret 24)",
        "CITYCOPILOT_COMMERCE_MYSQL_ROOT_PASSWORD=$(New-CityCopilotSecret 24)",
        "CITYCOPILOT_REDIS_PASSWORD=$(New-CityCopilotSecret 24)",
        "CITYCOPILOT_COMMERCE_REDIS_USERNAME=commerce",
        "CITYCOPILOT_COMMERCE_REDIS_PASSWORD=$(New-CityCopilotSecret 24)",
        "CITYCOPILOT_INTERNAL_SERVICE_TOKEN=$(New-CityCopilotSecret 48)",
        "CITYCOPILOT_INTERNAL_AGENT_TOKEN=$(New-CityCopilotSecret 48)",
        "CITYCOPILOT_INTERNAL_OPS_TOKEN=$(New-CityCopilotSecret 48)",
        "CITYCOPILOT_INTERNAL_OPS_READER_TOKEN=$(New-CityCopilotSecret 48)",
        "CITYCOPILOT_INTERNAL_OPS_EXECUTOR_TOKEN=$(New-CityCopilotSecret 48)",
        "CITYCOPILOT_INTERNAL_MCP_TOKEN=$(New-CityCopilotSecret 48)",
        "CITYCOPILOT_INTERNAL_COMMERCE_TOKEN=$(New-CityCopilotSecret 48)",
        "CITYCOPILOT_AI_ENABLED=false",
        "CITYCOPILOT_ROCKETMQ_ENABLED=true",
        "CITYCOPILOT_AI_API_KEY=",
        "CITYCOPILOT_AI_API_URL=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        "CITYCOPILOT_AI_MODEL=qwen-plus",
        "CITYCOPILOT_AMAP_WEB_SERVICE_KEY=",
        "VITE_DEFAULT_CITY=杭州",
        "VITE_AMAP_JS_KEY=",
        "VITE_AMAP_SECURITY_CODE="
    )
    [System.IO.File]::WriteAllLines($EnvFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Created local .env with random passwords and service token." -ForegroundColor Green
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker compose --project-directory $ProjectRoot --env-file $EnvFile -f $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE"
    }
}

function Get-ComposeOutput {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = & docker compose --project-directory $ProjectRoot --env-file $EnvFile -f $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE"
    }
    return $output
}

function Test-DockerDaemon {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell turns native stderr into an ErrorRecord when it is
        # redirected. A stopped daemon is an expected probe result, not a
        # terminating script error.
        $ErrorActionPreference = "SilentlyContinue"
        & docker info --format "{{.ServerVersion}}" *> $null
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Ensure-DockerDaemon {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker CLI was not found. Install Docker Desktop first."
    }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose v2 was not found. Update Docker Desktop."
    }
    if (Test-DockerDaemon) {
        return
    }

    $candidates = @(
        (Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"),
        (Join-Path $env:LOCALAPPDATA "Docker\Docker Desktop.exe")
    )
    $desktop = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $desktop) {
        throw "Docker daemon is not running and Docker Desktop could not be located."
    }

    Write-Host "Docker Desktop is not running; starting it now..." -ForegroundColor Yellow
    Start-Process -FilePath $desktop -WindowStyle Hidden
    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (Test-DockerDaemon) {
            Write-Host "Docker Desktop is ready." -ForegroundColor Green
            return
        }
    }
    throw "Docker Desktop did not become ready within two minutes. Open it and rerun the command."
}

function Read-CityCopilotEnvironment {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith("#")) {
            continue
        }
        $parts = $line.Split("=", 2)
        if ($parts.Count -eq 2) {
            $values[$parts[0]] = $parts[1]
        }
    }
    return $values
}

function Remove-ApplicationContainers {
    foreach ($name in $ApplicationServices) {
        $queryArguments = @("--profile", "app", "ps", "-a", "-q", $name)
        $ids = @(Get-ComposeOutput @queryArguments)
        foreach ($id in $ids) {
            if ([string]::IsNullOrWhiteSpace($id)) {
                continue
            }
            & docker rm -f $id | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to remove the Docker application container for $name."
            }
        }
    }
}

function Wait-ServicesHealthy {
    param([string[]]$Names)
    $deadline = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $deadline) {
        $pending = @()
        foreach ($name in $Names) {
            $id = (Get-ComposeOutput ps -q $name | Select-Object -First 1)
            if ([string]::IsNullOrWhiteSpace($id)) {
                $pending += "${name}:not-created"
                continue
            }
            $state = (& docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $id).Trim()
            if ($LASTEXITCODE -ne 0) {
                $pending += "${name}:inspect-failed"
            } elseif ($state -eq "exited" -or $state -eq "dead") {
                throw "$name exited before becoming healthy. Run '.\tools\citycopilot.ps1 logs -Service $name'."
            } elseif ($state -ne "healthy" -and $state -ne "running") {
                $pending += "${name}:$state"
            }
        }
        if ($pending.Count -eq 0) {
            return
        }
        Write-Host ("Waiting for services: " + ($pending -join ", "))
        Start-Sleep -Seconds 5
    }
    Invoke-Compose ps
    throw "The infrastructure did not become healthy within four minutes. Run '.\tools\citycopilot.ps1 logs'."
}

function Start-Infrastructure {
    $arguments = @("up", "-d", "--remove-orphans") + $InfrastructureServices
    Invoke-Compose @arguments
    Wait-ServicesHealthy -Names $InfrastructureServices
    Invoke-RedisInitialization
}

function Invoke-RedisInitialization {
    Invoke-Compose run --rm --no-deps redis-init
}

function Invoke-CoreSchemaMigrations {
    Invoke-Compose exec -T mysql sh /opt/citycopilot/migrations/apply-core-migrations.sh
}

function Invoke-CommerceSchemaMigrations {
    Invoke-Compose exec -T commerce-mysql sh /opt/citycopilot/migrations/apply-commerce-migrations.sh
}

Ensure-EnvironmentFile

switch ($Action) {
    "start" {
        Ensure-DockerDaemon
        Invoke-Compose config --quiet
        Remove-ApplicationContainers
        Start-Infrastructure
        Invoke-CoreSchemaMigrations
        Invoke-CommerceSchemaMigrations
        Invoke-Compose ps
        $environment = Read-CityCopilotEnvironment
        Write-Host ("Infrastructure is ready: MySQL {0}/{1}/{2}, Redis {3}, RocketMQ Proxy 18081." -f
            $environment["CITYCOPILOT_MYSQL_PORT"],
            $environment["CITYCOPILOT_AGENT_MYSQL_PORT"],
            $environment["CITYCOPILOT_COMMERCE_MYSQL_PORT"],
            $environment["CITYCOPILOT_REDIS_PORT"]) -ForegroundColor Green
        Write-Host "Start Commerce, Core, Agent, MCP, and the Vue frontend from the IDE." -ForegroundColor Green
    }
    "stop" {
        Ensure-DockerDaemon
        $arguments = @("--profile", "app", "--profile", "search", "down", "--remove-orphans")
        Invoke-Compose @arguments
        Write-Host "CityCopilot infrastructure stopped. Named volumes were preserved." -ForegroundColor Green
    }
    "restart" {
        Ensure-DockerDaemon
        Remove-ApplicationContainers
        $arguments = @("restart") + $InfrastructureServices
        Invoke-Compose @arguments
        Wait-ServicesHealthy -Names $InfrastructureServices
        Invoke-RedisInitialization
        Invoke-CoreSchemaMigrations
        Invoke-CommerceSchemaMigrations
        Write-Host "CityCopilot infrastructure restarted and is healthy." -ForegroundColor Green
    }
    "status" {
        Ensure-DockerDaemon
        $arguments = @("--profile", "app", "--profile", "search", "ps", "-a")
        Invoke-Compose @arguments
    }
    "logs" {
        Ensure-DockerDaemon
        $arguments = @("logs", "--tail", "200", "--follow")
        if (-not [string]::IsNullOrWhiteSpace($Service)) { $arguments += $Service }
        $arguments = @("--profile", "app", "--profile", "search") + $arguments
        Invoke-Compose @arguments
    }
    "reset" {
        Ensure-DockerDaemon
        if (-not $Force) {
            throw "Reset permanently deletes MySQL, Docker Redis, Elasticsearch, and uploaded-file volumes. WSL Redis is not affected. Rerun with -Force to confirm."
        }
        $arguments = @("--profile", "app", "--profile", "search", "down", "--volumes", "--remove-orphans")
        Invoke-Compose @arguments
        Write-Host "CityCopilot data volumes were deleted. Run start to initialize a clean stack." -ForegroundColor Yellow
    }
}
