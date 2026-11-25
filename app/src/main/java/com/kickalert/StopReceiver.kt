package com.kickalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the STOP SOUND button on the notification. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarm.stopAll(context)
    }
}
