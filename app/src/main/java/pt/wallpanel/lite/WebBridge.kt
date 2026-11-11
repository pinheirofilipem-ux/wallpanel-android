package pt.wallpanel.lite

import android.app.Activity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Ponte com a página (window.Pine3Wallpanel), para painéis como o Pine3 Homeview saberem que estão
 * nesta app e reagirem a movimento e ao ecrã. A página também recebe eventos:
 *   window.addEventListener("pine3wallpanel", (e) => e.detail.type)  // "motion" | "sleep" | "wake"
 * O user agent ganha "Pine3Wallpanel/<versão da ponte>".
 */
class WebBridge(private val activity: Activity, private val prefs: Prefs) {

    companion object {
        const val VERSION = 1
        const val NAME = "Pine3Wallpanel"
        const val USER_AGENT = "Pine3Wallpanel/$VERSION"
    }

    /** Versão da ponte (sobe quando mudam os métodos). */
    @JavascriptInterface
    fun version(): Int = VERSION

    /** Estado em JSON: ecrã a dormir, segundos até desligar, há quanto tempo houve movimento, brilho pedido. */
    @JavascriptInterface
    fun state(): String = JSONObject()
        .put("version", VERSION)
        .put("sleeping", PanelState.sleeping)
        .put("timeoutSec", prefs.timeoutSec)
        .put("secondsToSleep", MotionService.secondsToSleep())
        .put("lastMotionAgoMs", PanelState.lastMotionAgoMs())
        .put("brightness", PanelState.pageBrightness.toDouble())
        .put("admin", MotionService.isAdminActive(activity))
        .toString()

    /** Brilho do ecrã pedido pela página, 0.0–1.0; um valor negativo volta ao brilho do sistema. */
    @JavascriptInterface
    fun setBrightness(level: Double) {
        val v = if (level < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else level.coerceIn(0.01, 1.0).toFloat()
        PanelState.pageBrightness = v
        activity.runOnUiThread {
            if (!PanelState.sleeping) {
                val lp = activity.window.attributes
                lp.screenBrightness = v
                activity.window.attributes = lp
            }
        }
    }

    /** Conta como atividade: acorda o ecrã (se estiver a dormir) e reinicia a contagem para desligar. */
    @JavascriptInterface
    fun wake() = MotionService.poke()

    /** Mantém o ecrã ligado durante pelo menos estes segundos (0 cancela), por exemplo enquanto toca a campainha. */
    @JavascriptInterface
    fun keepAwake(seconds: Int) = MotionService.keepAwake(seconds.coerceIn(0, 3600))

    /** Desliga o ecrã já (como se tivesse passado o tempo sem movimento). */
    @JavascriptInterface
    fun sleepNow() = MotionService.sleepNow()
}
