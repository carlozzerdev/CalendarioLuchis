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
        return gson.fromJson(tree,CalendarData::class.java).also { CalendarEngine.validate(it) }
    }
}
