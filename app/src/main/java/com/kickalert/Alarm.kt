package com.kickalert

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Loud siren on the ALARM volume channel, loops until stopped (or 2 minutes). */
object Alarm {
    private var track: AudioTrack? = null
    private var appCtx: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    @Synchronized
    fun start(ctx: Context) {
        if (track != null) return
        appCtx = ctx.applicationContext
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        } catch (_: Exception) {
        }
        try {
            val rate = 22050
            val freqs = intArrayOf(1000, 1400, 1800, 1400, 1800, 1400, 1800)
            val toneLen = (rate * 0.14).toInt()
            val gap = (rate * 0.4).toInt()
            val data = ShortArray(freqs.size * toneLen + gap)
            var p = 0
            for (f in freqs) {
                for (i in 0 until toneLen) {
                    val fade = minOf(1.0, i / 200.0, (toneLen - i) / 200.0)
                    data[p++] = (30000 * fade * Math.sin(2 * Math.PI * f * i / rate)).toInt().toShort()
                }
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(data, 0, data.size)
            t.setLoopPoints(0, data.size, -1)
            t.play()
            track = t
        } catch (e: Exception) {
            Bus.info = "Sound problem: ${e.message}"
        }
        vibrate(true)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 120_000L)
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(autoStop)
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
        vibrate(false)
        Bus.banner = ""
    }

    /** Stops the siren and removes the "is LIVE" notifications (keeps the permanent one, id 1). */
    fun stopAll(ctx: Context) {
        stop()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            for (n in nm.activeNotifications) {
                if (n.id != 1) nm.cancel(n.id)
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(on: Boolean) {
        try {
            val v = appCtx?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (on) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300), 0))
            } else {
                v.cancel()
            }
        } catch (_: Exception) {
        }
    }
}
