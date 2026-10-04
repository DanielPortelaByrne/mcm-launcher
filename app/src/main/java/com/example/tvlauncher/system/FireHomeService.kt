package com.example.tvlauncher.system

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.tvlauncher.MainActivity

/** Fire OS 6 and 7 (Android 7.1 to 9). Reads Home launch events locally; never records log contents. */
class FireHomeService : Service() {
    @Volatile private var running = false
    @Volatile private var reader: Process? = null
    private var worker: Thread? = null
    private val bootHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bootAttempt = 0
    private val bootLaunch = object : Runnable {
        override fun run() {
            if (!running || !enabled(this@FireHomeService)) return
            val boot = android.provider.Settings.Global.getInt(contentResolver, "boot_count", 0)
            if (prefs(this@FireHomeService).getInt("opened_boot", -1) == boot) return
            if (getSystemService(android.os.UserManager::class.java).isUserUnlocked) {
                try {
                    startActivity(Intent(this@FireHomeService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    prefs(this@FireHomeService).edit().putInt("opened_boot", boot).apply()
                    Log.i(TAG, "MCM opened at startup")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Startup launch waiting: ${e.javaClass.simpleName}")
                }
            }
            if (++bootAttempt < 120) bootHandler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!supported() || !enabled(this) || checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            val control = PendingIntent.getActivity(this, 0,
                Intent(this, FireHomeSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (Build.VERSION.SDK_INT >= 26) getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, "MCM Home button", android.app.NotificationManager.IMPORTANCE_MIN))
            @Suppress("DEPRECATION")
            val notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("MCM Home button enabled")
                .setContentText("Open Home button settings to pause or turn off")
                .setContentIntent(control).setOngoing(true).build()
            startForeground(3101, notification)
            running = true
            worker = Thread({ monitor() }, "McmHomeMonitor").also { it.start() }
        }
        if (intent?.getBooleanExtra("open_at_boot", false) == true) {
            bootAttempt = 0
            bootHandler.removeCallbacks(bootLaunch)
            bootHandler.post(bootLaunch)
        }
        return START_STICKY
    }

    private fun monitor() {
        while (running) {
            val since = System.currentTimeMillis() / 1000.0
            try {
                val process = ProcessBuilder("logcat", "-b", "system", "-T", "1", "-v", "epoch", "ActivityManager:I", "*:S")
                    .redirectErrorStream(true).start()
                reader = process
                if (!running) { process.destroy(); return }
                Log.i(TAG, "Monitor ready")
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val timestamp = line.trimStart().substringBefore(' ').toDoubleOrNull() ?: 0.0
                        if (running && timestamp >= since && FireHomeEvent.isHome(line) && enabled(this) &&
                            System.currentTimeMillis() >= prefs(this).getLong("pause_until", 0)) {
                            Log.i(TAG, "Home detected")
                            startActivity(Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Monitor interrupted: ${e.javaClass.simpleName}")
            } finally { reader?.destroy(); reader = null }
            if (running) try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    override fun onDestroy() {
        running = false
        bootHandler.removeCallbacks(bootLaunch)
        reader?.destroy()
        worker?.interrupt()
        stopForeground(true)
        Log.i(TAG, "Monitor stopped")
        super.onDestroy()
    }

    companion object {
        const val TAG = "McmFireHome"
        private const val CHANNEL = "fire_home"
        fun supported() = Build.MANUFACTURER.equals("Amazon", true) && Build.VERSION.SDK_INT in 25..28
        fun prefs(context: Context): android.content.SharedPreferences {
            val storage = context.createDeviceProtectedStorageContext()
            // Move the initial implementation's preference once, while credential storage is available.
            if (!storage.getSharedPreferences("fire_home", Context.MODE_PRIVATE).contains("enabled") &&
                context.getSystemService(android.os.UserManager::class.java).isUserUnlocked) {
                storage.moveSharedPreferencesFrom(context, "fire_home")
            }
            return storage.getSharedPreferences("fire_home", Context.MODE_PRIVATE)
        }
        fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
        fun startIfEnabled(context: Context, openAtBoot: Boolean = false) {
            if (!supported() || !enabled(context) || context.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(context, FireHomeService::class.java).putExtra("open_at_boot", openAtBoot)
            // Android 8+ only lets a background app start a service that goes to the foreground at once.
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}

object FireHomeEvent {
    fun isHome(line: String) = line.contains("START u0 {") &&
        line.contains("android.intent.category.HOME") &&
        (line.contains("cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext ") ||
            line.contains("cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext}"))
}

class FireHomeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED || intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i(FireHomeService.TAG, "Restart after ${intent.action}")
            FireHomeService.startIfEnabled(context, intent.action != Intent.ACTION_MY_PACKAGE_REPLACED)
        }
    }
}
