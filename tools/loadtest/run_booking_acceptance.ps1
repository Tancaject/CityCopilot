param(
    [Parameter(Mandatory = $true)]
    [string]$JdbcUrl,
    [string]$DatabaseUser = 'root'
)

$ErrorActionPreference = 'Stop'
$bookingDatabaseUri = [Uri]($JdbcUrl -replace '^jdbc:', '')
if (-not $JdbcUrl.StartsWith('jdbc:mysql://') -or $bookingDatabaseUri.AbsolutePath -notmatch '^/[A-Za-z0-9_]+_test$') {
    throw '预约验收会清理测试数据。请提供独立 MySQL 数据库，名称必须以 _test 结尾。'
}

$bookingRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$bookingMaven = Join-Path $bookingRepo '.tools/apache-maven-3.9.9/bin/mvn.cmd'
if (-not (Test-Path -LiteralPath $bookingMaven)) {
    $bookingMaven = (Get-Command mvn -ErrorAction Stop).Source
}

$bookingPreviousUrl = $env:CITYCOPILOT_BOOKING_TEST_DB_URL
$bookingPreviousUser = $env:CITYCOPILOT_BOOKING_TEST_DB_USER
try {
    $env:CITYCOPILOT_BOOKING_TEST_DB_URL = $JdbcUrl
    $env:CITYCOPILOT_BOOKING_TEST_DB_USER = $DatabaseUser
    # Password is inherited from CITYCOPILOT_BOOKING_TEST_DB_PASSWORD; never write it to logs or command arguments.
    Push-Location $bookingRepo
    try {
        & $bookingMaven -q -pl backend/commerce-service -am `
            '-Dtest=BookingPriceCalculatorTest' '-Dsurefire.failIfNoSpecifiedTests=false' `
            '-Dit.test=Booking*IT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
        if ($LASTEXITCODE -ne 0) { throw "预约验收失败，Maven 退出码 $LASTEXITCODE；请查看 commerce-service/target 下的测试报告。" }
    } finally {
        Pop-Location
    }
} finally {
    $env:CITYCOPILOT_BOOKING_TEST_DB_URL = $bookingPreviousUrl
    $env:CITYCOPILOT_BOOKING_TEST_DB_USER = $bookingPreviousUser
}
