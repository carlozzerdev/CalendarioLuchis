[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function Invoke-PhoneAdb {
    param([string[]]$AdbArguments)

    # Capture native stderr and inspect the exit code ourselves in both PowerShell versions.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $output = & $adb -d @AdbArguments 2>&1
    $exitCode = $LASTEXITCODE
    [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    }
}

function Stop-PhoneDeploy {
    param([string]$Step, [string]$Advice, $Result)

    $details = if ($Result.Output) { $Result.Output } else { '(ADB no devolvio detalles)' }
    throw "$Step`n$Advice`n`nSalida de ADB (codigo $($Result.ExitCode)):`n$details"
}

$apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "No se encontro el APK en $apk. Ejecuta .\Compilar.ps1 -Offline y vuelve a intentarlo."
}

$adb = Join-Path $PSScriptRoot '.gradle/toolchain/sdk/platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    $adb = (Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (-not $adb) {
        throw "No se encuentra adb.exe en el SDK local ni en PATH. Instala Android SDK Platform-Tools o recupera la carpeta .gradle/toolchain/sdk/platform-tools."
    }
}

Write-Host 'Comprobando el telefono conectado por USB...'
$state = Invoke-PhoneAdb -AdbArguments @('get-state')
if ($state.ExitCode -ne 0 -or $state.Output -notmatch '(?m)^device\s*$') {
    $advice = switch -Regex ($state.Output) {
        'unauthorized' { 'Desbloquea el telefono y acepta el mensaje Permitir depuracion por USB. Si no aparece, desconecta y vuelve a conectar el cable.'; break }
        'offline' { 'ADB detecta el telefono, pero no responde. Desbloquealo, vuelve a conectar el USB y espera a que aparezca como device.'; break }
        'more than one' { 'Desconecta los demas telefonos USB y deja conectado solo el que quieres usar.'; break }
        'failed to start daemon|cannot connect to daemon|cannot bind' { 'El servidor ADB no pudo arrancar o conectarse. Cierra otras herramientas ADB y vuelve a intentarlo desde PowerShell.'; break }
        default { 'Comprueba que la depuracion USB esta activada y prueba un cable de datos u otro puerto USB. Si Windows no reconoce el telefono, comprueba el controlador USB del fabricante.' }
    }
    Stop-PhoneDeploy 'No se pudo conectar con el telefono por USB.' $advice $state
}

Write-Host 'Instalando el APK en el telefono...'
$install = Invoke-PhoneAdb -AdbArguments @('install', '-r', $apk)
if ($install.ExitCode -ne 0 -or $install.Output -notmatch '(?m)^Success\s*$') {
    $advice = switch -Regex ($install.Output) {
        'UPDATE_INCOMPATIBLE|signatures do not match' { 'La app instalada tiene otra firma. Usa un APK firmado con la misma clave. Si necesitas desinstalar, exporta antes una copia para conservar tus datos.'; break }
        'VERSION_DOWNGRADE' { 'El telefono tiene una version mas reciente. Compila un APK con un versionCode igual o superior al instalado.'; break }
        'INSUFFICIENT_STORAGE' { 'Libera espacio en el telefono y vuelve a intentar la instalacion.'; break }
        'USER_RESTRICTED|INSTALL_CANCELED_BY_USER|INSTALL_FAILED_ABORTED' { 'Desbloquea el telefono y revisa si pide confirmar la instalacion. Algunos fabricantes tienen un ajuste separado para instalar por USB.'; break }
        'OLDER_SDK' { 'Este APK requiere Android 8.0 o posterior (API 26).'; break }
        'offline|device.*not found|no devices' { 'Se perdio la conexion USB durante la instalacion. Reconecta el telefono y vuelve a intentarlo.'; break }
        default { 'Revisa el detalle de ADB. Si el APK esta corrupto o incompleto, vuelve a compilarlo con .\Compilar.ps1 -Offline.' }
    }
    Stop-PhoneDeploy 'No se pudo instalar Calendario de hijos.' $advice $install
}

Write-Host 'Abriendo Calendario de hijos...'
$launch = Invoke-PhoneAdb -AdbArguments @('shell', 'am', 'start', '-W', '-n', 'es.luchis.calendario/.MainActivity')
if ($launch.ExitCode -ne 0 -or $launch.Output -match '(?im)^(Error(?:\s+type\s+\d+)?\b|Status:\s+(?!ok\b))') {
    $advice = 'El APK se instalo. Desbloquea el telefono e intenta abrir la app desde el lanzador. Si ADB indica que no encuentra la actividad, comprueba que el APK corresponde a este proyecto.'
    Stop-PhoneDeploy 'No se pudo abrir Calendario de hijos.' $advice $launch
}
Write-Host 'Calendario de hijos instalado y abierto en el telefono.' -ForegroundColor Green
