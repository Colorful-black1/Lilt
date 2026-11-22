#Requires -Version 5.1
<#
.SYNOPSIS
    Build and package Lilt.

.DESCRIPTION
    pc   Build the desktop app and create dist/Lilt-<version>-win64/ plus a portable zip.
    apk  Build the Android debug APK.
    all  Do both.

.EXAMPLE
    .\package.ps1 pc

.EXAMPLE
    .\package.ps1 apk
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet("pc", "apk", "all")]
    [string]$Target
)

$ErrorActionPreference = "Stop"
$repo = $PSScriptRoot

function Invoke-PcPackage {
    Push-Location (Join-Path $repo "base/app")
    try {
        Write-Host "==> Building desktop app (release)" -ForegroundColor Cyan
        cargo build --release
        if ($LASTEXITCODE -ne 0) { throw "cargo build --release failed" }

        $cargoToml = Get-Content "Cargo.toml" -Raw
        $version = [regex]::Match($cargoToml, '(?m)^version\s*=\s*"([^"]+)"').Groups[1].Value
        if (-not $version) { throw "Could not read version from Cargo.toml" }

        $name = "Lilt-$version-win64"
        $dist = Join-Path $repo "dist"
        $stage = Join-Path $dist $name
        $zip = Join-Path $dist "$name-portable.zip"

        Write-Host "==> Staging $stage" -ForegroundColor Cyan
        Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Path $stage -Force | Out-Null

        Copy-Item "target\release\lilt.exe" $stage -Force
        Copy-Item "README.md" $stage -Force
        Copy-Item "RUN-zh.txt" $stage -Force
        Copy-Item "Starter Music" $stage -Recurse -Force

        Write-Host "==> Creating $zip" -ForegroundColor Cyan
        Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip -Force

        $zipInfo = Get-Item $zip
        Write-Host ("PC package: {0} ({1:N1} MB)" -f $zipInfo.FullName, ($zipInfo.Length / 1MB)) -ForegroundColor Green
    }
    finally {
        Pop-Location
    }
}

function Invoke-ApkPackage {
    Push-Location (Join-Path $repo "base/app/android")
    try {
        Write-Host "==> Building Android debug APK" -ForegroundColor Cyan
        .\gradlew.bat assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradlew assembleDebug failed" }

        $apk = Join-Path (Get-Location) "app\build\outputs\apk\debug\app-debug.apk"
        if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

        $gradle = Get-Content "app\build.gradle.kts" -Raw
        $versionName = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
        if (-not $versionName) { $versionName = "1.0" }

        $copy = Join-Path $repo "base/app/Lilt-$versionName-debug.apk"
        Copy-Item $apk $copy -Force

        $apkInfo = Get-Item $apk
        Write-Host ("APK: {0} ({1:N1} MB)" -f $apkInfo.FullName, ($apkInfo.Length / 1MB)) -ForegroundColor Green
        Write-Host ("Copy: {0}" -f $copy) -ForegroundColor Green
    }
    finally {
        Pop-Location
    }
}

if (-not $Target) {
    Write-Host "Usage: .\package.ps1 <pc|apk|all>" -ForegroundColor Yellow
    Write-Host "  pc   desktop portable package -> dist/"
    Write-Host "  apk  Android debug APK         -> base/app/android/app/build/outputs/apk/debug/"
    Write-Host "  all  build both"
    exit 1
}

switch ($Target) {
    "pc"  { Invoke-PcPackage }
    "apk" { Invoke-ApkPackage }
    "all" { Invoke-PcPackage; Invoke-ApkPackage }
}
