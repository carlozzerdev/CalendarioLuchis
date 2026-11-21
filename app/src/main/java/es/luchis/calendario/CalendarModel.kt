package es.luchis.calendario

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Templates(val since: String = "1900-01-01", val away: List<Int> = listOf(1,0,0,6,1,0,0), val together: List<Int> = listOf(6,7,1,6,7,7,7))
data class Restart(val monday: String, val together: Boolean)
data class Holiday(val id: String = UUID.randomUUID().toString(), val title: String, val start: String, val startHalf: Int = 0, val end: String, val endHalf: Int = 2, val mask: Int = 7)
data class Entry(val id: String = UUID.randomUUID().toString(), val title: String, val date: String = "", val time: String = "", val duration: String = "", val place: String = "", val notes: String = "", val reminder: Int = -1, val notice: Boolean = false, val pinned: Boolean = true, val archived: Boolean = false, val exam: Boolean = false, val activity: Boolean = false, val repeatWeekly: Boolean = false, val fixed: Boolean? = null) {
    // In older backups, pinned applied only to reminders.
    internal val isFixed: Boolean
        get() = fixed ?: (notice && pinned)

    internal val kind: EntryKind
        get() = when {
            notice -> EntryKind.REMINDER
            exam -> EntryKind.EXAM
            activity -> EntryKind.ACTIVITY
            else -> EntryKind.APPOINTMENT
        }

    internal fun nextDate(from: LocalDate): LocalDate? {
        if (date.isEmpty()) return null
        val start = LocalDate.parse(date)
        if (!start.isBefore(from)) return start
        if (!repeatWeekly) return null
        val elapsed = ChronoUnit.DAYS.between(start, from)
        return start.plusWeeks((elapsed + 6) / 7)
    }
}

internal enum class EntryKind(val mark: String, val label: String, val plural: String) {
    APPOINTMENT("C", "Cita", "Citas"),
    EXAM("E", "Examen", "Exámenes"),
    ACTIVITY("A", "Actividad", "Actividades"),
    REMINDER("R", "Recordatorio", "Recordatorios")
}

internal data class EntryOccurrence(val entry: Entry, val date: LocalDate)

internal fun Entry.occurrences(from: LocalDate, through: LocalDate): Sequence<EntryOccurrence> {
    val first = nextDate(from) ?: return emptySequence()
    return generateSequence(first) { if (repeatWeekly) it.plusWeeks(1) else null }
        .takeWhile { it <= through }
        .map { EntryOccurrence(this, it) }
}
internal const val DEFAULT_CALENDAR_COLOR = 0xFF206C9B

data class CalendarData(val version: Int = 2, val initialized: Boolean = false, val restarts: List<Restart> = emptyList(), val templates: List<Templates> = listOf(Templates()), val exceptions: Map<String,Int> = emptyMap(), val holidays: List<Holiday> = emptyList(), val summerEnabled: Boolean = true, val summerEven: List<Boolean> = listOf(false,true,false,true), val summerOdd: List<Boolean> = listOf(true,false,true,false), val entries: List<Entry> = emptyList(), val color: Long = DEFAULT_CALENDAR_COLOR, val theme: Int = 0, val agenda: Boolean = false)
data class DayState(val mask: Int, val morningSource: String, val afternoonSource: String, val nightSource: String)

internal fun initialCalendar(): CalendarData {
    val november = java.time.YearMonth.of(2026, 11)
    val custody = buildMap {
        listOf(4, 9, 13, 18, 23, 27).forEach { put(it, 1) }
        listOf(2, 5, 12, 19, 26).forEach { put(it, 6) }
        listOf(3, 6, 7, 8, 17, 20, 21, 22).forEach { put(it, 7) }
    }
    return CalendarData(
        exceptions = (1..november.lengthOfMonth()).associate { november.atDay(it).toString() to (custody[it] ?: 0) },
        entries = listOf(
            Entry(id = "nov2026-05-cita", title = "Cita", date = "2026-11-05"),
            Entry(id = "nov2026-12-examen", title = "Examen", date = "2026-11-12", exam = true),
            Entry(id = "nov2026-20-mates", title = "Examen de mates", date = "2026-11-20", exam = true),
            Entry(id = "nov2026-20-pediatra", title = "Pediatra", date = "2026-11-20"),
            Entry(id = "nov2026-24-tramite", title = "Trámite personal", date = "2026-11-24"),
            Entry(id = "nov2026-27-ciencias", title = "Examen de ciencias", date = "2026-11-27", exam = true)
        )
    )
}

object CalendarEngine {
    fun day(data: CalendarData, date: LocalDate): DayState {
        val parts = (0..2).map { half ->
            val manual = data.exceptions[date.toString()]
            if (manual != null) return@map ((manual and (1 shl half)) != 0) to "Cambio manual"
            val slot = date.toEpochDay() * 3 + half
            val holiday = data.holidays.firstOrNull { slot in (LocalDate.parse(it.start).toEpochDay()*3+it.startHalf)..(LocalDate.parse(it.end).toEpochDay()*3+it.endHalf) }
            if (holiday != null) return@map ((holiday.mask and (1 shl half)) != 0) to holiday.title
            if (data.summerEnabled && date.monthValue in 7..8) {
                val i = (date.monthValue-7)*2 + if(date.dayOfMonth <= 15) 0 else 1
                return@map (if(date.year % 2 == 0) data.summerEven[i] else data.summerOdd[i]) to "Quincena de verano"
            }
            if (!data.initialized || data.restarts.isEmpty()) return@map false to "Configura el lunes inicial"
            val sorted = data.restarts.sortedBy { it.monday }
            val anchor = sorted.lastOrNull { LocalDate.parse(it.monday).toEpochDay()*3+1 <= slot } ?: sorted.first()
            val monday = date.minusDays((date.dayOfWeek.value-1).toLong())
            val weeks = Math.floorDiv(ChronoUnit.DAYS.between(LocalDate.parse(anchor.monday), monday),7)
            val together = anchor.together xor (Math.floorMod(weeks,2L) == 1L)
            val template = data.templates.filter { it.since <= date.toString() }.maxByOrNull { it.since } ?: data.templates.minBy { it.since }
            val mask = (if(together) template.together else template.away)[date.dayOfWeek.value-1]
            ((mask and (1 shl half)) != 0) to if(together) "Semana con hijos" else "Semana sin hijos"
        }
        val mask = parts.withIndex().sumOf { (index, part) -> if (part.first) 1 shl index else 0 }
        return DayState(mask, parts[0].second, parts[1].second, parts[2].second)
    }
    fun validate(d: CalendarData) {
        require(d.version in 1..2) { "Versión de copia incompatible" }
        val periods = d.version + 1
        val maxMask = (1 shl periods) - 1
        require(d.templates.isNotEmpty())
        d.templates.forEach { LocalDate.parse(it.since); require(it.away.size == 7 && it.together.size == 7); require((it.away+it.together).all { m -> m in 0..maxMask }) }
        require(d.templates.map { it.since }.distinct().size == d.templates.size)
        require(!d.initialized || d.restarts.isNotEmpty())
        d.restarts.forEach { require(LocalDate.parse(it.monday).dayOfWeek.value == 1) { "El reinicio debe ser un lunes" } }
        require(d.restarts.map { it.monday }.distinct().size == d.restarts.size)
        require(d.summerEven.size == 4 && d.summerOdd.size == 4)
        d.exceptions.forEach { (day,mask) -> LocalDate.parse(day); require(mask in 0..maxMask) }
        val ranges = d.holidays.map { h ->
            require(h.title.isNotBlank() && h.mask in 0..maxMask && h.startHalf in 0 until periods && h.endHalf in 0 until periods)
            val a = LocalDate.parse(h.start).toEpochDay()*periods+h.startHalf; val b = LocalDate.parse(h.end).toEpochDay()*periods+h.endHalf
            require(a <= b) { "El final debe ser posterior al inicio" }; a..b
        }.sortedBy { it.first }
        ranges.zipWithNext().forEach { (a,b) -> require(a.last < b.first) { "Hay vacaciones superpuestas" } }
        require(d.holidays.map {it.id}.distinct().size == d.holidays.size)
        d.entries.forEach { e ->
            require(e.title.isNotBlank())
            require(listOf(e.notice, e.exam, e.activity).count { it } <= 1)
            require(e.reminder in listOf(-1, 0, 10, 30, 60, 1440))
            if (e.date.isNotEmpty()) {
                LocalDate.parse(e.date)
                if (e.time.isNotEmpty()) java.time.LocalTime.parse(e.time)
            }
            require(e.reminder < 0 || (e.date.isNotEmpty() && e.time.isNotEmpty()))
            require(!e.repeatWeekly || e.date.isNotEmpty())
            require(!(e.exam || e.activity) || e.date.isNotEmpty() || e.isFixed)
            require(e.duration.isEmpty() || e.duration.toInt() > 0)
        }
        require(d.entries.map { it.id }.distinct().size == d.entries.size)
        require(d.theme in 0..2)
        require(d.color in 0xFF000000..0xFFFFFFFF)
    }
}
