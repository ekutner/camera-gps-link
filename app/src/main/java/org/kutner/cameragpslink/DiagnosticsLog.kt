package org.kutner.cameragpslink

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent, size-capped log that survives process death, used to find out why the
 * service stopped running in the background. Unlike the in-memory activity log and
 * logcat (which only keeps a few minutes of history), it is kept across restarts and
 * included when the user shares the log.
 */
object DiagnosticsLog {
    private const val TAG = "DiagnosticsLog"
    private const val FILE_NAME = "diagnostics.log"
    private const val OLD_FILE_NAME = "diagnostics.old.log"
    private const val MAX_FILE_SIZE = 64 * 1024L
    private const val PREFS_NAME = "diagnostics"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val MAX_TRACE_CHARS = 4096
    private const val MAX_STATE_SUMMARY_BYTES = 128

    private var isCrashHandlerInstalled = false
    private var lastProcessStateSummary: String? = null

    @Synchronized
    fun append(context: Context, message: String) {
        Log.i(TAG, message)
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_FILE_SIZE) {
                file.renameTo(File(context.filesDir, OLD_FILE_NAME))
            }
            file.appendText("[${formatTimestamp(System.currentTimeMillis())}] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostics log", e)
        }
    }

    @Synchronized
    fun read(context: Context): String {
        return listOf(OLD_FILE_NAME, FILE_NAME)
            .map { File(context.filesDir, it) }
            .filter { it.exists() }
            .joinToString("") { it.readText() }
    }

    /** Records the stack trace of uncaught exceptions, which the system's exit info doesn't keep. */
    @Synchronized
    fun installCrashHandler(context: Context) {
        if (isCrashHandlerInstalled) return
        isCrashHandlerInstalled = true
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            append(appContext, "Uncaught exception on thread '${thread.name}': ${Log.getStackTraceString(throwable)}")
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Logs every process exit the system recorded since the last call (Android 11+). */
    fun logProcessExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastTimestamp = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
            val newExits = activityManager.getHistoricalProcessExitReasons(null, 0, 0)
                .filter { it.timestamp > lastTimestamp }
                .sortedBy { it.timestamp }
            newExits.forEach { append(context, "Previous process exit: ${describeExit(it)}") }
            newExits.lastOrNull()?.let {
                prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, it.timestamp).apply()
            }
        } catch (e: Exception) {
            append(context, "Failed to read process exit reasons: $e")
        }
    }

    /** Logs system state that can cause the OS to stop the app in the background. */
    fun logEnvironment(context: Context) {
        try {
            val powerManager = context.getSystemService(PowerManager::class.java)
            val parts = mutableListOf(
                "ignoringBatteryOptimizations=${powerManager.isIgnoringBatteryOptimizations(context.packageName)}",
                "powerSaveMode=${powerManager.isPowerSaveMode}",
                "deviceIdleMode=${powerManager.isDeviceIdleMode}"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val activityManager = context.getSystemService(ActivityManager::class.java)
                val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
                parts.add("backgroundRestricted=${activityManager.isBackgroundRestricted}")
                parts.add("standbyBucket=${usageStatsManager.appStandbyBucket}")
            }
            append(context, "Environment: ${parts.joinToString(", ")}")
        } catch (e: Exception) {
            append(context, "Failed to read environment: $e")
        }
    }

    /**
     * Stores a short description of the app's current state with the system, which reports
     * it back in the exit info of this process, so we know what the app was doing when it died.
     */
    fun setProcessStateSummary(context: Context, summary: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || summary == lastProcessStateSummary) return
        try {
            val stateWithTime = "${formatTimestamp(System.currentTimeMillis())} $summary"
            val bytes = stateWithTime.toByteArray(Charsets.UTF_8).take(MAX_STATE_SUMMARY_BYTES).toByteArray()
            context.getSystemService(ActivityManager::class.java).setProcessStateSummary(bytes)
            lastProcessStateSummary = summary
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set process state summary", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeExit(info: ApplicationExitInfo): String {
        // toString() includes the reason, sub-reason, status, importance and memory usage
        val description = StringBuilder(info.toString())
        info.processStateSummary?.let {
            description.append(" lastState='${String(it, Charsets.UTF_8)}'")
        }
        if (info.reason == ApplicationExitInfo.REASON_ANR) {
            try {
                info.traceInputStream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(MAX_TRACE_CHARS)
                    val length = reader.read(buffer)
                    if (length > 0) description.append("\nANR trace:\n").append(buffer, 0, length)
                }
            } catch (e: Exception) {
                description.append(" (failed to read ANR trace: $e)")
            }
        }
        return description.toString()
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
    }
}
