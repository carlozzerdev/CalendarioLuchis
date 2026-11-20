[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Puerto = 5555,
    [ValidateRange(1, 600)]
    [int]$Espera = 120
)

$ErrorActionPreference = 'Stop'
$apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw 'Falta el APK. Ejecuta primero Compilar.ps1.'
}

$adb = Join-Path $PSScriptRoot '.gradle/toolchain/sdk/platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    $adb = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
    if (-not $adb) {
        throw 'No se encuentra adb.exe. Instala Android SDK Platform-Tools.'
    }
}

if (-not (Get-Process -Name 'HD-Player' -ErrorAction SilentlyContinue)) {
    $player = Join-Path $env:ProgramFiles 'BlueStacks_nxt/HD-Player.exe'
    if (-not (Test-Path -LiteralPath $player -PathType Leaf)) {
        throw 'Abre BlueStacks 5 desde el menu Inicio y vuelve a ejecutar Emular.ps1.'
    }
    Write-Host 'Abriendo BlueStacks 5...'
    Start-Process -FilePath $player
}

$device = "127.0.0.1:$Puerto"
Write-Host "Esperando a BlueStacks en $device..."
$timer = [Diagnostics.Stopwatch]::StartNew()
$booted = $false
while ($timer.Elapsed.TotalSeconds -lt $Espera) {
    & $adb connect $device | Out-Null
    $devices = & $adb devices
    if ($devices -match "^$([regex]::Escape($device))\s+device\s*$") {
        $bootState = & $adb -s $device shell getprop sys.boot_completed
        if ($LASTEXITCODE -eq 0 -and $bootState -eq '1') {
            $booted = $true
            break
        }
    }
    Start-Sleep -Seconds 2
}
if (-not $booted) {
    throw "BlueStacks no esta listo en $device. Activa Android Debug Bridge en Ajustes > Avanzado y usa -Puerto con el puerto que indique BlueStacks."
}

Write-Host 'Instalando el APK en BlueStacks...'
& $adb -s $device install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw 'ADB no pudo instalar el APK. Revisa el mensaje anterior.'
}

& $adb -s $device shell am start -W -n 'es.luchis.calendario/.MainActivity'
if ($LASTEXITCODE -ne 0) {
    throw 'ADB no pudo abrir Calendario de hijos.'
}
Write-Host 'Calendario de hijos instalado y abierto.' -ForegroundColor Green
