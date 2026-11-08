package pt.wallpanel.lite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var blackout: View
    private var loadedUrl = ""
    private val handler = Handler(Looper.getMainLooper())
    private var pageFailed = false
    private var offline = false

    /** Recarrega a página: se a última carga falhou, volta a pedir o URL; senão reload normal. */
    private val reload = Runnable {
        if (pageFailed) web.loadUrl(loadedUrl) else web.reload()
    }

    // Dois dedos premidos > 2 s abrem as definições.
    private val openSettings = Runnable { startActivity(Intent(this, SettingsActivity::class.java)) }
    private var gestureX = 0f
    private var gestureY = 0f

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { offline = true }
        override fun onAvailable(network: Network) {
            // Voltou a rede (Wi-Fi caiu e regressou): recarrega logo se a página tinha falhado ou ficou offline.
            if (offline || pageFailed) {
                offline = false
                handler.removeCallbacks(reload)
                handler.postDelayed(reload, 1500)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.url.isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView, h: SslErrorHandler, e: SslError) {
                    if (prefs.ignoreSsl) h.proceed() else h.cancel()
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageFailed = false
                }
                override fun onReceivedError(view: WebView, r: WebResourceRequest, e: WebResourceError) {
                    // Rede/HA em baixo: tenta outra vez dentro de 10 s (e logo que a rede voltar).
                    if (r.isForMainFrame) markFailed()
                }
                override fun onReceivedHttpError(view: WebView, r: WebResourceRequest, e: android.webkit.WebResourceResponse) {
                    if (r.isForMainFrame && e.statusCode >= 500) markFailed()
                }
            }
        }
        blackout = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(blackout, FrameLayout.LayoutParams(-1, -1))
        })

        requestRuntimePermissions()

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(netCallback, handler)
    }

    private fun markFailed() {
        pageFailed = true
        handler.removeCallbacks(reload)
        handler.postDelayed(reload, 10_000)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Se não houver bloqueio seguro (PIN), isto remove o "deslizar para desbloquear".
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)

        PanelState.listener = { applySleeping(it) }
        applySleeping(PanelState.sleeping)

        val url = prefs.url
        if (prefs.reloadRequested) {
            // Pedido nas definições: limpa cache (mantém sessões/cookies) e recarrega do zero.
            prefs.reloadRequested = false
            web.clearCache(true)
            web.clearHistory()
            if (url.isNotBlank()) { loadedUrl = url; web.loadUrl(url) }
        } else if (url.isNotBlank() && url != loadedUrl) {
            loadedUrl = url
            web.loadUrl(url)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            MotionService.start(this)
        }
        MotionService.poke()
        if (pageFailed) { handler.removeCallbacks(reload); handler.postDelayed(reload, 1000) }
    }

    override fun onPause() {
        handler.removeCallbacks(openSettings)
        PanelState.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback)
        } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        MotionService.poke()
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(openSettings)
                if (ev.pointerCount == 2) {
                    gestureX = ev.getX(0); gestureY = ev.getY(0)
                    handler.postDelayed(openSettings, 2000)
                }
            }
            MotionEvent.ACTION_MOVE -> if (ev.pointerCount == 2) {
                // Se os dedos andarem (scroll/zoom), não é o gesto.
                val slop = ViewConfiguration.get(this).scaledTouchSlop * 4
                if (Math.abs(ev.getX(0) - gestureX) > slop || Math.abs(ev.getY(0) - gestureY) > slop) {
                    handler.removeCallbacks(openSettings)
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                handler.removeCallbacks(openSettings)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Fallback sem permissão de administrador: ecrã preto + brilho no mínimo absoluto. */
    private fun applySleeping(sleeping: Boolean) {
        blackout.visibility = if (sleeping) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (sleeping) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            MotionService.start(this)
        }
    }
}
