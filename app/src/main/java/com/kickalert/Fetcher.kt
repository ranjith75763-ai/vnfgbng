package com.kickalert

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class KickHttpException(val code: Int) : Exception("HTTP $code")

/**
 * Asks Kick "is this channel live?".
 * First tries a normal request. If Kick blocks it (403, Cloudflare), switches to a hidden
 * real browser (WebView) which Kick treats like a normal visitor.
 */
class Fetcher(private val ctx: Context) {
    private val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile private var webMode = false
    @Volatile private var web: WebView? = null
    private val queues = ConcurrentHashMap<String, LinkedBlockingQueue<String>>()
    private val main = Handler(Looper.getMainLooper())

    inner class Bridge {
        @JavascriptInterface
        fun result(id: String, data: String) {
            queues[id]?.offer(data)
        }
    }

    fun isLive(name: String): Boolean {
        val body = if (webMode) {
            viaWeb(name)
        } else {
            try {
                viaHttp(name)
            } catch (e: KickHttpException) {
                if (e.code == 403) {
                    enableWeb()
                    viaWeb(name)
                } else {
                    throw e
                }
            }
        }
        val json = JSONObject(body)
        if (!json.has("livestream")) throw Exception("unexpected reply from Kick")
        return !json.isNull("livestream")
    }

    private fun viaHttp(name: String): String {
        val req = Request.Builder()
            .url("https://kick.com/api/v2/channels/$name")
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .header("Referer", "https://kick.com/")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw KickHttpException(r.code)
            return r.body?.string() ?: throw Exception("empty reply")
        }
    }

    @Synchronized
    private fun enableWeb() {
        if (web == null) {
            Bus.info = "Kick blocked normal requests - using browser mode"
            val ready = CountDownLatch(1)
            main.post {
                val w = WebView(ctx.applicationContext)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.userAgentString = ua
                w.addJavascriptInterface(Bridge(), "KickBridge")
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        ready.countDown()
                    }
                }
                web = w
                w.loadUrl("https://kick.com/")
            }
            ready.await(20, TimeUnit.SECONDS)
            Thread.sleep(2000) // let any browser check finish
        }
        webMode = true
    }

    private fun viaWeb(name: String): String {
        val id = UUID.randomUUID().toString()
        val q = LinkedBlockingQueue<String>()
        queues[id] = q
        val js = "(async function(){try{" +
            "var r=await fetch('/api/v2/channels/$name',{headers:{'Accept':'application/json'}});" +
            "var t=await r.text();KickBridge.result('$id',r.status+'|'+t);" +
            "}catch(e){KickBridge.result('$id','0|'+e);}})();"
        main.post { web?.evaluateJavascript(js, null) }
        val out = q.poll(10, TimeUnit.SECONDS)
        queues.remove(id)
        if (out == null) throw Exception("browser check timed out")
        val i = out.indexOf('|')
        val code = out.substring(0, i).toInt()
        if (code != 200) throw KickHttpException(code)
        return out.substring(i + 1)
    }
}
