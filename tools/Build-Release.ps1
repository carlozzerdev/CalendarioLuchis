$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$signing = Join-Path $project '.signing'
New-Item -ItemType Directory -Force $signing | Out-Null
$store = Join-Path $signing 'lucia.jks'
$properties = Join-Path $signing 'signing.properties'
$passwordFile = Join-Path $signing 'password.txt'
if (-not (Test-Path -LiteralPath $store)) {
    if (Test-Path -LiteralPath $properties) { throw 'Existe configuración sin clave: recupera la clave original antes de continuar.' }
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $password = [Convert]::ToBase64String($bytes)
    Set-Content -LiteralPath $passwordFile -Value $password -Encoding ascii
    try {
        & keytool -genkeypair -keystore $store -storetype JKS -alias lucia -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Calendario de Lucia' '-storepass:file' $passwordFile '-keypass:file' $passwordFile
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar la clave de firma.' }
        Set-Content -LiteralPath $properties -Value "password=$password" -Encoding ascii
    } finally { Remove-Item -LiteralPath $passwordFile -ErrorAction SilentlyContinue }
}
if (-not (Test-Path -LiteralPath $properties)) { throw 'Falta signing.properties. Recupera su copia junto a la clave.' }
Push-Location $project
try {
    & .\gradlew.bat :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'La compilación falló. Consulta el error de Gradle.' }
    Write-Output "APK firmado: $project\app\build\outputs\apk\release\app-release.apk"
    Write-Output 'Conserva una copia privada de .signing para las futuras actualizaciones.'
} finally { Pop-Location }
