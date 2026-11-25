package com.kickalert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Runs in the background (even with the screen off) and checks every streamer every second. */
class WatchService : Service() {

    companion object {
        const val ACTION_START = "com.kickalert.START"
        const val ACTION_STOP_ALL = "com.kickalert.STOP_ALL"
        const val ACTION_TEST = "com.kickalert.TEST"
        const val CH_WATCH = "watch"
        const val CH_ALERT = "alert"
        const val CHECK_MS = 1000L
        const val COOLDOWN_MS = 120_000L
        const val BACKOFF_MS = 10_000L
    }

    private val running = AtomicBoolean(false)
    private val threads = mutableListOf<Thread>()
    private val live = ConcurrentHashMap<String, Boolean>()
    private val lastAlert = ConcurrentHashMap<String, Long>()
    private val confirmed = ConcurrentHashMap<String, Boolean>()
    private var wake: PowerManager.WakeLock? = null
    private var fetcher: Fetcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> {
                startWatching()
                alert("TEST", true)
            }
            else -> startWatching()
        }
        return START_STICKY
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val watch = NotificationChannel(CH_WATCH, "Watching", NotificationManager.IMPORTANCE_LOW)
        val alertCh = NotificationChannel(CH_ALERT, "Live alerts", NotificationManager.IMPORTANCE_HIGH)
        alertCh.setSound(null, null)          // the siren is played by the app itself
        alertCh.enableVibration(false)
        nm.createNotificationChannel(watch)
        nm.createNotificationChannel(alertCh)
    }

    private fun ensureForeground() {
        createChannels()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH_WATCH)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Kick Live Alert")
            .setContentText("Checking your streamers every second")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    private fun startWatching() {
        if (!running.compareAndSet(false, true)) return
        Bus.running = true
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kickalert:watch")
            wake?.acquire()
        } catch (_: Exception) {
        }
        val f = Fetcher(this)
        fetcher = f
        val list = loadStreamers(this)
        for ((i, slug) in list.withIndex()) {
            Bus.status[slug] = "checking..."
            val t = Thread { watch(f, slug, i, list.size) }
            t.isDaemon = true
            threads.add(t)
            t.start()
        }
    }

    private fun shutdown() {
        running.set(false)
        Bus.running = false
        for (t in threads) t.interrupt()
        threads.clear()
        live.clear()
        Alarm.stopAll(this)
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (_: Exception) {
        }
        wake = null
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun sleepUntil(next: Long) {
        val d = next - SystemClock.elapsedRealtime()
        if (d > 0) Thread.sleep(d)
    }

    /** One thread per streamer: a check every second, forever. Errors never stop it. */
    private fun watch(f: Fetcher, slug: String, index: Int, count: Int) {
        val names = listOf(slug.lowercase(), slug.lowercase().replace('_', '-')).distinct()
        var ni = 0
        var next = SystemClock.elapsedRealtime() + index * CHECK_MS / maxOf(1, count)
        try {
            sleepUntil(next)
            while (running.get()) {
                try {
                    val isLive = f.isLive(names[ni])
                    confirmed[slug] = true
                    onResult(slug, isLive)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: KickHttpException) {
                    if (e.code == 404 && names.size > 1 && confirmed[slug] != true) {
                        ni = (ni + 1) % names.size
                        Bus.status[slug] = "not found, retrying"
                    } else if (e.code == 429 || e.code == 403) {
                        Bus.status[slug] = "HTTP ${e.code}, pausing"
                        Thread.sleep(BACKOFF_MS)
                        next = SystemClock.elapsedRealtime()
                    } else {
                        Bus.status[slug] = "HTTP ${e.code}"
                    }
                } catch (e: Exception) {
                    Bus.status[slug] = "no connection"
                }
                next += CHECK_MS
                val now = SystemClock.elapsedRealtime()
                if (next < now) next = now
                sleepUntil(next)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun onResult(slug: String, isLive: Boolean) {
        val was = live[slug]
        live[slug] = isLive
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        Bus.status[slug] = if (isLive) "LIVE  $t" else "offline  $t"
        if (isLive && was != true) alert(slug, false)
    }

    private fun alert(slug: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - (lastAlert[slug] ?: 0L) < COOLDOWN_MS) return
        lastAlert[slug] = now
        Bus.banner = "$slug is LIVE"
        Alarm.start(this)
        notifyLive(slug)
    }

    private fun notifyLive(slug: String) {
        val id = (slug.hashCode() and 0x7fffffff) + 10
        val url = "https://kick.com/" + slug.lowercase().replace('_', '-')
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openStream = PendingIntent.getActivity(
            this, id, Intent(Intent.ACTION_VIEW, Uri.parse(url)), flags
        )
        val full = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val stop = PendingIntent.getBroadcast(
            this, 3, Intent(this, StopReceiver::class.java), flags
        )
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$slug is LIVE on Kick")
            .setContentText("Tap to open the stream")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openStream)
            .setFullScreenIntent(full, true)
            .addAction(0, "STOP SOUND", stop)
            .addAction(0, "OPEN", openStream)
            .build()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        } catch (_: Exception) {
        }
    }
}
