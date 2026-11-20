# Calendario de hijos

Aplicación Android personal, en español, sin cuenta ni servidor. Código Kotlin, interfaz Compose Material 3, base de datos Room, copias con WorkManager y avisos con AlarmManager.

## Estado de esta entrega

APK de desarrollo generado correctamente el 18 de noviembre de 2026: `app/build/outputs/apk/debug/app-debug.apk`. La ejecución de `assembleDebug`, `testDebugUnitTest` y `lintDebug` terminó con **BUILD SUCCESSFUL**. La batería de 35 comprobaciones del motor y del formato de copias pasó dentro de la prueba JUnit, sin fallos ni errores. Lint terminó con advertencias. Quedan pendientes instalación, revisión visual y pruebas de notificaciones y copias en un dispositivo o emulador.

## Compilar

Requisitos: JDK 17, Android SDK 35 y conexión a Google Maven, Maven Central y Gradle la primera vez. Abrir la carpeta en Android Studio y dejar sincronizar Gradle. Alternativamente, configurar `ANDROID_HOME` y ejecutar en PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK de desarrollo: `app/build/outputs/apk/debug/app-debug.apk`. Se instala con `adb install -r app/build/outputs/apk/debug/app-debug.apk` o trasladando el archivo al teléfono y autorizando la instalación desde esa fuente.

El APK de desarrollo usa la firma de depuración del ordenador. Para una entrega personal estable, usar `tools/Build-Release.ps1`: crea una clave privada local persistente y compila la versión release. Conservar `.signing/` en lugar seguro; es imprescindible para futuras actualizaciones sin desinstalar. Nunca añadir esa carpeta a control de versiones.

## Uso

1. En **Normas**, elegir un lunes inicial y si comienza una semana con hijos. Las plantillas iniciales reflejan los intercambios del lunes a mediodía y las excepciones de jueves/viernes o miércoles/jueves.
2. En **Calendario**, los bloques M, T y N muestran la convivencia por la mañana, la tarde y la noche, de izquierda a derecha. Toca un día para ver sus citas o editar la convivencia de ese día o de un intervalo.
3. Los cambios manuales prevalecen sobre las vacaciones, y estas sobre las semanas. El detalle indica la procedencia de cada tramo.
4. Para cambiar el vaivén, añadir un lunes de reinicio en Normas. Su mañana conserva el ciclo anterior; la tarde inicia la nueva alternancia. Se pueden editar y eliminar reinicios posteriores al primero.
5. Semana Santa y otros periodos se introducen por fechas y tramos de inicio y fin. Después vuelve el ciclo anterior hasta el lunes de reinicio elegido. Las vacaciones explícitas pueden sustituir una parte del verano.
6. Verano por defecto: años pares, segundas quincenas de julio y agosto; impares, primeras quincenas. Los cuatro intervalos de cada paridad son editables.
7. Toca un día para gestionar citas, exámenes, actividades y recordatorios. Puedes dejar la hora en blanco y marcar «Repetir cada semana» para mostrar el elemento los mismos días de la semana a partir de la fecha elegida.
8. Los avisos previos son opcionales. Android 13+ requiere permiso de notificaciones. Android 12+ permite habilitar alarmas exactas desde Configuración; sin ese permiso las notificaciones pueden retrasarse. Se reprograman tras reinicio, cambio de hora/zona y actualización.

## Copias y recuperación

En Configuración, elegir una carpeta local con el selector de Android. Se crea una primera copia y se programa una cada siete días; Android decide el momento efectivo. Se conservan doce copias automáticas identificadas con `lucia-auto-`; las exportaciones manuales no se eliminan. La pantalla muestra última copia y errores.

Los archivos JSON contienen todos los datos y no están cifrados. Para cambiar de teléfono, exportar, trasladar el archivo e importarlo desde Configuración. Se muestra un resumen antes de sustituir los datos. Una copia inválida no cambia la base de datos. Después hay que elegir nuevamente la carpeta y conceder permisos.

Al abrir datos antiguos o importar una copia de dos tramos, la mañana se conserva y la antigua tarde pasa a cubrir tanto la tarde como la noche.

Antes de restaurar se guarda el estado anterior en el almacenamiento interno privado (`antes-de-restaurar.json`). También se puede deshacer la restauración mientras la aplicación permanezca abierta. Para conservar una copia anterior accesible a largo plazo, exportarla antes de restaurar. No existe sincronización ni recuperación remota si se pierde el teléfono y las copias permanecían solo en él.

## Comprobaciones

`tools/Test-Core.ps1` ejecuta escenarios del motor y del formato de copia con el compilador Kotlin y Gson de la caché de Gradle, sin descargar Android. La misma batería se incorpora a `testDebugUnitTest`.

Incluye semanas completas, fechas anteriores al ancla, cambio de año, años bisiestos, reinicios, verano par/impar, vacaciones parciales, prioridades, versiones históricas, procedencia, validación, exportación/importación con acentos y rechazo de archivos corruptos/incompatibles.

Antes de usar con datos reales, completar en dispositivo: pantalla pequeña y letra ampliada; modos claro/oscuro; edición de intervalos; notificaciones con permisos concedidos/denegados y tras reinicio; exportación/restauración entre teléfonos; carpeta revocada y copia semanal. No se han ejecutado estas pruebas en este entorno.

## Arquitectura

- `CalendarEngine`: cálculo por tramos y reglas históricas, independiente de Android.
- `BackupCodec`: formato JSON versionado y validado, independiente de Room.
- `Repository`: instantánea validada en una entidad Room; cada guardado es una sustitución atómica.
- Pantallas Compose con escritura en IO, controles de fecha/hora nativos y vista previa antes de aplicar reglas.
- `BackupWorker` y receptores de alarmas: trabajo persistente y notificaciones locales sin servidor.

No incluye integración con Google Calendar ni publicación en tienda.
