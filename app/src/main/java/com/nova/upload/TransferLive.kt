package com.nova.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object TransferLive {
    const val NOTE_ID = 41
    private const val CHANNEL = "nova_transfer"

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile var busy = false
    @Volatile var cancelled = false
    var title = "در حال ارسال"
    var detail = ""
    var percent = 0
    var indeterminate = true
    var listener: ((String, String, Boolean, Int) -> Unit)? = null

    private var wakeLock: PowerManager.WakeLock? = null

    fun hold(context: Context) {
        val app = context.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:upload").also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        if (!lock.isHeld) lock.acquire(45 * 60 * 1000L)
        val intent = Intent(app, TransferService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun release(context: Context) {
        val app = context.applicationContext
        wakeLock?.let { if (it.isHeld) it.release() }
        app.stopService(Intent(app, TransferService::class.java))
    }

    fun note(context: Context, title: String, detail: String, indeterminate: Boolean, percent: Int) {
        this.title = title.ifBlank { "در حال ارسال" }
        this.detail = detail
        this.indeterminate = indeterminate
        this.percent = percent.coerceIn(0, 100)
        val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        nm.notify(NOTE_ID, build(context.applicationContext))
        listener?.invoke(this.title, this.detail, this.indeterminate, this.percent)
    }

    fun build(context: Context): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        ensureChannel(nm)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = detail.ifBlank { "فایل در حال ارسال است" }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
        if (indeterminate) builder.setProgress(0, 0, true)
        else builder.setProgress(100, percent, false)
        return builder.build()
    }

    private fun ensureChannel(nm: NotificationManager?) {
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ارسال فایل", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "ادامه ارسال وقتی صفحه خاموش است"
            }
        )
    }
}

class TransferService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val note = TransferLive.build(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(TransferLive.NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(TransferLive.NOTE_ID, note)
        }
        return START_STICKY
    }
}
