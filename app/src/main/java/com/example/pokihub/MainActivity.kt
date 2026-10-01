package com.example.pokihub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val homeUrl = "file:///android_asset/home.html"
    private val favUrl = "file:///android_asset/home.html#fav"
    private val pokiUrl = "https://poki.com/id"

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var main: LinearLayout
    private lateinit var progress: ProgressBar
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private val prefs by lazy { getSharedPreferences("fav", MODE_PRIVATE) }

    inner class Bridge {
        @JavascriptInterface
        fun getFavs(): String = prefs.getString("list", "[]") ?: "[]"

        @JavascriptInterface
        fun removeFav(url: String) {
            val old = JSONArray(getFavs())
            val n = JSONArray()
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                if (o.getString("u") != url) n.put(o)
            }
            prefs.edit().putString("list", n.toString()).apply()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0f1020")
        window.navigationBarColor = Color.parseColor("#181a30")

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0f1020"))

        main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 8)
        }
        main.addView(progress)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#0f1020"))
        }
        main.addView(web)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181a30"))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        bar.addView(navButton("🏠\nBeranda") { web.loadUrl(homeUrl) })
        bar.addView(navButton("🎮\nPoki") { web.loadUrl(pokiUrl) })
        bar.addView(navButton("⭐\nSimpan") { toggleFav() })
        bar.addView(navButton("❤\nFavorit") { web.loadUrl(favUrl) })
        bar.addView(navButton("🔄\nMuat") { web.reload() })
        main.addView(bar)

        root.addView(main, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val host = uri.host ?: ""
                if (uri.scheme == "file" || host == "poki.com" || host.endsWith(".poki.com")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                main.visibility = View.GONE
                setImmersive(true)
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCb?.onCustomViewHidden()
                customCb = null
                main.visibility = View.VISIBLE
                setImmersive(false)
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(homeUrl)
    }

    private fun navButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 18)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    private fun toggleFav() {
        val url = web.url ?: return
        if (url.startsWith("file:")) {
            Toast.makeText(this, "Buka sebuah game dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val list = JSONArray(prefs.getString("list", "[]"))
        val n = JSONArray()
        var existed = false
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.getString("u") == url) existed = true else n.put(o)
        }
        if (!existed) {
            n.put(JSONObject().put("t", web.title ?: url).put("u", url))
        }
        prefs.edit().putString("list", n.toString()).apply()
        Toast.makeText(this, if (existed) "Dihapus dari favorit" else "Disimpan ke favorit", Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun setImmersive(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            SYSTEM_UI_NONE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> web.webChromeClient?.onHideCustomView()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val SYSTEM_UI_NONE = 0
    }
}
