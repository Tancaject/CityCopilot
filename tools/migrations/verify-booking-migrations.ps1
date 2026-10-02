param(
    [string]$JdbcUrl = $env:CITYCOPILOT_MIGRATION_TEST_URL
)

$ErrorActionPreference = 'Stop'
if ($JdbcUrl -notmatch '^jdbc:mysql://(127\.0\.0\.1|localhost):([0-9]+)/$') {
    throw 'Set CITYCOPILOT_MIGRATION_TEST_URL or -JdbcUrl to an isolated local MySQL server, e.g. jdbc:mysql://127.0.0.1:33319/. DatabaseMigrationsIT creates and drops its own uniquely named test databases.'
}
if ([int]$Matches[2] -lt 1 -or [int]$Matches[2] -gt 65535) {
    throw 'The migration test MySQL port must be between 1 and 65535.'
}

$migrationRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$migrationMaven = Join-Path $migrationRepo '.tools/apache-maven-3.9.9/bin/mvn.cmd'
if (-not (Test-Path -LiteralPath $migrationMaven)) {
    $migrationMaven = (Get-Command mvn -ErrorAction Stop).Source
}

$previousMigrationUrl = $env:CITYCOPILOT_MIGRATION_TEST_URL
try {
    $env:CITYCOPILOT_MIGRATION_TEST_URL = $JdbcUrl
    # Test credentials remain in CITYCOPILOT_MIGRATION_TEST_USER/PASSWORD, never in Maven arguments.
    Push-Location $migrationRepo
    try {
        & $migrationMaven -q -pl backend/core -am `
            '-Dit.test=DatabaseMigrationsIT' '-Dfailsafe.failIfNoSpecifiedTests=false' `
            test-compile failsafe:integration-test failsafe:verify
        if ($LASTEXITCODE -ne 0) {
            throw "Database migration verification failed with Maven exit code $LASTEXITCODE; see backend/core/target/failsafe-reports."
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:CITYCOPILOT_MIGRATION_TEST_URL = $previousMigrationUrl
}
