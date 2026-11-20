$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$gradleLib = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
if (-not $gradleLib) { throw 'Se necesita una distribución Gradle con el compilador Kotlin en la caché.' }
$compilerClasspath = Join-Path $gradleLib '*'
$jars = (Get-ChildItem $gradleLib -Filter '*.jar' | Where-Object Name -Match '^(kotlin-stdlib|annotations-)' | Select-Object -ExpandProperty FullName) -join [IO.Path]::PathSeparator
$gson = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Filter 'gson-*.jar' -Recurse | Select-Object -Last 1 -ExpandProperty FullName
if (-not $gson) { throw 'Se necesita Gson en la caché Gradle' }
$jars += [IO.Path]::PathSeparator + $gson
$output = Join-Path $project 'build/core-checks'
New-Item -ItemType Directory -Force $output | Out-Null
& java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $jars -d $output (Join-Path $project 'app/src/main/java/es/luchis/calendario/CalendarModel.kt') (Join-Path $project 'app/src/main/java/es/luchis/calendario/BackupCodec.kt') (Join-Path $PSScriptRoot 'CoreChecks.kt')
if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación del motor' }
& java -cp "$output$([IO.Path]::PathSeparator)$jars" es.luchis.calendario.CoreChecksKt
if ($LASTEXITCODE -ne 0) { throw 'Fallaron las comprobaciones' }
