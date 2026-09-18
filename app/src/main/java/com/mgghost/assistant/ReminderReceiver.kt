package com.mgghost.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "MG Ghost reminder"
        val channelId = "ghost_reminders"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "GHOST Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, title.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_ghost)
            .setContentTitle("MG GHOST reminder")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(title.hashCode(), notification)
    }
}
