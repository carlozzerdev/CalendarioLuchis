package es.luchis.calendario

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LuciaApp() }
    }
}
private val spanish = Locale.forLanguageTag("es-ES")
private fun LocalDate.label() = format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM",spanish))
internal val stateNames = listOf("Sin Lucía","Solo mañana","Solo tarde","Todo el día")
internal val weekdays = listOf("Lunes","Martes","Miércoles","Jueves","Viernes","Sábado","Domingo")

@Composable fun LuciaApp() {
    val context=LocalContext.current
    val repo=remember { Repository.get(context) }
    val scope=rememberCoroutineScope()
    var data by remember { mutableStateOf<CalendarData?>(null) }
    var fatal by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<CalendarData?>(null) }
    val snackbar=remember { SnackbarHostState() }
    fun message(s: String) { scope.launch { snackbar.showSnackbar(s) } }
    fun save(next: CalendarData) {
        if(busy) return
        val previous=data ?: return
        busy=true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repo.save(next) }
                undo=previous; data=next
                withContext(Dispatchers.IO) { Reminders.schedule(context,previous,next) }
            } catch(e: Exception) { message(e.message ?: "No se pudo guardar") }
            finally { busy=false }
        }
    }
    LaunchedEffect(Unit) {
        try { data=withContext(Dispatchers.IO) { repo.read() }; Repository.schedule(context) }
        catch(e: Exception) { fatal="No se pueden abrir los datos. ${e.message}" }
    }
    val d=data
    val dark=when(d?.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=Color(0xFF9EC1FF)) else lightColorScheme(primary=Color(0xFF245AD4),background=Color(0xFFF6F7FB),surface=Color(0xFFF6F7FB))) {
        Surface(Modifier.fillMaxSize()) {
            if(d==null) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { if(fatal.isEmpty()) CircularProgressIndicator() else Text(fatal,Modifier.padding(24.dp)) }; return@Surface }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            var restore by remember { mutableStateOf<CalendarData?>(null) }
            var backupStatus by remember { mutableStateOf("") }
            val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(!it) message("Activa las notificaciones desde Configuración para recibir avisos") }
            fun requestNotifications() { if(Build.VERSION.SDK_INT>=33) permissions.launch(Manifest.permission.POST_NOTIFICATIONS) }
            val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) scope.launch {
                try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { it.write(repo.encode(d)) } }; message("Copia exportada") } catch(e: Exception) { message("No se pudo exportar: ${e.message}") }
            } }
            val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) scope.launch {
                try { restore=withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { input -> val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); var n=input.read(buffer); while(n!=-1) { require(out.size()+n<=10_000_000) { "Archivo demasiado grande" };out.write(buffer,0,n);n=input.read(buffer) }; repo.decode(out.toString("UTF-8")) } } } catch(e: Exception) { message("Copia inválida: ${e.message}") }
            } }
            val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri!=null) scope.launch {
                try { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); repo.prefs.edit().putString("folder",uri.toString()).apply(); backupStatus=withContext(Dispatchers.IO) { if(repo.backup()) "Primera copia guardada" else repo.prefs.getString("error","")!! } } catch(e: Exception) { message("No se pudo usar la carpeta: ${e.message}") }
            } }
            Scaffold(snackbarHost={ SnackbarHost(snackbar) }, bottomBar={ NavigationBar {
                listOf("Calendario","Normas","Configuración").forEachIndexed { i,title -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={ Icon(listOf(Icons.Outlined.CalendarMonth,Icons.Outlined.Rule,Icons.Outlined.Settings)[i],null) },label={Text(title)}) }
            } }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(undo!=null) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Cambio guardado",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium); TextButton(onClick={ val previous=undo!!; undo=null; save(previous) }) { Text("Deshacer") } }
                    when(tab) {
                        0 -> CalendarScreen(d,::save,::requestNotifications)
                        1 -> RulesScreen(d,::save)
                        2 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Heading("A tu manera","Configuración")
                            Text("Color de los días con Lucía",style=MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { listOf(0xFF2563EB,0xFF007F73,0xFF7C3AED,0xFFB54573).forEach { c -> Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color(c)).border(if(d.color==c) 3.dp else 0.dp,MaterialTheme.colorScheme.onSurface,RoundedCornerShape(16.dp)).clickable { save(d.copy(color=c)) }.semantics { contentDescription="Color ${c.toString(16)}${if(d.color==c) " seleccionado" else ""}" }) } }
                            Text("Aspecto",style=MaterialTheme.typography.titleMedium)
                            Choices(listOf("Sistema","Claro","Oscuro"),d.theme) { save(d.copy(theme=it)) }
                            Toggle("Abrir en agenda",d.agenda) { save(d.copy(agenda=it)) }
                            HorizontalDivider()
                            Text("Citas y avisos",style=MaterialTheme.typography.titleLarge)
                            OutlinedButton(onClick={ requestNotifications(); context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) }) { Text("Permisos de notificaciones") }
                            if(Build.VERSION.SDK_INT>=31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                                Text("Para avisar a la hora exacta, permite alarmas y recordatorios. Sin ese permiso Android puede retrasar los avisos.")
                                OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}")))}) { Text("Permitir avisos puntuales") }
                            }
                            HorizontalDivider()
                            Text("Tus copias",style=MaterialTheme.typography.titleLarge)
                            Text("Copias semanales locales. Se guardan las últimas 12. Android puede retrasarlas por ahorro de batería. Los archivos no están cifrados.")
                            Button(onClick={folder.launch(null)}) { Text("Elegir carpeta de copia semanal") }
                            OutlinedButton(onClick={ scope.launch { backupStatus=withContext(Dispatchers.IO) { if(repo.backup()) "Copia guardada" else repo.prefs.getString("error","")!! } } }) { Text("Hacer copia en esa carpeta ahora") }
                            Text("Última copia: ${repo.prefs.getString("last","Todavía ninguna")}")
                            if(backupStatus.isNotEmpty()) Text(backupStatus)
                            repo.prefs.getString("error",null)?.let { Text("Revisa las copias: $it",color=MaterialTheme.colorScheme.error) }
                            OutlinedButton(onClick={export.launch("lucia-${LocalDate.now()}.json")}) { Text("Exportar archivo para otro teléfono") }
                            OutlinedButton(onClick={import.launch(arrayOf("application/json","application/octet-stream","text/plain"))}) { Text("Restaurar desde un archivo") }
                            Text("En el teléfono nuevo importa el archivo y vuelve a elegir la carpeta y los permisos. No hay sincronización entre teléfonos.",style=MaterialTheme.typography.bodySmall)
                            Text("Calendario de Lucía · 1.0",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            restore?.let { incoming -> AlertDialog(onDismissRequest={restore=null},title={Text("Restaurar calendario")},text={Text("Se sustituirán los datos actuales por ${incoming.entries.size} citas/avisos, ${incoming.exceptions.size} cambios de día y ${incoming.holidays.size} periodos vacacionales. Antes se guardará una copia interna de los datos actuales.")},confirmButton={TextButton(onClick={ if(!busy) { busy=true; scope.launch { try { withContext(Dispatchers.IO) { repo.restore(incoming) }; data=incoming; undo=d; restore=null; withContext(Dispatchers.IO) { Reminders.schedule(context,d,incoming) }; message("Calendario restaurado") } catch(e: Exception) { message("No se pudo restaurar: ${e.message}") } finally { busy=false } } } }) {Text("Restaurar")}},dismissButton={TextButton(onClick={restore=null}) {Text("Cancelar")}}) }
        }
    }
}

@Composable internal fun Heading(kicker: String,title: String) { Text(kicker.uppercase(spanish),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary); Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold) }
@Composable internal fun Toggle(label: String,checked: Boolean,onChange:(Boolean)->Unit) { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(label,Modifier.weight(1f)); Switch(checked,onChange) } }
@Composable internal fun Choices(labels: List<String>,selected: Int,onSelect:(Int)->Unit) { Column { labels.forEachIndexed { i,s -> Row(Modifier.fillMaxWidth().clickable {onSelect(i)},verticalAlignment=Alignment.CenterVertically) { RadioButton(selected==i,{onSelect(i)}); Text(s) } } } }
@Composable internal fun Field(label: String,value: String,onChange:(String)->Unit) { OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true) }
@Composable internal fun DateField(label: String,value: String,onChange:(String)->Unit) {
    val c=LocalContext.current
    OutlinedButton(onClick={ val date=runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now()); android.app.DatePickerDialog(c,{_,y,m,d -> onChange(LocalDate.of(y,m+1,d).toString())},date.year,date.monthValue-1,date.dayOfMonth).show() },modifier=Modifier.fillMaxWidth()) { Text("$label: ${value.ifEmpty { "Elegir fecha" }}") }
}
@Composable internal fun Editor(title: String,onDismiss:()->Unit,onSave:()->Unit,content:@Composable ColumnScope.()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)},confirmButton={TextButton(onClick=onSave){Text("Guardar")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}})
}

@Composable private fun CalendarScreen(d:CalendarData,save:(CalendarData)->Unit,requestNotifications:()->Unit) {
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var agenda by rememberSaveable { mutableStateOf(d.agenda) }
    var editDay by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf<Entry?>(null) }
    var showEntries by remember { mutableStateOf(false) }
    val ym=YearMonth.parse(month); val date=LocalDate.parse(selected)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Heading("Tiempo juntos","Calendario de Lucía")
        if(!d.initialized) Card { Text("Empieza en Normas: elige el lunes inicial y la semana que comienza ese día.",Modifier.padding(16.dp)) }
        Row(verticalAlignment=Alignment.CenterVertically) { Text("${ym.month.getDisplayName(java.time.format.TextStyle.FULL,spanish).replaceFirstChar { it.uppercase() }} ${ym.year}",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge); TextButton(onClick={month=YearMonth.now().toString();selected=LocalDate.now().toString()}){Text("Hoy")} }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            IconButton(onClick={month=ym.minusMonths(1).toString()}) {Icon(Icons.Outlined.ChevronLeft,"Mes anterior")}
            TextButton(onClick={agenda=!agenda}) {Text(if(agenda) "Ver mes" else "Agenda")}
            IconButton(onClick={month=ym.plusMonths(1).toString()}) {Icon(Icons.Outlined.ChevronRight,"Mes siguiente")}
        }
        if(!agenda) {
            Row { listOf("L","M","X","J","V","S","D").forEach { Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {Text(it,style=MaterialTheme.typography.labelMedium)} } }
            val start=ym.atDay(1).minusDays((ym.atDay(1).dayOfWeek.value-1).toLong())
            val rows=(ym.lengthOfMonth()+ym.atDay(1).dayOfWeek.value-2)/7+1
            repeat(rows) { week -> Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) { repeat(7) { weekday ->
                val day=start.plusDays((week*7+weekday).toLong()); val state=CalendarEngine.day(d,day)
                val chosen=day==date; val today=day==LocalDate.now()
                val hasEntry=d.entries.any { it.date==day.toString() && !it.archived }
                Box(Modifier.weight(1f).heightIn(min=58.dp).clip(RoundedCornerShape(10.dp)).border(if(chosen) 2.dp else 1.dp,if(chosen) MaterialTheme.colorScheme.primary else Color(0xFFD2D8E2),RoundedCornerShape(10.dp)).clickable {selected=day.toString();editDay=true}.semantics {contentDescription="${day.label()}: ${stateNames[state.mask]}${if(hasEntry) ", con citas o avisos" else ""}"}) {
                    Column(Modifier.matchParentSize()) { repeat(2) { half -> Box(Modifier.weight(1f).fillMaxWidth().background(if(state.mask and (1 shl half)!=0) Color(d.color) else Color.White)) } }
                    // A neutral label keeps every day number legible with any custody colour.
                    Column(Modifier.align(Alignment.Center).background(Color(0xEEFFFFFF),RoundedCornerShape(6.dp)).padding(horizontal=4.dp),horizontalAlignment=Alignment.CenterHorizontally) { Text(day.dayOfMonth.toString(),color=if(day.month!=ym.month) Color.Gray else Color(0xFF15233D),fontWeight=if(today) FontWeight.ExtraBold else FontWeight.Normal); if(today || hasEntry) Text(if(hasEntry) "•" else "—",color=Color(0xFF245AD4),style=MaterialTheme.typography.labelSmall) }
                }
            } } }
            Text("Blanco: sin Lucía · Color: con Lucía\nMitad superior: mañana · Mitad inferior: tarde",style=MaterialTheme.typography.bodySmall)
        }
        d.entries.filter { it.notice && it.pinned && !it.archived }.forEach { e -> Card(onClick={entry=e},colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) { Row(Modifier.padding(16.dp)) { Icon(Icons.Outlined.PushPin,null); Spacer(Modifier.width(10.dp)); Text(e.title) } } }
        Row(verticalAlignment=Alignment.CenterVertically) { Text(if(agenda) "Agenda del mes" else date.label(),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium); TextButton(onClick={editDay=true}){Text("Editar día")} }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={entry=Entry(title="",date=selected)}){Text("+ Cita")}; OutlinedButton(onClick={entry=Entry(title="",notice=true)}){Text("+ Aviso")} }
        val entries=d.entries.filter { !it.archived && (if(agenda) it.date.startsWith(month) else it.date==selected) }.sortedBy {it.date+it.time}
        if(entries.isEmpty()) Text("Sin citas para ${if(agenda) "este mes" else "este día"}.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        entries.forEach { e -> EntryCard(e) {entry=e} }
        TextButton(onClick={showEntries=true}){Text("Todas las citas y avisos")}
    }
    if(editDay) DayEditor(d,date,{editDay=false},save)
    entry?.let { e -> EntryEditor(e,{entry=null},{updated -> if(updated.reminder>=0) requestNotifications(); save(d.copy(entries=d.entries.filter {it.id!=updated.id}+updated));entry=null},{save(d.copy(entries=d.entries.filter {it.id!=e.id}));entry=null}) }
    if(showEntries) AlertDialog(onDismissRequest={showEntries=false},title={Text("Citas y avisos")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) { if(d.entries.isEmpty()) Text("Todavía no hay citas ni avisos"); d.entries.sortedBy {it.date+it.time}.forEach { e -> EntryCard(e){showEntries=false;entry=e} } }},confirmButton={TextButton(onClick={showEntries=false}){Text("Cerrar")}})
}
@Composable private fun EntryCard(e:Entry,onClick:()->Unit) { OutlinedCard(onClick=onClick,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(e.title,style=MaterialTheme.typography.titleMedium); Text(if(e.archived) "Archivado" else if(e.date.isEmpty()) "Aviso fijado" else "${e.date} · ${e.time}${if(e.place.isNotBlank()) " · ${e.place}" else ""}",style=MaterialTheme.typography.bodySmall) } } }

@Composable private fun DayEditor(d:CalendarData,date:LocalDate,dismiss:()->Unit,save:(CalendarData)->Unit) {
    var mask by remember { mutableIntStateOf(CalendarEngine.day(d,date).mask) }
    var end by remember { mutableStateOf(date.toString()) }
    var error by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    val state=CalendarEngine.day(d,date)
    Editor(if(preview) "Confirmar cambio" else date.label(),dismiss,{
        if(preview) {
            val map=d.exceptions.toMutableMap(); var day=date; val last=LocalDate.parse(end)
            while(day<=last) {map[day.toString()]=mask;day=day.plusDays(1)}
            save(d.copy(exceptions=map));dismiss()
        } else { if(LocalDate.parse(end)<date) error="El final es anterior al inicio" else if(java.time.temporal.ChronoUnit.DAYS.between(date,LocalDate.parse(end))>3660) error="Selecciona como máximo diez años" else preview=true }
    }) {
        if(preview) Text("Del $date al $end: ${stateNames[mask]}. Se guardará como excepción y se mantendrá la alternancia semanal.") else {
            Text("Mañana: ${state.morningSource}\nTarde: ${state.afternoonSource}",style=MaterialTheme.typography.bodySmall)
            Choices(stateNames,mask){mask=it}
            DateField("Aplicar hasta",end){end=it}
            TextButton(onClick={ val map=d.exceptions.toMutableMap(); var day=date; val last=LocalDate.parse(end); if(last<date) {error="Revisa el intervalo"} else {while(day<=last){map.remove(day.toString());day=day.plusDays(1)};save(d.copy(exceptions=map));dismiss()} }) {Text("Volver a las normas en estos días")}
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun EntryEditor(original:Entry,dismiss:()->Unit,save:(Entry)->Unit,delete:()->Unit) {
    var e by remember { mutableStateOf(original) }; var error by remember { mutableStateOf("") }; var confirmDelete by remember { mutableStateOf(false) }
    Editor(if(e.notice) "Aviso / recordatorio" else "Cita",dismiss,{ try { CalendarEngine.validate(CalendarData(entries=listOf(e))); require(e.notice || e.date.isNotBlank());save(e) } catch(ex:Exception){error="Revisa título, fecha, hora y duración"} }) {
        Field("Título",e.title){e=e.copy(title=it)}
        DateField(if(e.notice) "Fecha opcional del aviso" else "Fecha",e.date){e=e.copy(date=it)}
        if(e.notice && e.date.isNotEmpty()) TextButton(onClick={e=e.copy(date="",reminder=-1)}){Text("Quitar fecha")}
        if(e.date.isNotEmpty()) {
            val c=LocalContext.current
            OutlinedButton(onClick={val t=LocalTime.parse(e.time);android.app.TimePickerDialog(c,{_,h,m-> e=e.copy(time=String.format(Locale.ROOT,"%02d:%02d",h,m))},t.hour,t.minute,true).show()}){Text("Hora: ${e.time}")}
            Text("Notificación")
            val minutes=listOf(-1,0,10,30,60,1440)
            Choices(listOf("Sin aviso","A la hora","10 minutos antes","30 minutos antes","1 hora antes","1 día antes"),minutes.indexOf(e.reminder)){e=e.copy(reminder=minutes[it])}
        }
        if(!e.notice) {Field("Duración en minutos (opcional)",e.duration){e=e.copy(duration=it)};Field("Lugar",e.place){e=e.copy(place=it)}}
        Field("Notas",e.notes){e=e.copy(notes=it)}
        if(e.notice) Toggle("Fijar en calendario",e.pinned){e=e.copy(pinned=it)}
        Toggle("Archivado",e.archived){e=e.copy(archived=it)}
        if(original.title.isNotBlank()) TextButton(onClick={confirmDelete=true}){Text("Eliminar",color=MaterialTheme.colorScheme.error)}
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("¿Eliminar este elemento?")},confirmButton={TextButton(onClick=delete){Text("Eliminar")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("Cancelar")}})
}
