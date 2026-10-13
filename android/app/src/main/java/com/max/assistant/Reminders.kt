package com.max.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Reminders: AlarmManager tay time par ReminderReceiver ko jagata hai, wo notification dikhata hai.
 * Abhi phone restart hone par reminders kho jaate hain — baad mein server se sync karenge.
 */
object Reminders {
    private const val CHANNEL_ID = "reminders"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun schedule(context: Context, atMillis: Long, text: String) {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_TEXT, text)
        val requestCode = (atMillis / 1000 % Int.MAX_VALUE).toInt() xor text.hashCode()
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Exact time ke liye Android 12+ par special permission chahiye. Na ho to kuch minute aage-peeche chalega.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
        DebugLog.i("Reminder scheduled: '$text'")
    }

    fun show(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Max reminders", NotificationManager.IMPORTANCE_HIGH),
        )
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Max reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(text.hashCode(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.show(context, intent.getStringExtra(EXTRA_TEXT) ?: "Reminder")
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
