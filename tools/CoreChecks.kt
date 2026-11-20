package es.luchis.calendario

import java.time.LocalDate

fun main() {
    var checks=0
    fun verify(name:String, block:()->Boolean) { check(block()) { name }; checks++; println("OK $name") }
    fun rejected(name:String,d:CalendarData) = verify(name) { runCatching {CalendarEngine.validate(d)}.isFailure }
    val base=CalendarData(initialized=true,restarts=listOf(Restart("2026-11-16",false)))
    fun mask(date:String,d:CalendarData=base)=CalendarEngine.day(d,LocalDate.parse(date)).mask
    verify("Calendario inicial de noviembre de 2026") {
        val initial = initialCalendar()
        CalendarEngine.validate(initial)
        val november = LocalDate.of(2026, 11, 1)
        val expected = listOf(0,6,7,1,6,7,7,7,1,0,0,6,1,0,0,0,7,1,6,7,7,7,1,0,0,6,1,0,0,0)
        (0L..29L).map { CalendarEngine.day(initial, november.plusDays(it)).mask } == expected &&
            mask("2026-10-30", initial) == 0 && mask("2026-12-01", initial) == 0 &&
            initial.entries.groupBy { it.date }.mapValues { (_, items) -> items.map { it.kind }.toSet() } == mapOf(
                "2026-11-05" to setOf(EntryKind.APPOINTMENT),
                "2026-11-12" to setOf(EntryKind.EXAM),
                "2026-11-20" to setOf(EntryKind.APPOINTMENT, EntryKind.EXAM),
                "2026-11-24" to setOf(EntryKind.APPOINTMENT),
                "2026-11-27" to setOf(EntryKind.EXAM)
            )
    }
    verify("Semana sin hijos completa") { (16..22).map {mask("2026-11-$it")} == listOf(1,0,0,6,1,0,0) }
    verify("Semana con hijos completa") { (23..29).map {mask("2026-11-$it")} == listOf(6,7,1,6,7,7,7) }
    verify("Extrapolacion anterior al ancla") {mask("2026-11-10")==7}
    verify("Cruce de ano") { mask("2027-01-05")==7 }
    verify("Ciclo sin configurar") {mask("2026-11-16",CalendarData(summerEnabled=false))==0}
    val restarted=base.copy(restarts=base.restarts+Restart("2026-11-23",false))
    verify("Reinicio conserva manana del lunes") {mask("2026-11-23",restarted)==0}
    verify("Reinicio cambia semana") {mask("2026-11-24",restarted)==0 && mask("2026-12-01",restarted)==7}
    verify("Verano par limites") {listOf("2026-07-01","2026-07-15","2026-07-16","2026-07-31","2026-08-01","2026-08-16","2026-08-31").map {mask(it)}==listOf(0,0,7,7,0,7,7)}
    verify("Verano impar") {mask("2027-07-01")==7 && mask("2027-08-31")==0}
    verify("Anio bisiesto") {mask("2028-02-29") in 0..7}
    val holiday=Holiday(title="Semana Santa",start="2026-11-17",startHalf=1,end="2026-11-19",endHalf=0,mask=7)
    val vacations=base.copy(holidays=listOf(holiday))
    verify("Vacaciones parciales inicio") {mask("2026-11-17",vacations)==6}
    verify("Vacaciones completas") {mask("2026-11-18",vacations)==7}
    verify("Vacaciones parciales final") {mask("2026-11-19",vacations)==7}
    verify("Vuelve ciclo tras vacaciones") {mask("2026-11-20",vacations)==1}
    verify("Manual prevalece sobre vacaciones") {mask("2026-11-18",vacations.copy(exceptions=mapOf("2026-11-18" to 0)))==0}
    verify("Manual prevalece sobre verano") {mask("2026-07-20",base.copy(exceptions=mapOf("2026-07-20" to 1)))==1}
    verify("Vacaciones explicitas prevalecen sobre verano") {mask("2026-07-20",base.copy(holidays=listOf(Holiday(title="Cambio verano",start="2026-07-20",end="2026-07-20",mask=0))))==0}
    val versions=base.copy(templates=base.templates+Templates(since="2026-11-18",away=List(7){7}))
    verify("Versiones no alteran pasado") {mask("2026-11-17",versions)==0 && mask("2026-11-18",versions)==7}
    verify("Origen visible") {CalendarEngine.day(vacations,LocalDate.parse("2026-11-17")).afternoonSource=="Semana Santa"}
    verify("Estado serializable valido") {CalendarEngine.validate(base.copy(entries=listOf(Entry(title="Otorrino",date="2026-11-19",time="10:30",reminder=30))));true}
    rejected("Solapes rechazados",vacations.copy(holidays=listOf(holiday,holiday.copy(id="otra"))))
    rejected("Intervalo invertido",base.copy(holidays=listOf(holiday.copy(end="2026-11-16"))))
    rejected("Reinicio fuera de lunes",base.copy(restarts=listOf(Restart("2026-11-17",true))))
    rejected("Version incompatible",base.copy(version=99))
    rejected("Plantilla incompleta",base.copy(templates=listOf(Templates(away=listOf(0)))))
    rejected("Estado incorrecto",base.copy(exceptions=mapOf("2026-11-17" to 8)))
    rejected("Fecha invalida",base.copy(exceptions=mapOf("2026-02-30" to 1)))
    rejected("Cita sin titulo",base.copy(entries=listOf(Entry(title=""))))
    rejected("Hora invalida",base.copy(entries=listOf(Entry(title="Cita",date="2026-11-20",time="28:00"))))
    rejected("Aviso sin fecha",base.copy(entries=listOf(Entry(title="Nota",notice=true,reminder=0))))
    val rich=vacations.copy(exceptions=mapOf("2026-12-03" to 2),entries=listOf(Entry(title="Otorrino de los hijos",date="2026-12-04",time="10:30",notes="Revisión",reminder=30)),color=0xFF7C3AED,theme=2,agenda=true)
    verify("Copia completa ida y vuelta con acentos") {BackupCodec.decode(BackupCodec.encode(rich))==rich}
    verify("Los cuatro tipos se distinguen al restaurar") {
        val data = base.copy(entries = listOf(
            Entry(title = "Cita", date = "2026-11-20"),
            Entry(title = "Examen", date = "2026-11-20", exam = true),
            Entry(title = "Actividad", date = "2026-11-20", activity = true),
            Entry(title = "Recordatorio", date = "2026-11-20", notice = true)
        ))
        val encoded = BackupCodec.encode(data)
        BackupCodec.decode(encoded).entries.map { it.kind } == EntryKind.entries &&
            BackupCodec.decode(encoded.replace(",\"exam\":false", "")).entries == data.entries
    }
    verify("Una actividad sin hora se repite cada siete días desde la fecha inicial") {
        val drawing = Entry(title = "Dibujo", date = "2026-11-20", activity = true, repeatWeekly = true)
        val restored = BackupCodec.decode(BackupCodec.encode(base.copy(entries = listOf(drawing)))).entries.single()
        val dates = restored.occurrences(LocalDate.parse("2026-11-21"), LocalDate.parse("2026-12-12")).map { it.date.toString() }.toList()
        restored.time.isEmpty() && dates == listOf("2026-11-27", "2026-12-04", "2026-12-11") &&
            drawing.nextDate(LocalDate.parse("2026-11-01")) == LocalDate.parse("2026-11-20") &&
            drawing.copy(repeatWeekly = false).nextDate(LocalDate.parse("2026-11-21")) == null
    }
    rejected("Una notificación requiere hora", base.copy(entries = listOf(Entry(title = "Excursión", date = "2026-11-20", reminder = 0))))
    verify("La tarde y la noche se pueden asignar por separado") {
        val split = base.copy(exceptions = mapOf("2026-11-17" to 2, "2026-11-18" to 4))
        mask("2026-11-17", split) == 2 && mask("2026-11-18", split) == 4
    }
    verify("Vacaciones de una sola noche") {
        val nightOnly = holiday.copy(end = holiday.start, startHalf = 2, endHalf = 2)
        mask(holiday.start, base.copy(holidays = listOf(nightOnly))) == 4
    }
    verify("Una copia antigua conserva la tarde como tarde y noche") {
        val old = base.copy(
            version = 1,
            templates = listOf(Templates(away = List(7) { 2 }, together = List(7) { 3 })),
            exceptions = mapOf("2026-11-18" to 2),
            holidays = listOf(holiday.copy(end = holiday.start, endHalf = 1, mask = 3))
        )
        val migrated = BackupCodec.decode(BackupCodec.encode(old))
        migrated.version == 2 && migrated.templates.single().away == List(7) { 6 } &&
            migrated.exceptions["2026-11-18"] == 6 && migrated.holidays.single().endHalf == 2 &&
            mask("2026-11-17", migrated) == 6
    }
    verify("JSON roto rechazado") {runCatching {BackupCodec.decode("{no es json")}.isFailure}
    verify("Copia incompleta rechazada") {runCatching {BackupCodec.decode("{\"version\":1}")}.isFailure}
    verify("Version futura rechazada al importar") {runCatching {BackupCodec.decode(BackupCodec.encode(rich).replace("\"version\":2","\"version\":99"))}.isFailure}
    verify("Reglas corruptas rechazadas al importar") {runCatching {BackupCodec.decode(BackupCodec.encode(rich).replace("\"summerEven\":[false,true,false,true]","\"summerEven\":[]"))}.isFailure}
    println("$checks comprobaciones correctas")
}
