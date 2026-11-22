package com.example.cryptobubbles

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var alive = true          // false после onDestroy — фоновые потоки больше не трогают WebView
    private var lastBack = 0L         // время прошлого «Назад» на главном экране (выход только двойным нажатием)
    private var backBusy = false      // защита от двойного срабатывания, пока JS ещё отвечает
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

        createWeb()
        loadApp()
        checkUpdate(false)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWeb() {
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
                // нет браузера / приложения TradingView — раньше это роняло приложение
                try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) }
                catch (e: Exception) { Toast.makeText(this@MainActivity, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
                return true
            }
            // движок страницы упал (часто от нехватки памяти) — по умолчанию Android закрывает всё приложение.
            // Вместо этого пересоздаём WebView и открываем интерфейс заново.
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail?): Boolean {
                if (!alive) return true
                try { (v.parent as? ViewGroup)?.removeView(v); v.destroy() } catch (e: Exception) {}
                createWeb(); loadApp()
                return true
            }
        }
        setContentView(web)
    }

    private fun appHtml(): String =
        if (cache.exists() && cache.length() > 50000) cache.readText()
        else assets.open("index.html").bufferedReader().use { it.readText() }

    private fun loadApp() {
        // тот же адрес страницы, что и раньше — настройки и списки сохраняются
        web.loadDataWithBaseURL(base, appHtml(), "text/html", "utf-8", base)
    }

    private fun js(code: String) = runOnUiThread { if (alive) try { web.evaluateJavascript(code, null) } catch (e: Exception) {} }

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
        @JavascriptInterface fun reload() { runOnUiThread { if (alive) loadApp() } }
        @JavascriptInterface fun check() { checkUpdate(true) }
        /* сетевой запрос из приложения, без ограничений браузера (CORS): нужен для истории фьючерсов */
        @JavascriptInterface fun get(id: Int, url: String, ms: Int) {
            Thread {
                var code = 0; var txt = ""
                try {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = ms; readTimeout = ms * 2
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CryptoBubbles")
                        setRequestProperty("Accept", "application/json")
                    }
                    code = c.responseCode
                    txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) { code = 0; txt = e.toString() }
                js("window.__nr&&__nr($id,$code," + JSONObject.quote(txt) + ")")
            }.start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (backBusy) return
        backBusy = true
        // интерфейс сам закрывает верхнее окно (настройки, монета, график…) и отвечает "1".
        // Приложение закрывается ТОЛЬКО если интерфейс ответил "0" (уже главный экран) и «Назад» нажали дважды.
        // Любой другой ответ (ошибка, страница ещё грузится) больше НЕ закрывает приложение.
        web.evaluateJavascript(
            "(function(){try{return window.cbBack?window.cbBack():0}catch(e){return 2}})()"
        ) { r ->
            backBusy = false
            if (r == "1") return@evaluateJavascript
            if (r != "0") {
                // ответ непонятный — на всякий случай просто прячем все всплывающие окна
                web.evaluateJavascript("(function(){try{window.cbHideAll&&cbHideAll()}catch(e){}})()", null)
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastBack < 2000) finish()
            else {
                lastBack = now
                Toast.makeText(this, "Нажмите «Назад» ещё раз, чтобы выйти", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() { super.onPause(); if (alive) web.onPause() }
    override fun onResume() { super.onResume(); if (alive) web.onResume() }

    override fun onDestroy() {
        alive = false
        try { web.destroy() } catch (e: Exception) {}
        super.onDestroy()
    }
}
