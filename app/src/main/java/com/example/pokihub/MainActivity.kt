package com.example.pokihub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val homeUrl = "file:///android_asset/home.html"
    private val favUrl = "file:///android_asset/home.html#fav"
    private val pokiUrl = "https://poki.com/id"
    private val offlineUrl = "file:///android_asset/offline.html"

    // Palet warna (sinkron dengan home.html)
    private val cBg = Color.parseColor("#0b0c1e")
    private val cBar = Color.parseColor("#13152b")
    private val cLine = Color.parseColor("#1f2245")
    private val cAccent = Color.parseColor("#a29bfe")
    private val cAccent2 = Color.parseColor("#22d3ee")
    private val cMuted = Color.parseColor("#7f82a8")
    private val cStar = Color.parseColor("#ffd166")

    private class NavItem(val view: LinearLayout, val icon: ImageView, val label: TextView)

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var main: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var bar: FrameLayout
    private lateinit var indicator: View
    private lateinit var toastView: TextView
    private lateinit var chrome: WebChromeClient

    private val navItems = ArrayList<NavItem>()
    private val tabIdx = intArrayOf(0, 1, 3)
    private var activeIndex = -1
    private var loadDone = true

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private val prefs by lazy { getSharedPreferences("fav", MODE_PRIVATE) }

    // ---------- Bridge ke home.html ----------
    inner class Bridge {
        @JavascriptInterface
        fun getFavs(): String = readList("list").toString()

        @JavascriptInterface
        fun getRecent(): String = readList("recent").toString()

        @JavascriptInterface
        fun removeFav(url: String) {
            val old = readList("list")
            val n = JSONArray()
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                if (o.optString("u") != url) n.put(o)
            }
            prefs.edit().putString("list", n.toString()).apply()
            runOnUiThread { updateStar(web.url) }
        }

        @JavascriptInterface
        fun clearRecent() {
            prefs.edit().putString("recent", "[]").apply()
        }

        @JavascriptInterface
        fun tick() {
            runOnUiThread { web.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        }
    }

    // ---------- Lifecycle ----------
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBar

        root = FrameLayout(this)
        root.setBackgroundColor(cBg)

        main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            alpha = 0f
            progressTintList = ColorStateList.valueOf(cAccent2)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(3))
        }
        main.addView(progress)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setBackgroundColor(cBg)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        main.addView(web)

        main.addView(View(this).apply {
            setBackgroundColor(cLine)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
        })
        buildNavBar()
        main.addView(bar)

        root.addView(main, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        toastView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#ee262a52"))
                cornerRadius = dp(22).toFloat()
                setStroke(1, Color.parseColor("#3d4280"))
            }
            visibility = View.GONE
        }
        root.addView(toastView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(84)
        })

        setContentView(root)

        if (savedInstanceState == null) {
            bar.translationY = dp(90).toFloat()
            bar.post {
                bar.animate().translationY(0f).setDuration(520)
                    .setInterpolator(OvershootInterpolator(0.8f)).start()
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (uri.scheme == "file" || isPoki(uri.toString())) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                }
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                refreshChrome(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                refreshChrome(url)
                recordRecent(view.title, url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && request.url.scheme != "file") {
                    view.loadUrl(offlineUrl + "?u=" + Uri.encode(request.url.toString()))
                }
            }
        }

        chrome = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    progress.animate().cancel()
                    if (loadDone) {
                        loadDone = false
                        progress.setProgress(0, false)
                    }
                    progress.alpha = 1f
                    progress.setProgress(newProgress * 10, true)
                } else {
                    loadDone = true
                    progress.setProgress(1000, true)
                    progress.animate().alpha(0f).setDuration(350).start()
                }
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                root.addView(view, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                main.visibility = View.GONE
                setImmersiveMode(true)
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCb?.onCustomViewHidden()
                customCb = null
                main.visibility = View.VISIBLE
                setImmersiveMode(false)
            }
        }
        web.webChromeClient = chrome

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(homeUrl)
        }
    }

    // ---------- UI helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun ripple(radiusDp: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun navItem(iconRes: Int, label: String, onClick: () -> Unit): NavItem {
        val iv = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(cMuted)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(cMuted)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(2), dp(4), dp(2))
            }
            background = ripple(14)
            addView(iv)
            addView(tv)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(180)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                }
                false
            }
        }
        return NavItem(v, iv, tv)
    }

    private fun buildNavBar() {
        navItems.add(navItem(R.drawable.ic_home, "Beranda") { web.loadUrl(homeUrl) })
        navItems.add(navItem(R.drawable.ic_game, "Poki") { web.loadUrl(pokiUrl) })
        navItems.add(navItem(R.drawable.ic_star, "Simpan") { toggleFav() })
        navItems.add(navItem(R.drawable.ic_heart, "Favorit") { web.loadUrl(favUrl) })
        navItems.add(navItem(R.drawable.ic_refresh, "Muat") {
            navItems[4].icon.animate().rotationBy(360f).setDuration(520)
                .setInterpolator(DecelerateInterpolator()).start()
            web.reload()
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        for (n in navItems) row.addView(n.view)

        indicator = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cAccent, cAccent2)
            ).apply { cornerRadius = dp(2).toFloat() }
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(3)).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        }

        bar = FrameLayout(this).apply {
            setBackgroundColor(cBar)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(row)
            addView(indicator)
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, orr, _ ->
                if (r - l != orr - ol) moveIndicator(activeIndex, false)
            }
        }
    }

    private fun tabFor(url: String?): Int = when {
        url == null -> -1
        url.contains("offline.html") -> -1
        url.startsWith("file:") && url.contains("#fav") -> 3
        url.startsWith("file:") -> 0
        isPoki(url) -> 1
        else -> -1
    }

    private fun refreshChrome(url: String?) {
        setActive(tabFor(url))
        updateStar(url)
    }

    private fun setActive(idx: Int) {
        if (idx == activeIndex) return
        activeIndex = idx
        for (i in tabIdx) {
            val sel = i == idx
            val ni = navItems[i]
            val c = if (sel) cAccent else cMuted
            ni.icon.setColorFilter(c)
            ni.label.setTextColor(c)
            val s = if (sel) 1.15f else 1f
            ni.icon.animate().scaleX(s).scaleY(s).setDuration(200).start()
        }
        moveIndicator(idx, true)
    }

    private fun moveIndicator(idx: Int, animate: Boolean) {
        if (idx < 0) {
            indicator.animate().alpha(0f).setDuration(150).start()
            return
        }
        val w = bar.width
        if (w == 0) {
            bar.post { moveIndicator(idx, animate) }
            return
        }
        val slot = w / navItems.size.toFloat()
        val x = slot * idx + (slot - dp(32)) / 2f
        if (!animate || indicator.alpha == 0f) {
            indicator.animate().cancel()
            indicator.translationX = x
            indicator.alpha = 1f
        } else {
            indicator.animate().translationX(x).alpha(1f).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun updateStar(url: String?) {
        val fav = isFav(url)
        val ni = navItems[2]
        val c = if (fav) cStar else cMuted
        ni.icon.setImageResource(if (fav) R.drawable.ic_star_filled else R.drawable.ic_star)
        ni.icon.setColorFilter(c)
        ni.label.setTextColor(c)
        ni.label.text = if (fav) "Tersimpan" else "Simpan"
    }

    private fun showToast(msg: String) {
        toastView.removeCallbacks(hideToast)
        toastView.animate().cancel()
        toastView.text = msg
        toastView.visibility = View.VISIBLE
        toastView.alpha = 0f
        toastView.translationY = dp(16).toFloat()
        toastView.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
        toastView.postDelayed(hideToast, 1800)
    }

    private val hideToast = Runnable {
        toastView.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(200)
            .withEndAction { if (toastView.alpha == 0f) toastView.visibility = View.GONE }
            .start()
    }

    // ---------- Data ----------
    private fun isPoki(url: String?): Boolean {
        if (url == null) return false
        val host = Uri.parse(url).host ?: return false
        return host == "poki.com" || host.endsWith(".poki.com")
    }

    private fun readList(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun isFav(url: String?): Boolean {
        if (url == null || url.startsWith("file:")) return false
        val l = readList("list")
        for (i in 0 until l.length()) {
            if (l.getJSONObject(i).optString("u") == url) return true
        }
        return false
    }

    private fun cleanTitle(title: String?, url: String): String {
        var t = (title ?: "").trim()
        t = t.replace(Regex("\\s*[|\\-–]\\s*Poki.*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+di\\s+Poki.*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^Mainkan\\s+", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+Online(\\s+Gratis)?$", RegexOption.IGNORE_CASE), "")
        t = t.trim()
        if (t.isEmpty() || t.startsWith("http")) {
            val seg = Uri.parse(url).lastPathSegment ?: "Game"
            t = seg.replace('-', ' ').replaceFirstChar { it.uppercase() }
        }
        return t
    }

    private fun recordRecent(title: String?, url: String?) {
        if (url == null || !isPoki(url) || !url.contains("/g/")) return
        val old = readList("recent")
        val n = JSONArray()
        n.put(JSONObject().put("t", cleanTitle(title, url)).put("u", url))
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.optString("u") != url && n.length() < 10) n.put(o)
        }
        prefs.edit().putString("recent", n.toString()).apply()
    }

    private fun toggleFav() {
        val url = web.url ?: return
        if (url.startsWith("file:")) {
            showToast("Buka sebuah game dulu")
            return
        }
        val list = readList("list")
        val n = JSONArray()
        var existed = false
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("u") == url) existed = true else n.put(o)
        }
        if (!existed) {
            n.put(JSONObject().put("t", cleanTitle(web.title, url)).put("u", url))
        }
        prefs.edit().putString("list", n.toString()).apply()
        updateStar(url)
        val icon = navItems[2].icon
        icon.scaleX = 0.4f
        icon.scaleY = 0.4f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(380)
            .setInterpolator(OvershootInterpolator(3f)).start()
        showToast(if (existed) "Dihapus dari favorit" else "⭐ Disimpan ke favorit")
    }

    @Suppress("DEPRECATION")
    private fun setImmersiveMode(on: Boolean) {
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
            customView != null -> chrome.onHideCustomView()
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
