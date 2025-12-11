package org.kutner.cameragpslink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val highChannel = NotificationChannel(
                Constants.CHANNEL_CAMERA_SYNC_HIGH,
                context.getString(R.string.channel_name_connected), // Use string resource
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(highChannel)

            val lowChannel = NotificationChannel(
                Constants.CHANNEL_CAMERA_SYNC_LOW,
                context.getString(R.string.channel_name_searching), // Use string resource
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(lowChannel)

            val errorChannel = NotificationChannel(
                Constants.CHANNEL_CAMERA_ERROR,
                context.getString(R.string.channel_name_errors), // Use string resource
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(errorChannel)
        }
    }

//    fun createForegroundNotification(connectedCount: Int): Notification {
//        val channelId = if (connectedCount > 0) Constants.CHANNEL_CAMERA_SYNC_HIGH else Constants.CHANNEL_CAMERA_SYNC_LOW
//        val priority = if (connectedCount > 0) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
//
//        val title = when {
//            connectedCount == 0 -> "Searching for cameras..."
//            connectedCount == 1 -> "Connected to 1 camera"
//            else -> "Connected to $connectedCount cameras"
//        }
//
//        val pendingIntent: PendingIntent =
//            Intent(context, MainActivity::class.java).let { notificationIntent ->
//                PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
//            }
//
//        val builder = NotificationCompat.Builder(context, channelId)
//            .setContentTitle(title)
//            .setContentText()
//            .setSmallIcon(R.drawable.ic_notification)
//            .setContentIntent(pendingIntent)
//            .setOngoing(true)
//            .setPriority(priority)
//            .setCategory(NotificationCompat.CATEGORY_SERVICE)
//
//        // Add shutter button if at least one camera is connected
//        if (connectedCount > 0) {
//            val shutterIntent = Intent(context, CameraSyncService::class.java).apply {
//                action = Constants.ACTION_TRIGGER_SHUTTER
//            }
//            val shutterPendingIntent = PendingIntent.getService(
//                context,
//                1,
//                shutterIntent,
//                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
//            )
//            builder.addAction(
//                R.drawable.appicon,
//                "Shutter All",
//                shutterPendingIntent
//            )
//        }
//
//        return builder.build()
//    }

    fun createSearchingNotification(): Notification {
        val pendingIntent: PendingIntent =
            Intent(context, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        return NotificationCompat.Builder(context, Constants.CHANNEL_CAMERA_SYNC_LOW)
            .setContentTitle(context.getString(R.string.notification_searching_title)) // Use string resource
            .setContentText(context.getString(R.string.notification_searching_message)) // Use string resource
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notification_search_long_message))) // Use string resource
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun createCameraNotification(connection: CameraConnection): Notification {
        val notificationId = connection.device.address.hashCode()
        val pendingIntent: PendingIntent =
            Intent(context, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        val shutterIntent = Intent(context, CameraSyncService::class.java).apply {
            action = Constants.ACTION_TRIGGER_SHUTTER
            putExtra(Constants.EXTRA_DEVICE_ADDRESS, connection.device.address)
        }
        val shutterPendingIntent = PendingIntent.getService(
            context,
            notificationId,
            shutterIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cameraName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            connection.device.name ?: context.getString(R.string.default_camera_name) // Use string resource
        } else {
            context.getString(R.string.default_camera_name) // Use string resource
        }

        return NotificationCompat.Builder(context, Constants.CHANNEL_CAMERA_SYNC_HIGH)
            .setContentTitle(context.getString(R.string.notification_connected_to, cameraName)) // Use formatted string resource
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.appicon,
                context.getString(R.string.action_shutter), // Use string resource
                shutterPendingIntent
            )
            .build()
    }

    fun updateNotifications(
        connectedCameras: Collection<CameraConnection>,
        isForegroundServiceStarted: Boolean,
        log: (String) -> Unit
    ) {
        if (isForegroundServiceStarted) {
            connectedCameras.forEach { connection ->
                val notificationId = connection.device.address.hashCode()
                if (connection.isConnected || connection.isConnecting) {
                    notify(notificationId, createCameraNotification(connection))
                    log("Created/Updated notification for ${connection.device.address}")
                } else {
                    cancel(notificationId)
                    log("Cancelled notification for ${connection.device.address}")
                }
            }
        } else {
            log("Service not started, cancelling all notifications")
            cancelAll()
        }
    }

//    fun showPermissionsRequiredNotification() {
//        val notification = createForegroundNotification(0)
//        notificationManager.notify(Constants.NOTIFICATION_ID_PERMISSIONS_REQUIRED, notification)
//    }

    fun showShutterErrorNotification(deviceAddress: String, connection: CameraConnection) {
        val cameraName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            connection.device.name ?: context.getString(R.string.default_camera_name) // Use string resource
        } else {
            context.getString(R.string.default_camera_name) // Use string resource
        }

        val pendingIntent: PendingIntent =
            Intent(context, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        // Use string resources
        val errorTitle = context.getString(R.string.error_shutter_title) + " - " + cameraName
        val errorMessage = context.getString(R.string.error_shutter_message)
        val errorMessageLong = context.getString(R.string.error_shutter_message_long)

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_CAMERA_ERROR)
            .setContentTitle(errorTitle)
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(errorMessageLong))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationId = deviceAddress.hashCode() + Constants.NOTIFICATION_ID_SHUTTER_ERROR_OFFSET
        notificationManager.notify(notificationId, notification)
    }

    fun clearShutterErrorNotification(deviceAddress: String) {
        val notificationId = deviceAddress.hashCode() + Constants.NOTIFICATION_ID_SHUTTER_ERROR_OFFSET
        notificationManager.cancel(notificationId)
    }

    fun notify(id: Int, notification: Notification) {
        notificationManager.notify(id, notification)
    }

    fun cancel(id: Int) {
        notificationManager.cancel(id)
    }

    fun cancelAll() {
        notificationManager.cancelAll()
    }
}