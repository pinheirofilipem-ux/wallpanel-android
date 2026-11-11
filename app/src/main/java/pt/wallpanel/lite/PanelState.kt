package pt.wallpanel.lite

import android.os.SystemClock
import android.view.WindowManager

/** Estado partilhado entre o serviço (decide) e a atividade (mostra). Só usado na main thread. */
object PanelState {
    var sleeping = false
        private set
    var listener: ((Boolean) -> Unit)? = null

    /** Avisado quando a câmara vê movimento (no máximo uma vez por segundo), para passar à página. */
    var motionListener: (() -> Unit)? = null
    private var lastMotion = 0L
    private var lastMotionSent = 0L

    /** Brilho pedido pela página (WebBridge.setBrightness); NONE = o do sistema. */
    var pageBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    fun set(value: Boolean) {
        if (sleeping == value) return
        sleeping = value
        listener?.invoke(value)
    }

    fun motion() {
        val now = SystemClock.elapsedRealtime()
        lastMotion = now
        if (now - lastMotionSent >= 1000) {
            lastMotionSent = now
            motionListener?.invoke()
        }
    }

    fun lastMotionAgoMs(): Long = if (lastMotion == 0L) -1 else SystemClock.elapsedRealtime() - lastMotion
}
