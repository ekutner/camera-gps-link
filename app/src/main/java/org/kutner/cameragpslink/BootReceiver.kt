package org.kutner.cameragpslink

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Check if we have saved cameras using CameraSettingsManager
            val savedCameras = AppSettingsManager.getSavedCameras(context)

            if (savedCameras.isNotEmpty()) {
                // Instead of starting the service, show a notification
                val localizedContext = LanguageManager.wrapContext(context)
                NotificationHelper(localizedContext).showBootNotification()
            }
//            val receiver = ComponentName(context, BootReceiver::class.java)
//            context.packageManager.setComponentEnabledSetting(
//                receiver,
//                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
//                PackageManager.DONT_KILL_APP
//            )
        }
    }


}