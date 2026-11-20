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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private val calendarColors = listOf(
    "Azul" to DEFAULT_CALENDAR_COLOR,
    "Verde esmeralda" to 0xFF00B86B,
    "Amarillo" to 0xFFFFD500,
    "Naranja" to 0xFFFF7900,
    "Rojo" to 0xFFEF233C,
    "Rosa fucsia" to 0xFFDB2777,
    "Violeta" to 0xFF7C3AED
)
private val CalendarData.displayColor: Long
    get() = when (color) {
        0xFF007F73, 0xFF2563EB -> DEFAULT_CALENDAR_COLOR
        0xFFB54573 -> 0xFFDB2777
        else -> color
    }
private fun LocalDate.label() = format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM",spanish))
internal val periodNames = listOf("Mañana", "Tarde", "Noche")
internal val stateNames = listOf(
    "Sin hijos", "Solo mañana", "Solo tarde", "Mañana y tarde",
    "Solo noche", "Mañana y noche", "Tarde y noche", "Todo el día"
)
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
            Scaffold(contentWindowInsets = WindowInsets.safeDrawing, snackbarHost={ SnackbarHost(snackbar) }, bottomBar={ NavigationBar(
                modifier = Modifier.windowInsetsPadding(NavigationBarDefaults.windowInsets).height(64.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                val tabs = listOf(
                    Triple(0, "Calendario", Icons.Outlined.CalendarMonth),
                    Triple(3, "Citas", Icons.Outlined.Event),
                    Triple(1, "Normas", Icons.Outlined.Rule),
                    Triple(2, "Ajustes", Icons.Outlined.Settings)
                )
                tabs.forEach { (id, title, icon) ->
                    NavigationBarItem(selected = tab == id, onClick = { tab = id }, icon = { Icon(icon, null) }, label = { Text(title) })
                }
            } }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(undo!=null) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Cambio guardado",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium); TextButton(onClick={ val previous=undo!!; undo=null; save(previous) }) { Text("Deshacer") } }
                    when(tab) {
                        0 -> CalendarScreen(d,::save,::requestNotifications)
                        1 -> RulesScreen(d,::save)
                        3 -> AppointmentsScreen(d.entries) { id -> save(d.copy(entries = d.entries.filterNot { it.id == id })) }
                        2 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Heading("A tu manera","Configuración")
                            Text("Color de los días con hijos",style=MaterialTheme.typography.titleMedium)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                calendarColors.chunked(4).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { (label, color) ->
                                            val selected = d.displayColor == color
                                            val shape = RoundedCornerShape(16.dp)
                                            Box(
                                                Modifier.size(48.dp).clip(shape).background(Color(color))
                                                    .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, shape)
                                                    .clickable { save(d.copy(color = color)) }
                                                    .semantics { contentDescription = if (selected) "$label seleccionado" else label }
                                            )
                                        }
                                    }
                                }
                            }
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
                            OutlinedButton(onClick={export.launch("hijos-${LocalDate.now()}.json")}) { Text("Exportar archivo para otro teléfono") }
                            OutlinedButton(onClick={import.launch(arrayOf("application/json","application/octet-stream","text/plain"))}) { Text("Restaurar desde un archivo") }
                            Text("En el teléfono nuevo importa el archivo y vuelve a elegir la carpeta y los permisos. No hay sincronización entre teléfonos.",style=MaterialTheme.typography.bodySmall)
                            Text("Calendario de hijos · 1.0",style=MaterialTheme.typography.labelSmall)
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
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var agenda by rememberSaveable { mutableStateOf(d.agenda) }
    var showDay by rememberSaveable { mutableStateOf(false) }
    var editDay by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf<Entry?>(null) }
    var listNotices by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val today = LocalDate.now()
    val ym = YearMonth.parse(month)
    val date = selected?.let(LocalDate::parse) ?: today
    val first = ym.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val rows = (ym.lengthOfMonth() + first.dayOfWeek.value - 2) / 7 + 1
    val byTime = compareBy<EntryOccurrence> { it.entry.time.isEmpty() }.thenBy { it.entry.time }
    val entriesByDay = remember(d.entries, ym) {
        d.entries.asSequence().filterNot { it.archived }
            .flatMap { it.occurrences(start, start.plusDays(rows * 7L - 1)) }
            .sortedWith(byTime)
            .groupBy { it.date }
    }
    val dayEntries = remember(d.entries, entriesByDay, date, start, rows) {
        if (date in start..start.plusDays(rows * 7L - 1)) entriesByDay[date].orEmpty()
        else d.entries.asSequence().filterNot { it.archived }.flatMap { it.occurrences(date, date) }
            .sortedWith(byTime).toList()
    }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val accent = if (dark) Color(0xFFFFB58A) else Color(0xFFAE4713)
    val custodyColor = Color(d.displayColor)
    val header = lerp(colors.surface, Color(0xFF67C6F0), if (dark) 0.18f else 0.21f)
    val cardColor = if (dark) colors.surfaceContainer else Color.White
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(Modifier.fillMaxWidth().background(header).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = ym.minusMonths(1).toString() }) {
                    Icon(Icons.Outlined.ChevronLeft, "Mes anterior", Modifier.background(cardColor, RoundedCornerShape(9.dp)).padding(4.dp))
                }
                Text(
                    ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish)).replaceFirstChar { it.uppercase() },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { month = ym.plusMonths(1).toString() }) {
                    Icon(Icons.Outlined.ChevronRight, "Mes siguiente", Modifier.background(cardColor, RoundedCornerShape(9.dp)).padding(4.dp))
                }
                IconButton(onClick = { listNotices = false }) {
                    Icon(Icons.Outlined.Add, "Citas", Modifier.background(accent, RoundedCornerShape(9.dp)).padding(5.dp), tint = if (dark) Color(0xFF351405) else Color.White)
                }
            }
            if (!agenda) {
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(custodyColor, RoundedCornerShape(3.dp)))
                        Text("Con hijos", style = MaterialTheme.typography.labelMedium)
                    }
                    Text("M mañana · T tarde · N noche", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    EntryKind.entries.forEach { kind ->
                        val width = if (kind == EntryKind.REMINDER) 1.4f else 1f
                        Row(Modifier.weight(width), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            EntryDot(kind, 10.dp, showMark = true)
                            Text(kind.plural, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if(!agenda) {
            val weekdayLabels = listOf("L", "M", "X", "J", "V", "S", "D")
            Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                weekdayLabels.forEachIndexed { index, label -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = if (index >= 5) accent else colors.onSurfaceVariant)
                } }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val gridSpacing = 3.dp
                val cardHeight = ((maxHeight - 104.dp - gridSpacing * (rows - 1)) / rows).coerceIn(50.dp, 56.dp)
                val cardWidth = (maxWidth - 12.dp - gridSpacing * 6) / 7
                val periodSize = ((cardWidth - 3.dp) / 3).coerceAtMost(17.dp)
                val eventRowWidth = cardWidth - 5.dp
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(gridSpacing)
                ) {
                    repeat(rows) { week ->
                        Row(Modifier.height(cardHeight), horizontalArrangement = Arrangement.spacedBy(gridSpacing)) {
                            repeat(7) { weekday ->
                                val day = start.plusDays((week * 7 + weekday).toLong())
                                val inMonth = YearMonth.from(day) == ym
                                CalendarDayCard(
                                    day = day,
                                    mask = if (inMonth) CalendarEngine.day(d, day).mask else 0,
                                    custodyColor = custodyColor,
                                    cardColor = cardColor,
                                    inMonth = inMonth,
                                    selected = selected != null && day == date,
                                    today = day == today,
                                    periodSize = periodSize,
                                    eventRowWidth = eventRowWidth,
                                    entryKinds = if (inMonth) entriesByDay[day].orEmpty().map { it.entry.kind }.toSet() else emptySet(),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                ) {
                                    month = YearMonth.from(day).toString()
                                    selected = day.toString()
                                    showDay = true
                                }
                            }
                        }
                    }
                    SelectedDayEvents(date, today, dayEntries) { entry = it }
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Agenda del mes", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { agenda = false }) { Text("Ver mes") }
                }
                val entries = entriesByDay.toSortedMap().filterKeys { YearMonth.from(it) == ym }.values.flatten()
                if (entries.isEmpty()) Text("No hay elementos este mes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.forEach { occurrence -> EntryCard(occurrence.entry, occurrence.date) { entry = occurrence.entry } }
            }
        }
        if (!d.initialized && d.exceptions.isEmpty()) {
            Text("Configura el lunes inicial en Normas.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if(editDay) DayEditor(d,date,{editDay=false},save)
    entry?.let { e -> EntryEditor(e,{entry=null},{updated -> if(updated.reminder>=0) requestNotifications(); save(d.copy(entries=d.entries.filter {it.id!=updated.id}+updated));entry=null},{save(d.copy(entries=d.entries.filter {it.id!=e.id}));entry=null}) }
    if (showDay && entry == null && !editDay) {
        DayDetailsSheet(
            date = date,
            mask = CalendarEngine.day(d, date).mask,
            custodyColor = custodyColor,
            entries = dayEntries,
            onDismissRequest = { showDay = false },
            onOpenEntry = { entry = it },
            onAddEntry = { kind ->
                entry = Entry(
                    title = "",
                    date = date.toString(),
                    exam = kind == EntryKind.EXAM,
                    activity = kind == EntryKind.ACTIVITY,
                    notice = kind == EntryKind.REMINDER
                )
            },
            onEditCustody = { editDay = true }
        )
    }
    listNotices?.let { notices ->
        val kind = if (notices) EntryKind.REMINDER else EntryKind.APPOINTMENT
        val entries = d.entries.filter { it.kind == kind }.sortedWith(
            compareBy<Entry> { it.archived }.thenByDescending { it.notice && it.pinned }.thenBy { it.date + it.time }
        )
        AlertDialog(
            onDismissRequest = { listNotices = null },
            title = { Text(if (notices) "Recordatorios" else "Citas") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!notices && !agenda) {
                        TextButton(onClick = { listNotices = null; agenda = true }) { Text("Ver agenda del mes") }
                    }
                    if (!notices) {
                        TextButton(onClick = { listNotices = true }) { Text("Ver recordatorios") }
                    }
                    if (!notices) {
                        TextButton(onClick = {
                            listNotices = null
                            month = YearMonth.from(today).toString()
                            selected = null
                        }) { Text("Ir a hoy") }
                    }
                    if (entries.isEmpty()) Text(if (notices) "Todavía no hay recordatorios." else "Todavía no hay citas.")
                    entries.forEach { e -> EntryCard(e) { listNotices = null; entry = e } }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    listNotices = null
                    entry = Entry(title = "", date = if (notices) "" else date.toString(), notice = notices)
                }) { Text(if (notices) "Nuevo recordatorio" else "Nueva cita") }
            },
            dismissButton = { TextButton(onClick = { listNotices = null }) { Text("Cerrar") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailsSheet(
    date: LocalDate,
    mask: Int,
    custodyColor: Color,
    entries: List<EntryOccurrence>,
    onDismissRequest: () -> Unit,
    onOpenEntry: (Entry) -> Unit,
    onAddEntry: (EntryKind) -> Unit,
    onEditCustody: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val dateFormat = remember { DateTimeFormatter.ofPattern("d 'de' MMMM", spanish) }
    val custodyTint = lerp(colors.surface, custodyColor, 0.15f)
    val custodyText = if (custodyColor.luminance() > 0.179f) Color.Black else Color.White
    val custodyLabel = when (mask) {
        0 -> stateNames[0]
        7 -> "Con hijos · Todo el día"
        else -> "Con hijos · ${stateNames[mask]}"
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, spanish).uppercase(spanish),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(date.format(dateFormat), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(date.year.toString(), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = onDismissRequest) { Icon(Icons.Outlined.Close, "Cerrar detalle del día") }
            }
            Row(
                Modifier.fillMaxWidth().background(custodyTint, RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.People, null, tint = colors.primary)
                Text(custodyLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            val periodIcons = listOf(Icons.Outlined.WbTwilight, Icons.Outlined.LightMode, Icons.Outlined.Bedtime)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                periodNames.forEachIndexed { index, label ->
                    val active = mask and (1 shl index) != 0
                    val labelColor = if (active) custodyText else colors.onSurfaceVariant
                    Row(
                        Modifier.weight(1f).heightIn(min = 40.dp)
                            .background(if (active) custodyColor else colors.surfaceContainerHigh)
                            .semantics { contentDescription = "$label, ${if (active) "con hijos" else "sin hijos"}" }
                            .padding(horizontal = 2.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                    ) {
                        Icon(periodIcons[index], null, Modifier.size(15.dp), tint = labelColor)
                        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
                    }
                }
            }
            Text("Agenda", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (entries.isEmpty()) {
                Text("No hay elementos para este día.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            entries.forEach { occurrence ->
                val item = occurrence.entry
                val accent = entryColor(item.kind)
                val shape = RoundedCornerShape(11.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(accent)
                        .padding(start = 5.dp).background(lerp(colors.surface, accent, 0.05f))
                        .border(1.dp, lerp(colors.surface, accent, 0.58f), RoundedCornerShape(topEnd = 11.dp, bottomEnd = 11.dp))
                        .clickable { onOpenEntry(item) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.time.ifEmpty { "Sin hora" }, Modifier.widthIn(min = 54.dp),
                        style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.Bold)
                    VerticalDivider(Modifier.padding(horizontal = 9.dp).height(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.kind.label.uppercase(spanish), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("Añadir al día", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            EntryKind.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { kind ->
                        val accent = entryColor(kind)
                        val shape = RoundedCornerShape(11.dp)
                        val icon = when (kind) {
                            EntryKind.APPOINTMENT -> Icons.Outlined.CalendarMonth
                            EntryKind.EXAM -> Icons.Outlined.Description
                            EntryKind.ACTIVITY -> Icons.Outlined.DirectionsRun
                            EntryKind.REMINDER -> Icons.Outlined.NotificationsNone
                        }
                        Row(
                            Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                                .background(lerp(colors.surface, accent, 0.12f))
                                .border(1.dp, lerp(colors.surface, accent, 0.27f), shape)
                                .clickable(role = Role.Button) { onAddEntry(kind) }.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, null, Modifier.size(20.dp), tint = accent)
                            Text(kind.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            TextButton(onClick = onEditCustody) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Editar custodia")
            }
        }
    }
}

@Composable
private fun SelectedDayEvents(date: LocalDate, today: LocalDate, entries: List<EntryOccurrence>, onOpenEntry: (Entry) -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(if (date == today) "Eventos de hoy" else "Eventos del ${date.label()}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (entries.isEmpty()) {
                Text("No hay eventos para este día.", style = MaterialTheme.typography.bodySmall)
            }
            entries.forEach { (item, _) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 26.dp).clickable { onOpenEntry(item) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(item.time.ifEmpty { "Sin hora" }, Modifier.widthIn(min = 48.dp),
                        color = colors.primary, style = MaterialTheme.typography.labelMedium)
                    EntryDot(item.kind, 11.dp, showMark = true)
                    Text(
                        item.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCard(
    day: LocalDate,
    mask: Int,
    custodyColor: Color,
    cardColor: Color,
    inMonth: Boolean,
    selected: Boolean,
    today: Boolean,
    periodSize: Dp,
    eventRowWidth: Dp,
    entryKinds: Set<EntryKind>,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val morning = mask and 1 != 0
    val afternoon = mask and 2 != 0
    val night = mask and 4 != 0
    val shape = RoundedCornerShape(8.dp)
    val kinds = EntryKind.entries.filter { it in entryKinds }
    val dark = cardColor.luminance() < 0.5f
    val highlight = if (dark) colors.primary else Color(0xFF206C9B)
    val dateColor = when {
        inMonth -> colors.onSurface
        else -> colors.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val background = when {
        today && inMonth -> lerp(cardColor, highlight, 0.18f)
        !inMonth -> lerp(cardColor, colors.surfaceContainerHigh, 0.32f)
        mask != 0 -> lerp(cardColor, custodyColor, 0.08f)
        else -> cardColor
    }
    val border = if (selected && inMonth) highlight else lerp(cardColor, highlight, 0.18f)
    Column(
        modifier
            .clip(shape)
            .background(background)
            .border(if (selected && inMonth) 2.dp else 1.dp, border, shape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${day.label()}. Mañana: ${if (morning) "con hijos" else "sin hijos"}. " +
                    "Tarde: ${if (afternoon) "con hijos" else "sin hijos"}. " +
                    "Noche: ${if (night) "con hijos" else "sin hijos"}" +
                    (if (today) ". Hoy" else "") +
                    (if (kinds.isNotEmpty()) kinds.joinToString(prefix = ". ") { it.plural } else "")
            }
            .padding(horizontal = 1.dp, vertical = 1.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(15.dp).padding(start = 2.dp).clearAndSetSemantics {},
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = dateColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (mask != 0) {
                Row(
                    Modifier.fillMaxWidth().clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(0.5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CalendarPeriod("M", morning, custodyColor, periodSize)
                    CalendarPeriod("T", afternoon, custodyColor, periodSize)
                    CalendarPeriod("N", night, custodyColor, periodSize)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(16.dp).padding(end = 3.dp, bottom = 2.dp).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(0.5.dp, Alignment.End),
            verticalAlignment = Alignment.Bottom
        ) {
            if (kinds.isNotEmpty()) {
                val dotSize = ((eventRowWidth - 1.dp * (kinds.size - 1)) / kinds.size).coerceAtMost(14.dp)
                kinds.forEach { EntryDot(it, dotSize, showMark = true) }
            }
        }
    }
}

@Composable
private fun CalendarPeriod(label: String, active: Boolean, custodyColor: Color, size: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).background(
            if (active) custodyColor else colors.surfaceContainerHigh,
            RoundedCornerShape(3.dp)
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = when {
                !active -> colors.onSurfaceVariant
                custodyColor.luminance() > 0.179f -> Color.Black
                else -> Color.White
            },
            fontSize = 11.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun entryColor(kind: EntryKind, showMark: Boolean = false): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (kind) {
        EntryKind.APPOINTMENT -> if (showMark) Color(0xFFB54D09) else if (dark) Color(0xFFFFB080) else Color(0xFFCE601C)
        EntryKind.EXAM -> if (dark && !showMark) Color(0xFFD7A9FF) else Color(0xFF8D4BBC)
        EntryKind.ACTIVITY -> if (showMark) Color(0xFF00745B) else if (dark) Color(0xFF6CE3BC) else Color(0xFF00886A)
        EntryKind.REMINDER -> if (showMark) Color(0xFF846000) else if (dark) Color(0xFFF6CF60) else Color(0xFF9D7200)
    }
}

@Composable
private fun EntryDot(kind: EntryKind, size: Dp, muted: Boolean = false, showMark: Boolean = false) {
    val fill = if (muted) MaterialTheme.colorScheme.outline else entryColor(kind, showMark)
    Box(
        Modifier.size(size).background(fill, RoundedCornerShape(100)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        if (showMark) {
            Text(
                kind.mark,
                color = Color.White,
                fontSize = if (size >= 14.dp) 10.sp else 8.sp,
                lineHeight = if (size >= 14.dp) 11.sp else 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun EntryCard(e: Entry, occurrenceDate: LocalDate? = null, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                EntryDot(e.kind, 6.dp)
                Text(e.kind.label, style = MaterialTheme.typography.labelSmall)
            }
            Text(e.title, style = MaterialTheme.typography.titleMedium)
            val detail = when {
                e.archived -> "Archivado"
                e.date.isEmpty() -> "Sin fecha"
                else -> listOf(
                    occurrenceDate?.toString() ?: e.date,
                    e.time.ifEmpty { "Sin hora" },
                    e.place,
                    if (e.repeatWeekly) "Cada semana" else ""
                ).filter { it.isNotBlank() }.joinToString(" · ")
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppointmentsScreen(entries: List<Entry>, onDelete: (String) -> Unit) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000)
        }
    }
    val today = now.toLocalDate()
    val itemsByDate = remember(entries, today) {
        entries.mapNotNull { item ->
            val date = item.nextDate(today)
            if (date != null || item.date.isEmpty()) item to date else null
        }.sortedWith(
            compareBy<Pair<Entry, LocalDate?>> { it.second == null }
                .thenBy { it.second }
                .thenBy { it.first.time.isEmpty() }
                .thenBy { it.first.time }
        )
    }
    var pendingDelete by remember { mutableStateOf<Entry?>(null) }
    val colors = MaterialTheme.colorScheme

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Citas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (itemsByDate.isEmpty()) {
            item { Text("No hay citas, exámenes, actividades ni recordatorios.", color = colors.onSurfaceVariant) }
        }
        items(itemsByDate, key = { it.first.id }) { (entry, occurrenceDate) ->
            val passed = occurrenceDate == today && entry.time.isNotEmpty() &&
                LocalTime.parse(entry.time).isBefore(now.toLocalTime())
            val muted = passed || entry.archived
            val textColor = if (muted) colors.onSurfaceVariant else colors.onSurface
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (muted) colors.surfaceContainerHigh else colors.surface
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            EntryDot(entry.kind, 6.dp, muted = muted)
                            Text(entry.kind.label, style = MaterialTheme.typography.labelSmall, color = textColor)
                        }
                        Text(entry.title, style = MaterialTheme.typography.titleMedium, color = textColor)
                        val dateLabel = if (occurrenceDate == null) "Sin fecha"
                            else "${occurrenceDate.label().replaceFirstChar { it.uppercase() }} · ${entry.time.ifEmpty { "Sin hora" }}"
                        Text(dateLabel, style = MaterialTheme.typography.bodyMedium, color = textColor)
                        if (entry.place.isNotBlank()) {
                            Text(entry.place, style = MaterialTheme.typography.bodySmall, color = textColor)
                        }
                        if (entry.repeatWeekly) Text("Cada semana", style = MaterialTheme.typography.labelSmall, color = textColor)
                        if (muted) {
                            val status = listOfNotNull("Ya pasó".takeIf { passed }, "En el archivo".takeIf { entry.archived })
                            Text(status.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = textColor)
                        }
                    }
                    IconButton(onClick = { pendingDelete = entry }) {
                        Icon(Icons.Outlined.DeleteOutline, "Eliminar ${entry.title}", tint = textColor)
                    }
                }
            }
        }
    }

    pendingDelete?.let { appointment ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (appointment.repeatWeekly) "¿Eliminar la serie semanal?" else "¿Eliminar este elemento?") },
            text = { Text(appointment.title) },
            confirmButton = {
                TextButton(onClick = { onDelete(appointment.id); pendingDelete = null }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } }
        )
    }
}

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
            Text("Mañana: ${state.morningSource}\nTarde: ${state.afternoonSource}\nNoche: ${state.nightSource}",style=MaterialTheme.typography.bodySmall)
            periodNames.forEachIndexed { index, name ->
                val bit = 1 shl index
                Toggle(name, mask and bit != 0) { mask = mask xor bit }
            }
            DateField("Aplicar hasta",end){end=it}
            TextButton(onClick={ val map=d.exceptions.toMutableMap(); var day=date; val last=LocalDate.parse(end); if(last<date) {error="Revisa el intervalo"} else {while(day<=last){map.remove(day.toString());day=day.plusDays(1)};save(d.copy(exceptions=map));dismiss()} }) {Text("Volver a las normas en estos días")}
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun EntryEditor(original:Entry,dismiss:()->Unit,save:(Entry)->Unit,delete:()->Unit) {
    var e by remember { mutableStateOf(original) }; var error by remember { mutableStateOf("") }; var confirmDelete by remember { mutableStateOf(false) }
    Editor(e.kind.label,dismiss,{ try { CalendarEngine.validate(CalendarData(entries=listOf(e))); require(e.notice || e.date.isNotBlank());save(e) } catch(ex:Exception){error="Revisa título, fecha, hora y duración"} }) {
        Field("Título",e.title){e=e.copy(title=it)}
        DateField(if(e.notice) "Fecha opcional del recordatorio" else "Fecha",e.date){e=e.copy(date=it)}
        if(e.notice && e.date.isNotEmpty()) TextButton(onClick={e=e.copy(date="",reminder=-1,repeatWeekly=false)}){Text("Quitar fecha")}
        if(e.date.isNotEmpty()) {
            val c=LocalContext.current
            OutlinedButton(onClick={
                val time = LocalTime.parse(e.time.ifEmpty { "10:30" })
                android.app.TimePickerDialog(c, { _, hour, minute ->
                    e = e.copy(time = String.format(Locale.ROOT, "%02d:%02d", hour, minute))
                }, time.hour, time.minute, true).show()
            }) { Text(if (e.time.isEmpty()) "Añadir hora (opcional)" else "Hora: ${e.time}") }
            if (e.time.isNotEmpty()) {
                TextButton(onClick = { e = e.copy(time = "", reminder = -1) }) { Text("Quitar hora") }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = e.repeatWeekly,
                    role = Role.Checkbox,
                    onValueChange = { e = e.copy(repeatWeekly = it) }
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(e.repeatWeekly, onCheckedChange = null)
                Text("Repetir cada semana", Modifier.padding(start = 8.dp))
            }
        }
        if(e.date.isNotEmpty() && e.time.isNotEmpty()) {
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
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text(if (original.repeatWeekly) "¿Eliminar la serie semanal?" else "¿Eliminar este elemento?")},confirmButton={TextButton(onClick=delete){Text("Eliminar")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("Cancelar")}})
}
