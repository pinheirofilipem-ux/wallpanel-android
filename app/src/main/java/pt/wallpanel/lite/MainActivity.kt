package pt.wallpanel.lite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
    private val reload = Runnable { web.reload() }

    // 5 toques no canto superior esquerdo abrem as definições.
    private var cornerTaps = 0
    private var firstTapAt = 0L

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
                override fun onReceivedError(view: WebView, r: WebResourceRequest, e: WebResourceError) {
                    // Rede/HA em baixo: tenta outra vez dentro de 10 s.
                    if (r.isForMainFrame) {
                        handler.removeCallbacks(reload)
                        handler.postDelayed(reload, 10_000)
                    }
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
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Se não houver bloqueio seguro (PIN), isto remove o "deslizar para desbloquear".
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)

        PanelState.listener = { applySleeping(it) }
        applySleeping(PanelState.sleeping)

        val url = prefs.url
        if (url.isNotBlank() && url != loadedUrl) {
            loadedUrl = url
            web.loadUrl(url)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            MotionService.start(this)
        }
        MotionService.poke()
    }

    override fun onPause() {
        PanelState.listener = null
        super.onPause()
    }

    override fun onDestroy() {
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
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val corner = 80 * resources.displayMetrics.density
            if (ev.x < corner && ev.y < corner) {
                val now = SystemClock.elapsedRealtime()
                if (cornerTaps == 0 || now - firstTapAt > 3000) { cornerTaps = 0; firstTapAt = now }
                if (++cornerTaps >= 5) {
                    cornerTaps = 0
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return true
                }
            }
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
