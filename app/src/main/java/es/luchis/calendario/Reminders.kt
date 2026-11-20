package es.luchis.calendario

import android.app.*
import android.content.*
import android.os.Build
import java.time.*
import java.util.concurrent.Executors

object Reminders {
    fun channel(c: Context) { c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("agenda","Citas y avisos",NotificationManager.IMPORTANCE_HIGH)) }
    private fun pending(c: Context, id: String, date: LocalDate? = null): PendingIntent =
        PendingIntent.getBroadcast(
            c, 0, Intent(c, ReminderReceiver::class.java)
                .setData(android.net.Uri.parse("lucia://reminder/$id"))
                .putExtra("id", id)
                .putExtra("occurrenceDate", date?.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    fun schedule(c: Context,old: CalendarData,new: CalendarData) {
        channel(c)
        val alarm = c.getSystemService(AlarmManager::class.java)
        old.entries.forEach { alarm.cancel(pending(c,it.id)) }
        new.entries.forEach { scheduleNext(c, it) }
    }

    fun scheduleNext(c: Context, entry: Entry) {
        if (entry.archived || entry.reminder < 0 || entry.time.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val leadSeconds = entry.reminder * 60L
        val hour = LocalTime.parse(entry.time)
        val firstDate = entry.nextDate(now.plusSeconds(leadSeconds).atZone(zone).toLocalDate()) ?: return
        fun trigger(date: LocalDate) = date.atTime(hour).atZone(zone).toInstant().minusSeconds(leadSeconds)
        val date = if (trigger(firstDate) > now) firstDate
            else if (entry.repeatWeekly) firstDate.plusWeeks(1) else return
        val time = trigger(date).toEpochMilli()
        val alarm = c.getSystemService(AlarmManager::class.java)
        val intent = pending(c, entry.id, date)
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
        }
    }
}
class ReminderReceiver: BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) {
        val result = goAsync()
        Executors.newSingleThreadExecutor().apply { execute {
            try {
                val e = Repository.get(c).read().entries.firstOrNull {
                    it.id == i.getStringExtra("id") && !it.archived && it.reminder >= 0 && it.time.isNotEmpty()
                } ?: return@execute
                if (e.repeatWeekly) Reminders.scheduleNext(c, e)
                Reminders.channel(c)
                val open = PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
                val date = i.getStringExtra("occurrenceDate") ?: e.date
                val n = Notification.Builder(c,"agenda").setSmallIcon(es.luchis.calendario.R.drawable.ic_lucia).setContentTitle(e.title).setContentText("$date · ${e.time} ${e.place}").setContentIntent(open).setAutoCancel(true).build()
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
