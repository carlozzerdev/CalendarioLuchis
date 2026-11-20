package es.luchis.calendario

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.*

@Composable fun RulesScreen(d:CalendarData,save:(CalendarData)->Unit) {
    var restart by remember { mutableStateOf<Restart?>(null) }
    var templates by remember { mutableStateOf<Templates?>(null) }
    var holiday by remember { mutableStateOf<Holiday?>(null) }
    var summer by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Heading("Un calendario que se adapta","Normas generales")
        Text("Los cambios manuales tienen prioridad sobre vacaciones; las vacaciones, sobre las semanas.")
        Text("Alternancia de semanas",style=MaterialTheme.typography.titleLarge)
        Button(onClick={ val today=LocalDate.now(); restart=Restart(today.minusDays((today.dayOfWeek.value-1).toLong()).toString(),true) }) {Text(if(d.initialized) "Cambiar alternancia desde un lunes" else "Elegir lunes inicial")}
        d.restarts.sortedBy {it.monday}.forEach { r -> OutlinedCard(onClick={restart=r},modifier=Modifier.fillMaxWidth()) {Text("${r.monday} por la tarde\nComienza semana ${if(r.together) "con" else "sin"} hijos",Modifier.padding(16.dp))} }
        Text("Las mañanas de los lunes de reinicio conservan el ciclo anterior. Toca un reinicio para editarlo.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Mañana, tarde y noche de cada semana",style=MaterialTheme.typography.titleLarge)
        Button(onClick={templates=(d.templates.maxByOrNull {it.since} ?: Templates()).copy(since=LocalDate.now().toString())}) {Text("Cambiar plantillas semanales")}
        d.templates.sortedByDescending {it.since}.forEach { t -> TextButton(onClick={templates=t}) {Text(if(t.since=="1900-01-01") "Plantillas iniciales" else "Plantillas desde ${t.since}")} }
        HorizontalDivider()
        Text("Vacaciones",style=MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick={summer=true}) {Text("Quincenas de verano")}
        Text("Semana Santa y otros periodos se definen por fechas. Para volver con otra alternancia, añade un reinicio en un lunes posterior.")
        OutlinedButton(onClick={holiday=Holiday(title="Semana Santa",start=LocalDate.now().toString(),end=LocalDate.now().toString())}) {Text("+ Periodo de vacaciones")}
        d.holidays.sortedBy {it.start}.forEach { h -> OutlinedCard(onClick={holiday=h},modifier=Modifier.fillMaxWidth()) {Text("${h.title}\n${h.start} — ${h.end}",Modifier.padding(16.dp))} }
    }
    restart?.let { original ->
        var monday by remember(original) { mutableStateOf(original.monday) }; var together by remember(original) { mutableStateOf(original.together) }; var error by remember { mutableStateOf("") }; var preview by remember { mutableStateOf<CalendarData?>(null) }
        Editor(if(d.initialized) "Reiniciar alternancia" else "Lunes de referencia",{restart=null},{ try {
            require(LocalDate.parse(monday).dayOfWeek.value==1) {"Elige un lunes"}
            val next=d.copy(initialized=true,restarts=d.restarts.filter {it.monday!=original.monday && it.monday!=monday}+Restart(monday,together))
            CalendarEngine.validate(next);preview=next
        } catch(e:Exception){error=e.message ?: "Revisa la fecha"} }) {
            DateField("Lunes",monday){monday=it}
            Toggle("Comienza semana con hijos",together){together=it}
            Text("Se aplica a partir del lunes por la tarde. Las vacaciones y los cambios manuales se conservan.")
            if(d.restarts.contains(original) && d.restarts.size>1) TextButton(onClick={save(d.copy(restarts=d.restarts-original));restart=null}){Text("Eliminar este reinicio")}
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }
        preview?.let { next -> Preview(next,LocalDate.parse(monday),{preview=null},{save(next);restart=null;preview=null}) }
    }
    templates?.let { original ->
        var since by remember(original) { mutableStateOf(original.since) }; var away by remember(original) { mutableStateOf(original.away) }; var together by remember(original) { mutableStateOf(original.together) }; var error by remember { mutableStateOf("") }; var preview by remember { mutableStateOf<CalendarData?>(null) }
        Editor("Plantillas semanales",{templates=null},{try {val next=d.copy(templates=d.templates.filter {it.since!=since}+Templates(since,away,together));CalendarEngine.validate(next);preview=next} catch(e:Exception){error=e.message ?: "Revisa las fechas"} }) {
            DateField("Aplicar desde",since){since=it}
            Text("Marca los tramos con hijos en cada día.")
            Text("Semana sin hijos",style=MaterialTheme.typography.titleMedium)
            TemplateGrid(away){away=it}
            Text("Semana con hijos",style=MaterialTheme.typography.titleMedium)
            TemplateGrid(together){together=it}
            if(original.since!="1900-01-01" && d.templates.contains(original)) TextButton(onClick={save(d.copy(templates=d.templates-original));templates=null}){Text("Eliminar esta versión")}
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }
        preview?.let {next -> Preview(next,LocalDate.parse(since),{preview=null},{save(next);templates=null;preview=null})}
    }
    holiday?.let { original ->
        var h by remember(original) { mutableStateOf(original) }; var error by remember { mutableStateOf("") }; var preview by remember { mutableStateOf<CalendarData?>(null) }
        Editor("Periodo de vacaciones",{holiday=null},{try {val next=d.copy(holidays=d.holidays.filter {it.id!=h.id}+h);CalendarEngine.validate(next);preview=next} catch(e:Exception){error=e.message ?: "Revisa el periodo"} }) {
            Field("Nombre",h.title){h=h.copy(title=it)}
            DateField("Inicio",h.start){h=h.copy(start=it)}
            Choices(listOf("Desde la mañana","Desde la tarde","Desde la noche"),h.startHalf){h=h.copy(startHalf=it)}
            DateField("Último día incluido",h.end){h=h.copy(end=it)}
            Choices(listOf("Hasta la mañana incluida","Hasta la tarde incluida","Hasta la noche incluida"),h.endHalf){h=h.copy(endHalf=it)}
            Text("Tramos con hijos", style = MaterialTheme.typography.titleMedium)
            periodNames.forEachIndexed { index, name ->
                val bit = 1 shl index
                Toggle(name, h.mask and bit != 0) { h = h.copy(mask = h.mask xor bit) }
            }
            Text("Fuera del periodo sigue el ciclo anterior. En Normas puedes elegir el lunes de reinicio.")
            if(d.holidays.contains(original)) TextButton(onClick={save(d.copy(holidays=d.holidays-original));holiday=null}){Text("Eliminar periodo")}
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }
        preview?.let {next -> Preview(next,LocalDate.parse(h.start),{preview=null},{save(next);holiday=null;preview=null})}
    }
    if(summer) {
        var enabled by remember { mutableStateOf(d.summerEnabled) }; var even by remember { mutableStateOf(d.summerEven) }; var odd by remember { mutableStateOf(d.summerOdd) }; var preview by remember { mutableStateOf<CalendarData?>(null) }
        Editor("Quincenas de verano",{summer=false},{preview=d.copy(summerEnabled=enabled,summerEven=even,summerOdd=odd)}) {
            Toggle("Aplicar reglas de verano",enabled){enabled=it}
            Text("Activado = con hijos. Los intervalos incluyen días completos.")
            listOf("Años pares","Años impares").forEachIndexed { parity,title -> Text(title,style=MaterialTheme.typography.titleMedium); listOf("1–15 julio","16–31 julio","1–15 agosto","16–31 agosto").forEachIndexed { i,label -> Toggle(label,(if(parity==0) even else odd)[i]) { value -> if(parity==0) even=even.toMutableList().also {it[i]=value} else odd=odd.toMutableList().also {it[i]=value} } } }
        }
        preview?.let {next -> Preview(next,LocalDate.of(LocalDate.now().year,7,1),{preview=null},{save(next);summer=false;preview=null})}
    }
}

@Composable private fun TemplateGrid(values:List<Int>,change:(List<Int>)->Unit) {
    values.forEachIndexed { day, mask ->
        Text(weekdays[day], style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            periodNames.forEachIndexed { index, name ->
                val bit = 1 shl index
                FilterChip(
                    selected = mask and bit != 0,
                    onClick = { change(values.toMutableList().also { it[day] = mask xor bit }) },
                    label = { Text(name) }
                )
            }
        }
    }
}
@Composable private fun Preview(data:CalendarData,start:LocalDate,dismiss:()->Unit,confirm:()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text("Vista previa del mes")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("${start.monthValue}/${start.year} · Se conservan los cambios manuales.")
        val first=start.withDayOfMonth(1)
        repeat(first.lengthOfMonth()) { i -> val day=first.plusDays(i.toLong()); val state=CalendarEngine.day(data,day); Text("${day.dayOfMonth} · ${weekdays[day.dayOfWeek.value-1]}: ${stateNames[state.mask]}",Modifier.padding(vertical=5.dp)) }
    }},confirmButton={TextButton(onClick=confirm){Text("Aplicar cambios")}},dismissButton={TextButton(onClick=dismiss){Text("Volver")}})
}
