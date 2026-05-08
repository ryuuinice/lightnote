param(
    [string]$DataDir = $env:LIGHTNOTE_DATA_DIR
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$clientDir = Join-Path $root "lightnote-client"

if (-not [string]::IsNullOrWhiteSpace($DataDir)) {
    $env:LIGHTNOTE_DATA_DIR = $DataDir
}

Set-Location $clientDir
mvn javafx:run
