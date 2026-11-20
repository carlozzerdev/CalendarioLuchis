[CmdletBinding()]
param([switch]$Offline)

$ErrorActionPreference = 'Stop'
$toolchain = Join-Path $PSScriptRoot '.gradle/toolchain'
$localJdk = Get-ChildItem -LiteralPath $toolchain -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
    Select-Object -First 1
if ($localJdk) {
    $env:JAVA_HOME = $localJdk.FullName
}

$localSdk = Join-Path $toolchain 'sdk'
if (Test-Path -LiteralPath (Join-Path $localSdk 'platforms/android-35/android.jar')) {
    $env:ANDROID_HOME = $localSdk
    $env:ANDROID_USER_HOME = Join-Path $localSdk '.android'
}

$localGradleHome = Join-Path $PSScriptRoot '.gradle/user-home'
if (Test-Path -LiteralPath $localGradleHome) {
    $env:GRADLE_USER_HOME = $localGradleHome
}

$gradleArgs = @(':app:assembleDebug', '--console=plain')
if ($Offline) {
    $gradleArgs += '--offline'
}

Push-Location -LiteralPath $PSScriptRoot
try {
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle fallo con el codigo $LASTEXITCODE."
    }

    $apk = Get-Item -LiteralPath 'app/build/outputs/apk/debug/app-debug.apk'
    Write-Host "APK generado: $($apk.FullName)" -ForegroundColor Green
} finally {
    Pop-Location
}
