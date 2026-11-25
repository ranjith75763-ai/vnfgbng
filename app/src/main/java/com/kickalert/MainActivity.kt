package com.kickalert

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var bannerView: TextView
    private lateinit var infoView: TextView
    private lateinit var streamersEdit: EditText

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun prefs() = getSharedPreferences("kick", Context.MODE_PRIVATE)

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun button(text: String, bg: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(bg)
        b.textSize = 16f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun sendToService(action: String) {
        val i = Intent(this, WatchService::class.java)
        i.action = action
        ContextCompat.startForegroundService(this, i)
    }

    private fun startWatching() {
        prefs().edit().putString("streamers", streamersEdit.text.toString()).apply()
        // restart so a changed streamer list is picked up
        sendToService(WatchService.ACTION_STOP_ALL)
        handler.postDelayed({ sendToService(WatchService.ACTION_START) }, 600)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val bg = Color.parseColor("#14161a")
        val dim = Color.parseColor("#9aa0a8")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.setPadding(dp(20), dp(36), dp(20), dp(24))

        root.addView(label("Kick Live Alert", 24f, Color.WHITE, true))
        root.addView(label("checking every streamer every second", 13f, dim))

        val hdr = label("Streamers (separate with commas)", 13f, dim)
        hdr.setPadding(0, dp(16), 0, 0)
        root.addView(hdr)

        streamersEdit = EditText(this)
        streamersEdit.setText(prefs().getString("streamers", DEFAULT_STREAMERS))
        streamersEdit.setTextColor(Color.WHITE)
        streamersEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        root.addView(streamersEdit)

        root.addView(button("SAVE & RESTART WATCHING", Color.parseColor("#2a2e35")) { startWatching() })

        statusView = label("", 15f, Color.WHITE)
        statusView.typeface = Typeface.MONOSPACE
        statusView.setPadding(0, dp(16), 0, dp(8))
        root.addView(statusView)

        bannerView = label("", 18f, Color.parseColor("#2ecc71"), true)
        root.addView(bannerView)

        root.addView(button("STOP SOUND", Color.parseColor("#e74c3c")) { Alarm.stopAll(this) })
        root.addView(button("TEST ALERT (sound + popup)", Color.parseColor("#2a2e35")) {
            sendToService(WatchService.ACTION_TEST)
        })
        root.addView(button("STOP WATCHING", Color.parseColor("#2a2e35")) {
            sendToService(WatchService.ACTION_STOP_ALL)
        })

        infoView = label("", 12f, dim)
        infoView.setPadding(0, dp(12), 0, 0)
        root.addView(infoView)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.addView(root)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // start watching right away when the app opens
        sendToService(WatchService.ACTION_START)

        // once: ask Android not to put the app to sleep (needed for per-second checks with screen off)
        if (!prefs().getBoolean("battery_asked", false)) {
            prefs().edit().putBoolean("battery_asked", true).apply()
            handler.postDelayed({
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                }
            }, 1500)
        }
    }

    private fun render() {
        val sb = StringBuilder()
        for (s in loadStreamers(this)) {
            val st = Bus.status[s]
            val dot = if (st != null && st.startsWith("LIVE")) "\uD83D\uDFE2 " else "\u26AA "
            val text = st ?: (if (Bus.running) "checking..." else "not started")
            sb.append(dot).append(s.padEnd(16)).append(text).append("\n")
        }
        statusView.text = sb.toString()
        bannerView.text = if (Bus.banner.isNotEmpty()) "\u26A1 " + Bus.banner else ""
        infoView.text = Bus.info
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
