$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

Push-Location (Join-Path $root "lightnote-server")
try {
    mvn package
} finally {
    Pop-Location
}

Push-Location (Join-Path $root "lightnote-client")
try {
    mvn package
} finally {
    Pop-Location
}
