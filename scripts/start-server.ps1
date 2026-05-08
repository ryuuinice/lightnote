param(
    [string]$DbUsername = $env:LIGHTNOTE_DB_USERNAME,
    [string]$DbPassword = $env:LIGHTNOTE_DB_PASSWORD,
    [string]$DbUrl = $env:LIGHTNOTE_DB_URL,
    [string]$Port = "8080"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $root "lightnote-server"

if ([string]::IsNullOrWhiteSpace($DbUsername)) {
    $DbUsername = Read-Host "MariaDB username"
}

if ([string]::IsNullOrWhiteSpace($DbPassword)) {
    $secure = Read-Host "MariaDB password" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $DbPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

if ([string]::IsNullOrWhiteSpace($DbUrl)) {
    $DbUrl = "jdbc:mariadb://10.10.5.57:3306/lightnote?useUnicode=true&characterEncoding=utf8"
}

$env:LIGHTNOTE_DB_USERNAME = $DbUsername
$env:LIGHTNOTE_DB_PASSWORD = $DbPassword
$env:LIGHTNOTE_DB_URL = $DbUrl
$env:LIGHTNOTE_SERVER_PORT = $Port

Set-Location $serverDir
mvn spring-boot:run
