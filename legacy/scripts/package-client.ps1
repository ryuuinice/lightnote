param(
    [switch]$SkipBuild,
    [switch]$SkipValidation
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$clientDir = Join-Path $root "lightnote-client"
$targetDir = Join-Path $clientDir "target"
$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$packagingDir = Join-Path $targetDir ("packaging\" + $runId)
$inputDir = Join-Path $packagingDir "input"
$libDir = Join-Path $inputDir "libs"
$javafxModuleDir = Join-Path $packagingDir "javafx-modules"
$distDir = Join-Path $targetDir ("dist\" + $runId)

[xml]$pom = Get-Content (Join-Path $clientDir "pom.xml")
$version = $pom.project.version
$mainJar = "lightnote-client-$version.jar"
$appName = "LightNote"
$portableZip = Join-Path $distDir "$appName-windows-x64-portable.zip"

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

function Test-PackagedApp([string]$exePath) {
    $existingPids = @(Get-Process LightNote,java,javaw -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
    $process = Start-Process -FilePath $exePath -PassThru
    Start-Sleep -Seconds 8
    $newProcesses = Get-Process LightNote,java,javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.Id -notin $existingPids }
    if (-not $newProcesses) {
        throw "打包后的客户端未能持续启动，请检查 $exePath"
    }
    $newProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
}

if (-not $SkipBuild) {
    Invoke-Maven @("package")
}

Get-Process LightNote,java,javaw -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500
New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
New-Item -ItemType Directory -Path $javafxModuleDir -Force | Out-Null
New-Item -ItemType Directory -Path $distDir -Force | Out-Null

Invoke-Maven @(
    "dependency:copy-dependencies",
    "-DincludeScope=runtime",
    "-DoutputDirectory=$libDir"
)

$jarPath = Join-Path $targetDir $mainJar
if (-not (Test-Path $jarPath)) {
    throw "未找到客户端主 Jar: $jarPath"
}
Copy-Item -LiteralPath $jarPath -Destination $inputDir -Force

$javafxJars = Get-ChildItem -Path $libDir -Filter "javafx-*-win.jar" -File
if ($javafxJars.Count -eq 0) {
    throw "未找到 JavaFX Windows 模块 Jar，无法继续打包。"
}
$javafxJars | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $javafxModuleDir -Force
}

$appImageDir = Join-Path $distDir $appName
$runtimeDir = Join-Path $appImageDir "runtime"
$appFilesDir = Join-Path $appImageDir "app"
$classpathDir = Join-Path $appFilesDir "classpath"
$javafxDir = Join-Path $appFilesDir "javafx"

New-Item -ItemType Directory -Path $appFilesDir -Force | Out-Null
New-Item -ItemType Directory -Path $classpathDir -Force | Out-Null
New-Item -ItemType Directory -Path $javafxDir -Force | Out-Null

Copy-Item -LiteralPath $jarPath -Destination $appFilesDir -Force
Get-ChildItem -File $libDir | ForEach-Object {
    if ($_.Name -match '^javafx-.*-win\.jar$') {
        Copy-Item -LiteralPath $_.FullName -Destination $javafxDir -Force
    } elseif ($_.Name -notmatch '^javafx-.*\.jar$') {
        Copy-Item -LiteralPath $_.FullName -Destination $classpathDir -Force
    }
}

$jlinkBin = (Get-Command jlink).Source
$jdkHome = Split-Path (Split-Path $jlinkBin -Parent) -Parent
$jmodsDir = Join-Path $jdkHome "jmods"
$jlinkModulePath = "$jmodsDir;$javafxModuleDir"
$jlinkModules = "java.se,jdk.crypto.ec,jdk.jsobject,jdk.unsupported,jdk.unsupported.desktop,javafx.controls,javafx.web"

& jlink `
    "--module-path" $jlinkModulePath `
    "--add-modules" $jlinkModules `
    "--output" $runtimeDir `
    "--strip-debug" `
    "--compress=2" `
    "--no-header-files" `
    "--no-man-pages"
if ($LASTEXITCODE -ne 0) {
    throw "jlink 执行失败。"
}

$launcherPath = Join-Path $appImageDir "$appName.cmd"
$debugLauncherPath = Join-Path $appImageDir "$appName-debug.cmd"
$launcherContent = @"
@echo off
setlocal
set "APP_HOME=%~dp0"
"%APP_HOME%runtime\bin\javaw.exe" --module-path "%APP_HOME%app\javafx" --add-modules javafx.controls,javafx.web -Dfile.encoding=UTF-8 -Dprism.lcdtext=false -cp "%APP_HOME%app\$mainJar;%APP_HOME%app\classpath\*" com.lightnote.client.LightNoteClientLauncher
endlocal
"@
$debugLauncherContent = @"
@echo off
setlocal
set "APP_HOME=%~dp0"
"%APP_HOME%runtime\bin\java.exe" --module-path "%APP_HOME%app\javafx" --add-modules javafx.controls,javafx.web -Dfile.encoding=UTF-8 -Dprism.lcdtext=false -cp "%APP_HOME%app\$mainJar;%APP_HOME%app\classpath\*" com.lightnote.client.LightNoteClientLauncher
endlocal
"@
Set-Content -Path $launcherPath -Value $launcherContent -Encoding ASCII
Set-Content -Path $debugLauncherPath -Value $debugLauncherContent -Encoding ASCII

if (Test-Path $portableZip) {
    Remove-Item -LiteralPath $portableZip -Force
}
Compress-Archive -Path $appImageDir -DestinationPath $portableZip

if (-not $SkipValidation) {
    Test-PackagedApp $launcherPath
}

Write-Host ""
Write-Host "Windows 客户端打包完成：" -ForegroundColor Green
Write-Host "  App Image: $distDir\\$appName"
Write-Host "  Portable Zip: $portableZip"
