package es.luchis.calendario

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Templates(val since: String = "1900-01-01", val away: List<Int> = listOf(1,0,0,2,1,0,0), val together: List<Int> = listOf(2,3,1,2,3,3,3))
data class Restart(val monday: String, val together: Boolean)
data class Holiday(val id: String = UUID.randomUUID().toString(), val title: String, val start: String, val startHalf: Int = 0, val end: String, val endHalf: Int = 1, val mask: Int = 3)
data class Entry(val id: String = UUID.randomUUID().toString(), val title: String, val date: String = "", val time: String = "10:30", val duration: String = "", val place: String = "", val notes: String = "", val reminder: Int = -1, val notice: Boolean = false, val pinned: Boolean = true, val archived: Boolean = false)
data class CalendarData(val version: Int = 1, val initialized: Boolean = false, val restarts: List<Restart> = emptyList(), val templates: List<Templates> = listOf(Templates()), val exceptions: Map<String,Int> = emptyMap(), val holidays: List<Holiday> = emptyList(), val summerEnabled: Boolean = true, val summerEven: List<Boolean> = listOf(false,true,false,true), val summerOdd: List<Boolean> = listOf(true,false,true,false), val entries: List<Entry> = emptyList(), val color: Long = 0xFF2563EB, val theme: Int = 0, val agenda: Boolean = false)
data class DayState(val mask: Int, val morningSource: String, val afternoonSource: String)

object CalendarEngine {
    fun day(data: CalendarData, date: LocalDate): DayState {
        val parts = (0..1).map { half ->
            val manual = data.exceptions[date.toString()]
            if (manual != null) return@map ((manual and (1 shl half)) != 0) to "Cambio manual"
            val slot = date.toEpochDay() * 2 + half
            val holiday = data.holidays.firstOrNull { slot in (LocalDate.parse(it.start).toEpochDay()*2+it.startHalf)..(LocalDate.parse(it.end).toEpochDay()*2+it.endHalf) }
            if (holiday != null) return@map ((holiday.mask and (1 shl half)) != 0) to holiday.title
            if (data.summerEnabled && date.monthValue in 7..8) {
                val i = (date.monthValue-7)*2 + if(date.dayOfMonth <= 15) 0 else 1
                return@map (if(date.year % 2 == 0) data.summerEven[i] else data.summerOdd[i]) to "Quincena de verano"
            }
            if (!data.initialized || data.restarts.isEmpty()) return@map false to "Configura el lunes inicial"
            val sorted = data.restarts.sortedBy { it.monday }
            val anchor = sorted.lastOrNull { LocalDate.parse(it.monday).toEpochDay()*2+1 <= slot } ?: sorted.first()
            val monday = date.minusDays((date.dayOfWeek.value-1).toLong())
            val weeks = Math.floorDiv(ChronoUnit.DAYS.between(LocalDate.parse(anchor.monday), monday),7)
            val together = anchor.together xor (Math.floorMod(weeks,2L) == 1L)
            val template = data.templates.filter { it.since <= date.toString() }.maxByOrNull { it.since } ?: data.templates.minBy { it.since }
            val mask = (if(together) template.together else template.away)[date.dayOfWeek.value-1]
            ((mask and (1 shl half)) != 0) to if(together) "Semana con Lucía" else "Semana sin Lucía"
        }
        return DayState((if(parts[0].first) 1 else 0) + (if(parts[1].first) 2 else 0),parts[0].second,parts[1].second)
    }
    fun validate(d: CalendarData) {
        require(d.version == 1) { "Versión de copia incompatible" }
        require(d.templates.isNotEmpty())
        d.templates.forEach { LocalDate.parse(it.since); require(it.away.size == 7 && it.together.size == 7); require((it.away+it.together).all { m -> m in 0..3 }) }
        require(d.templates.map { it.since }.distinct().size == d.templates.size)
        require(!d.initialized || d.restarts.isNotEmpty())
        d.restarts.forEach { require(LocalDate.parse(it.monday).dayOfWeek.value == 1) { "El reinicio debe ser un lunes" } }
        require(d.restarts.map { it.monday }.distinct().size == d.restarts.size)
        require(d.summerEven.size == 4 && d.summerOdd.size == 4)
        d.exceptions.forEach { (day,mask) -> LocalDate.parse(day); require(mask in 0..3) }
        val ranges = d.holidays.map { h ->
            require(h.title.isNotBlank() && h.mask in 0..3 && h.startHalf in 0..1 && h.endHalf in 0..1)
            val a = LocalDate.parse(h.start).toEpochDay()*2+h.startHalf; val b = LocalDate.parse(h.end).toEpochDay()*2+h.endHalf
            require(a <= b) { "El final debe ser posterior al inicio" }; a..b
        }.sortedBy { it.first }
        ranges.zipWithNext().forEach { (a,b) -> require(a.last < b.first) { "Hay vacaciones superpuestas" } }
        require(d.holidays.map {it.id}.distinct().size == d.holidays.size)
        d.entries.forEach { e -> require(e.title.isNotBlank()); require(e.reminder in listOf(-1,0,10,30,60,1440)); if(e.date.isNotEmpty()) { LocalDate.parse(e.date); java.time.LocalTime.parse(e.time) }; require(e.reminder < 0 || e.date.isNotEmpty()); require(e.duration.isEmpty() || e.duration.toInt() > 0) }
        require(d.entries.map { it.id }.distinct().size == d.entries.size)
        require(d.theme in 0..2)
        require(d.color in 0xFF000000..0xFFFFFFFF)
    }
}
