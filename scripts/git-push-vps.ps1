param(
    [string]$Message = "",
    [string]$Remote = "vps",
    [string]$Branch = "",
    [string]$RemoteUrl = "ssh://root@203.0.113.10/git-workspace/lightnote-dev"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if ([string]::IsNullOrWhiteSpace($Branch)) {
        $Branch = (& git branch --show-current).Trim()
        if ([string]::IsNullOrWhiteSpace($Branch)) {
            throw "无法识别当前分支，请先切到一个普通 Git 分支。"
        }
    }

    $remoteExists = $false
    & git remote get-url $Remote *> $null
    if ($LASTEXITCODE -eq 0) {
        $remoteExists = $true
    }
    if (-not $remoteExists) {
        & git remote add $Remote $RemoteUrl
        if ($LASTEXITCODE -ne 0) {
            throw "添加远端失败: $Remote -> $RemoteUrl"
        }
    }

    & git add -A
    if ($LASTEXITCODE -ne 0) {
        throw "git add 失败。"
    }

    $hasChanges = -not [string]::IsNullOrWhiteSpace((& git status --porcelain))
    if ($hasChanges) {
        if ([string]::IsNullOrWhiteSpace($Message)) {
            $Message = "chore: update lightnote workspace"
        }
        & git commit -m $Message
        if ($LASTEXITCODE -ne 0) {
            throw "git commit 失败。"
        }
    } else {
        Write-Host "没有需要提交的本地改动。"
    }

    & git push $Remote $Branch
    if ($LASTEXITCODE -ne 0) {
        throw "git push 失败: $Remote $Branch"
    }

    $head = (& git rev-parse --short HEAD).Trim()
    Write-Host ""
    Write-Host "提交并推送完成：" -ForegroundColor Green
    Write-Host "  Remote: $Remote"
    Write-Host "  Branch: $Branch"
    Write-Host "  HEAD: $head"
} finally {
    Pop-Location
}
