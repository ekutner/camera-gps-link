package org.kutner.cameragpslink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Check if we have saved cameras using CameraSettingsManager
            val savedCameras = AppSettingsManager.getSavedCameras(context)

            if (savedCameras.isNotEmpty()) {
                // Instead of starting the service, show a notification
                showStartServiceNotification(context)
            }
        }
    }

    private fun showStartServiceNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_CAMERA_SYNC_BOOT,
                context.getString(R.string.channel_name_boot), // Use string resource
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open MainActivity and start the service
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("start_service_on_launch", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Use string resource for title and message, formatting the message with app_name
        val appName = context.getString(R.string.app_name)
        val bootMessage = context.getString(R.string.notification_boot_message, appName)

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_CAMERA_SYNC_BOOT)
            .setContentTitle(appName) // Use string resource
            .setContentText(bootMessage) // Use formatted string resource
            .setSmallIcon(R.drawable.appicon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1000, notification)
    }
}