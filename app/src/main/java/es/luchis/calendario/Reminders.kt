package es.luchis.calendario

import android.app.*
import android.content.*
import android.os.Build
import java.time.*
import java.util.concurrent.Executors

object Reminders {
    fun channel(c: Context) { c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("agenda","Citas y avisos",NotificationManager.IMPORTANCE_HIGH)) }
    private fun pending(c: Context,id: String): PendingIntent = PendingIntent.getBroadcast(c,0,Intent(c,ReminderReceiver::class.java).setData(android.net.Uri.parse("lucia://reminder/$id")).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(c: Context,old: CalendarData,new: CalendarData) {
        channel(c)
        val alarm = c.getSystemService(AlarmManager::class.java)
        old.entries.forEach { alarm.cancel(pending(c,it.id)) }
        new.entries.filter { !it.archived && it.date.isNotBlank() && it.reminder >= 0 }.forEach { e ->
            val time = LocalDateTime.of(LocalDate.parse(e.date),LocalTime.parse(e.time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()-e.reminder*60000L
            if(time > System.currentTimeMillis()) {
                if(Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pending(c,e.id))
                else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pending(c,e.id))
            }
        }
    }
}
class ReminderReceiver: BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) {
        val result = goAsync()
        Executors.newSingleThreadExecutor().apply { execute {
            try {
                val e = Repository.get(c).read().entries.firstOrNull { it.id == i.getStringExtra("id") && !it.archived } ?: return@execute
                Reminders.channel(c)
                val open = PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
                val n = Notification.Builder(c,"agenda").setSmallIcon(es.luchis.calendario.R.drawable.ic_lucia).setContentTitle(e.title).setContentText("${e.date} · ${e.time} ${e.place}").setContentIntent(open).setAutoCancel(true).build()
                if(Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) c.getSystemService(NotificationManager::class.java).notify(e.id.hashCode(),n)
            } finally { result.finish(); shutdown() }
        } }
    }
}
class RescheduleReceiver: BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) {
        val result = goAsync()
        Executors.newSingleThreadExecutor().apply { execute { try { val d=Repository.get(c).read(); Reminders.schedule(c,d,d) } finally { result.finish(); shutdown() } } }
    }
}
