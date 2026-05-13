param(
    [switch]$SkipBuild,
    [switch]$SkipValidation
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$clientDir = Join-Path $root "lightnote-client"
$targetDir = Join-Path $clientDir "target"
$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$packagingDir = Join-Path $targetDir ("jpackage\" + $runId)
$inputDir = Join-Path $packagingDir "input"
$libDir = Join-Path $inputDir "libs"
$dependencyDir = Join-Path $packagingDir "dependencies"
$javafxModuleDir = Join-Path $packagingDir "javafx-modules"
$distDir = Join-Path $targetDir ("jpackage-dist\" + $runId)

[xml]$pom = Get-Content (Join-Path $clientDir "pom.xml")
$version = $pom.project.version
$mainJar = "lightnote-client-$version.jar"
$appName = "LightNote"

function Invoke-Maven([string[]]$arguments) {
    Push-Location $clientDir
    try {
        & mvn @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Maven 执行失败: mvn $($arguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Test-DebugLauncher([string]$launcherPath) {
    $existingPids = @(Get-Process java,javaw,LightNote -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
    $process = Start-Process -FilePath $launcherPath -PassThru
    Start-Sleep -Seconds 8
    $newProcesses = Get-Process java,javaw,LightNote -ErrorAction SilentlyContinue |
        Where-Object { $_.Id -notin $existingPids }
    if (-not $newProcesses) {
        throw "jpackage 调试启动器未能持续启动，请检查 $launcherPath"
    }
    $newProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
}

if (-not $SkipBuild) {
    Invoke-Maven @("package")
}

New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
New-Item -ItemType Directory -Path $libDir -Force | Out-Null
New-Item -ItemType Directory -Path $dependencyDir -Force | Out-Null
New-Item -ItemType Directory -Path $javafxModuleDir -Force | Out-Null
New-Item -ItemType Directory -Path $distDir -Force | Out-Null

Invoke-Maven @(
    "dependency:copy-dependencies",
    "-DincludeScope=runtime",
    "-DoutputDirectory=$dependencyDir"
)

$jarPath = Join-Path $targetDir $mainJar
if (-not (Test-Path $jarPath)) {
    throw "未找到客户端主 Jar: $jarPath"
}
Copy-Item -LiteralPath $jarPath -Destination $inputDir -Force

$javafxJars = Get-ChildItem -Path $dependencyDir -Filter "javafx-*-win.jar" -File
if ($javafxJars.Count -eq 0) {
    throw "未找到 JavaFX Windows 模块 Jar，无法继续打包。"
}
$javafxJars | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $javafxModuleDir -Force
    Copy-Item -LiteralPath $_.FullName -Destination $inputDir -Force
}
Get-ChildItem -Path $dependencyDir -File | Where-Object { $_.Name -notmatch '^javafx-.*\.jar$' } | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $libDir -Force
}

$jpackageArgs = @(
    "--type", "app-image",
    "--win-console",
    "--dest", $distDir,
    "--input", $inputDir,
    "--name", $appName,
    "--main-jar", $mainJar,
    "--main-class", "com.lightnote.client.LightNoteClientLauncher",
    "--module-path", $javafxModuleDir,
    "--add-modules", "javafx.controls,javafx.web",
    "--java-options", "--add-modules=javafx.controls,javafx.web",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Dprism.lcdtext=false",
    "--vendor", "OpenAI Codex",
    "--app-version", ($version -replace "-SNAPSHOT$", "")
)

& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) {
    throw "jpackage 执行失败。"
}

$appImageDir = Join-Path $distDir $appName
$cfgPath = Join-Path $appImageDir "app\LightNote.cfg"
if (Test-Path $cfgPath) {
    $cfgLines = Get-Content $cfgPath
    $cfgLines = $cfgLines | Where-Object {
        $_ -ne 'java-options=--module-path=$APPDIR\libs' -and
        $_ -ne 'java-options=--add-modules=javafx.controls,javafx.web'
    }
    Set-Content -Path $cfgPath -Value $cfgLines -Encoding UTF8
}

$debugLauncherPath = Join-Path $appImageDir "$appName-debug.cmd"
$debugLauncherContent = @"
@echo off
setlocal
set "APP_HOME=%~dp0"
java -Dfile.encoding=UTF-8 -Dprism.lcdtext=false -cp "%APP_HOME%app\*;%APP_HOME%app\libs\*" com.lightnote.client.LightNoteClientLauncher
endlocal
pause
"@
Set-Content -Path $debugLauncherPath -Value $debugLauncherContent -Encoding ASCII

if (-not $SkipValidation) {
    Test-DebugLauncher $debugLauncherPath
}

Start-Sleep -Milliseconds 500
Get-ChildItem -Path $appImageDir -Filter "RCX*.tmp" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue
$leftoverTempFiles = Get-ChildItem -Path $appImageDir -Filter "RCX*.tmp" -File -ErrorAction SilentlyContinue
if ($leftoverTempFiles) {
    Write-Warning "jpackage 留下了 RCX 临时文件，通常是 Windows 资源编辑器访问拒绝导致，可稍后手动删除。"
}

Write-Host ""
Write-Host "jpackage 客户端打包完成：" -ForegroundColor Green
Write-Host "  App Image: $appImageDir"
Write-Host "  Launcher: $(Join-Path $appImageDir "$appName.exe")"
