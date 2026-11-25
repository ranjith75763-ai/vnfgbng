package com.kickalert

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

const val DEFAULT_STREAMERS = "urosh, ChiTown_Chris, oxygambler, theladdoo, itsjakeuwu"

/** Shared state between the background service and the window. */
object Bus {
    val status = ConcurrentHashMap<String, String>()
    @Volatile var info: String = ""
    @Volatile var running: Boolean = false
    @Volatile var banner: String = ""
}

fun loadStreamers(ctx: Context): List<String> {
    val raw = ctx.getSharedPreferences("kick", Context.MODE_PRIVATE)
        .getString("streamers", DEFAULT_STREAMERS) ?: DEFAULT_STREAMERS
    return raw.split(",", "\n", " ")
        .map { it.trim().removePrefix("https://kick.com/").trim('/') }
        .filter { it.isNotEmpty() }
        .distinct()
}
