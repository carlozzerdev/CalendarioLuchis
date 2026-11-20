package es.luchis.calendario

import com.google.gson.Gson
import com.google.gson.JsonParser

/** Portable backup, deliberately independent of Android and the database schema. */
object BackupCodec {
    private val gson=Gson()
    fun encode(d:CalendarData):String { CalendarEngine.validate(d); return gson.toJson(d) }
    fun decode(json:String):CalendarData {
        require(json.length<=10_000_000) { "La copia es demasiado grande" }
        val tree=JsonParser.parseString(json).asJsonObject
        require(listOf("version","initialized","restarts","templates","exceptions","holidays","summerEnabled","summerEven","summerOdd","entries","color","theme","agenda").all { tree.has(it) && !tree.get(it).isJsonNull }) { "La copia está incompleta" }
        val data = gson.fromJson(tree, CalendarData::class.java)
        CalendarEngine.validate(data)
        if (data.version == 2) return data

        // The old afternoon covered both of the new periods.
        fun expand(mask: Int): Int = mask or ((mask and 2) shl 1)
        return data.copy(
            version = 2,
            templates = data.templates.map { it.copy(away = it.away.map(::expand), together = it.together.map(::expand)) },
            exceptions = data.exceptions.mapValues { expand(it.value) },
            holidays = data.holidays.map { it.copy(endHalf = if (it.endHalf == 1) 2 else 0, mask = expand(it.mask)) }
        ).also(CalendarEngine::validate)
    }
}
