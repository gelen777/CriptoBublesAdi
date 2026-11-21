package com.example.cryptobubbles

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var web: WebView
    // Живое обновление: свежая версия интерфейса берётся с GitHub, без переустановки APK
    private val updUrl = "https://raw.githubusercontent.com/gelen777/CriptoBublesAdi/main/app/src/main/assets/index.html"
    private val base = "file:///android_asset/index.html"
    private val cache by lazy { File(filesDir, "index.html") }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // после установки нового APK старый скачанный интерфейс выбрасываем
        val prefs = getSharedPreferences("cb", MODE_PRIVATE)
        val inst = try { packageManager.getPackageInfo(packageName, 0).lastUpdateTime } catch (e: Exception) { 0L }
        if (prefs.getLong("inst", -1L) != inst) { cache.delete(); prefs.edit().putLong("inst", inst).apply() }

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B0E14"))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (!r.isForMainFrame || r.url.scheme == "file") return false
                startActivity(Intent(Intent.ACTION_VIEW, r.url))
                return true
            }
        }
        setContentView(web)
        loadApp()
        checkUpdate(false)
    }

    private fun appHtml(): String =
        if (cache.exists() && cache.length() > 50000) cache.readText()
        else assets.open("index.html").bufferedReader().use { it.readText() }

    private fun loadApp() {
        // тот же адрес страницы, что и раньше — настройки и списки сохраняются
        web.loadDataWithBaseURL(base, appHtml(), "text/html", "utf-8", base)
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun checkUpdate(manual: Boolean) {
        Thread {
            try {
                val c = (URL(updUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 30000
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val ok = c.responseCode == 200 && txt.length > 50000 && txt.contains("cbBack")
                if (ok && txt != appHtml()) {
                    cache.writeText(txt)
                    js("window.cbUpd&&cbUpd(1)")
                } else if (manual) js("window.cbUpd&&cbUpd(0)")
            } catch (e: Exception) {
                if (manual) js("window.cbUpd&&cbUpd(-1)")
            }
        }.start()
    }

    inner class Bridge {
        @JavascriptInterface fun reload() { runOnUiThread { loadApp() } }
        @JavascriptInterface fun check() { checkUpdate(true) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){return window.cbBack?window.cbBack():0})()"
        ) { r -> if (r != "1") finish() }
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
