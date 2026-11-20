package es.luchis.calendario

import android.content.Context
import androidx.room.*
import androidx.work.*
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity data class Snapshot(@PrimaryKey val id: Int = 1, val json: String)
@Dao interface SnapshotDao {
    @Query("SELECT * FROM Snapshot WHERE id=1") fun read(): Snapshot?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun write(snapshot: Snapshot)
}
@Database(entities = [Snapshot::class], version = 1, exportSchema = false)
abstract class CalendarDatabase : RoomDatabase() { abstract fun snapshots(): SnapshotDao }
class Repository private constructor(val context: Context) {
    private val db = Room.databaseBuilder(context,CalendarDatabase::class.java,"lucia.db").build()
    val prefs = context.getSharedPreferences("backup",Context.MODE_PRIVATE)
    fun read(): CalendarData = db.snapshots().read()?.let { decode(it.json) } ?: CalendarData()
    fun encode(d: CalendarData): String = BackupCodec.encode(d)
    fun decode(json: String): CalendarData = BackupCodec.decode(json)
    fun save(d: CalendarData) { CalendarEngine.validate(d); db.snapshots().write(Snapshot(json=encode(d))) }
    fun restore(d: CalendarData) {
        CalendarEngine.validate(d)
        val previous = encode(read())
        val safe = File(context.filesDir,"antes-de-restaurar.json")
        safe.writeText(previous)
        save(d)
    }
    fun backup(): Boolean {
        return try {
            val uri = prefs.getString("folder",null) ?: error("Elige una carpeta para las copias")
            val folder = DocumentFile.fromTreeUri(context,Uri.parse(uri)) ?: error("Carpeta no disponible")
            val name = "lucia-auto-${System.currentTimeMillis()}.json"
            val file = folder.createFile("application/json",name) ?: error("No se puede crear la copia")
            try { context.contentResolver.openOutputStream(file.uri,"wt")!!.bufferedWriter().use { it.write(encode(read())) } }
            catch(e: Exception) { file.delete(); throw e }
            folder.listFiles().filter { it.name?.startsWith("lucia-auto-") == true && it.name?.endsWith(".json") == true }.sortedByDescending { it.name }.drop(12).forEach { it.delete() }
            prefs.edit().putString("last",Instant.now().toString()).remove("error").apply(); true
        } catch(e: Exception) { prefs.edit().putString("error",e.message ?: "Error de copia").apply(); false }
    }
    companion object {
        @Volatile private var instance: Repository? = null
        fun get(c: Context): Repository = instance ?: synchronized(this) { instance ?: Repository(c.applicationContext).also { instance=it } }
        fun schedule(c: Context) {
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("weekly-backup",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<BackupWorker>(7,TimeUnit.DAYS).setInitialDelay(7,TimeUnit.DAYS).build())
        }
    }
}
class BackupWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { if(Repository.get(applicationContext).backup()) Result.success() else Result.retry() }
}
