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
import android.view.animation.DecelerateInterpolator
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val SETTLE_MS = 300L  // espera com tudo preto até a janela/barras/página estabilizarem
        const val FADE_MS = 300L    // duração do fade-in
        const val SLIDE_MS = 320L   // duração da transição horizontal entre painéis
        const val SWITCH_SETTLE_MS = 400L   // espera depois de o novo painel carregar, antes de deslizar
        const val SWITCH_TIMEOUT_MS = 5000L // se o painel não carregar, desliza na mesma
    }

    private lateinit var prefs: Prefs
    private lateinit var bridge: WebBridge
    private lateinit var web: WebView
    private lateinit var container: FrameLayout
    private var standby: WebView? = null
    private var standbyFailed = false
    private var switching = false
    private lateinit var blackout: View
    private var loadedUrl = ""
    private val handler = Handler(Looper.getMainLooper())
    private var pageFailed = false
    private var offline = false
    private var resumed = false

    // Gesto de 3 dedos (swipe horizontal) para trocar de painel.
    private var swipeActive = false
    private var swipeFired = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    /** Faz o fade do preto para o painel (só quando a janela está estável). */
    private val reveal = Runnable {
        if (blackout.visibility == View.VISIBLE && !PanelState.sleeping) {
            blackout.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                blackout.visibility = View.GONE
                blackout.alpha = 1f
            }.start()
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        bridge = WebBridge(this, prefs)
        if (prefs.url.isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = newWebView()
        blackout = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true // enquanto está preto, não deixa toques "cegos" chegarem ao dashboard
        }
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(blackout, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(container)

        requestRuntimePermissions()

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(netCallback, handler)
    }

    /** Cria um WebView configurado. Os callbacks de erro só mexem no estado se for o WebView ativo. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        // Painéis que conhecem esta app (Pine3 Homeview) detetam-na pelo user agent e por window.Pine3Wallpanel.
        settings.userAgentString = "${settings.userAgentString} ${WebBridge.USER_AGENT}"
        addJavascriptInterface(bridge, WebBridge.NAME)
        webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, h: SslErrorHandler, e: SslError) {
                if (prefs.ignoreSsl) h.proceed() else h.cancel()
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (view === web) pageFailed = false else standbyFailed = false
            }
            override fun onReceivedError(view: WebView, r: WebResourceRequest, e: WebResourceError) {
                // Rede/HA em baixo: tenta outra vez dentro de 10 s (e logo que a rede voltar).
                if (r.isForMainFrame) { if (view === web) markFailed() else standbyFailed = true }
            }
            override fun onReceivedHttpError(view: WebView, r: WebResourceRequest, e: android.webkit.WebResourceResponse) {
                if (r.isForMainFrame && e.statusCode >= 500) { if (view === web) markFailed() else standbyFailed = true }
            }
            override fun onPageFinished(view: WebView, url: String) {
                // O painel de destino acabou de carregar: dá um instante para o SPA desenhar e desliza.
                if (view === standby && switching) {
                    handler.removeCallbacks(startSlide)
                    handler.postDelayed(startSlide, SWITCH_SETTLE_MS)
                }
            }
        }
    }

    private fun markFailed() {
        pageFailed = true
        handler.removeCallbacks(reload)
        handler.postDelayed(reload, 10_000)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        cover() // começa sempre tapado; o fade só acontece quando estiver estável
        hideSystemBars()
        // Se não houver bloqueio seguro (PIN), isto remove o "deslizar para desbloquear".
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)

        PanelState.listener = { applySleeping(it); sendEvent(if (it) "sleep" else "wake") }
        PanelState.motionListener = { sendEvent("motion") }
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
        resumed = false
        cover() // já fica tapado para o próximo acordar
        handler.removeCallbacks(openSettings)
        PanelState.listener = null
        PanelState.motionListener = null
        super.onPause()
    }

    override fun onDestroy() {
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback)
        } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        standby?.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { hideSystemBars(); scheduleReveal() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        MotionService.poke()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> swipeActive = false
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(openSettings)
                if (ev.pointerCount == 3) {
                    // Começou o gesto de 3 dedos: cancela o que o WebView tinha iniciado (scroll/zoom)
                    // e passa a engolir os toques até os dedos saírem.
                    swipeActive = true
                    swipeFired = false
                    swipeStartX = centroidX(ev); swipeStartY = centroidY(ev)
                    val c = MotionEvent.obtain(ev)
                    c.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(c)
                    c.recycle()
                    return true
                }
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
        if (swipeActive) {
            if (ev.actionMasked == MotionEvent.ACTION_MOVE && !swipeFired && ev.pointerCount >= 3) {
                val dx = centroidX(ev) - swipeStartX
                val dy = centroidY(ev) - swipeStartY
                if (Math.abs(dx) > 80 * resources.displayMetrics.density && Math.abs(dx) > 1.3f * Math.abs(dy)) {
                    swipeFired = true
                    switchPanel(if (dx < 0) +1 else -1) // deslizar para a esquerda = painel seguinte
                }
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swipeActive = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun centroidX(ev: MotionEvent): Float { var t = 0f; for (i in 0 until ev.pointerCount) t += ev.getX(i); return t / ev.pointerCount }
    private fun centroidY(ev: MotionEvent): Float { var t = 0f; for (i in 0 until ev.pointerCount) t += ev.getY(i); return t / ev.pointerCount }

    private var slideDelta = 0
    private var slideTarget = 0

    /**
     * Troca de painel com transição horizontal. delta = +1 (seguinte, entra pela direita) ou -1 (anterior).
     * O painel de destino carrega num segundo WebView fora do ecrã; quando está pronto, os dois deslizam juntos.
     */
    private fun switchPanel(delta: Int) {
        if (switching || PanelState.sleeping) return
        val list = prefs.panels
        if (list.size < 2) {
            Toast.makeText(this, "Só há um painel guardado", Toast.LENGTH_SHORT).show()
            return
        }
        val cur = prefs.activeIndex.coerceIn(0, list.lastIndex)
        slideTarget = (cur + delta + list.size) % list.size
        slideDelta = delta
        switching = true
        standbyFailed = false
        Toast.makeText(this, list[slideTarget].name.ifBlank { list[slideTarget].url }, Toast.LENGTH_SHORT).show()

        val incoming = newWebView()
        standby = incoming
        incoming.translationX = delta * container.width.toFloat() // fora do ecrã, do lado de onde vai entrar
        container.addView(incoming, container.indexOfChild(blackout), FrameLayout.LayoutParams(-1, -1))
        incoming.loadUrl(list[slideTarget].url)
        handler.postDelayed(startSlide, SWITCH_TIMEOUT_MS) // rede de segurança se onPageFinished nunca vier
    }

    private val startSlide: Runnable = Runnable {
        val incoming = standby ?: return@Runnable
        if (!switching) return@Runnable
        handler.removeCallbacks(startSlide)
        val outgoing = web
        val w = container.width.toFloat()
        val ip = DecelerateInterpolator(1.6f)
        outgoing.animate().translationX(-slideDelta * w).setDuration(SLIDE_MS).setInterpolator(ip).start()
        incoming.animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(ip).withEndAction {
            // O destino passa a ser o painel ativo; o antigo é libertado (poupa memória no tablet).
            prefs.activeIndex = slideTarget
            loadedUrl = prefs.url
            web = incoming
            standby = null
            container.removeView(outgoing)
            outgoing.destroy()
            switching = false
            pageFailed = standbyFailed
            if (pageFailed) markFailed()
        }.start()
    }

    private fun cover() {
        handler.removeCallbacks(reveal)
        blackout.animate().cancel()
        blackout.alpha = 1f
        blackout.visibility = View.VISIBLE
    }

    private fun scheduleReveal() {
        if (!resumed || !hasWindowFocus() || PanelState.sleeping || blackout.visibility != View.VISIBLE) return
        handler.removeCallbacks(reveal)
        handler.postDelayed(reveal, SETTLE_MS)
    }

    /** Fallback sem permissão de administrador: ecrã preto + brilho no mínimo absoluto. Ao acordar, volta ao brilho pedido pela página. */
    private fun applySleeping(sleeping: Boolean) {
        if (sleeping) cover() else scheduleReveal()
        val lp = window.attributes
        lp.screenBrightness = if (sleeping) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        else PanelState.pageBrightness
        window.attributes = lp
    }

    /** Evento para a página: window.addEventListener("pine3wallpanel", e => e.detail.type). */
    private fun sendEvent(type: String) {
        val js = "window.dispatchEvent(new CustomEvent('pine3wallpanel',{detail:{type:'$type'}}))"
        try { web.evaluateJavascript(js, null) } catch (_: Exception) {}
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
