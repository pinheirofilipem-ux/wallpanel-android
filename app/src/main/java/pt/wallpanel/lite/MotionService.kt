package pt.wallpanel.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.os.SystemClock

/**
 * O "cérebro": serviço em primeiro plano que mantém a câmara a analisar movimento,
 * acorda o ecrã quando há movimento e desliga-o X segundos depois do último movimento.
 */
class MotionService : Service() {

    companion object {
        private const val CHANNEL = "motion"
        private const val NOTIF_ID = 1
        private const val IGNORE_AFTER_SLEEP_MS = 1500L

        @Volatile private var instance: MotionService? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, MotionService::class.java)
            try { ctx.startForegroundService(i) } catch (_: Exception) {}
        }

        /** Chamado pelas atividades quando o utilizador toca no ecrã. */
        fun poke() { instance?.main?.post { instance?.onActivity() } }

        fun isAdminActive(ctx: Context): Boolean {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var detector: MotionDetector? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sleptAt = 0L

    private val sleepRunnable = Runnable { goToSleep() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (detector == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // Mantém o CPU acordado com o ecrã desligado para a câmara continuar a entregar frames.
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wallpanel:cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
            // Samsung adormece o Wi-Fi com o ecrã desligado; isto mantém-no ligado para o painel estar pronto ao acordar.
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wallpanel:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            detector = MotionDetector(this, { prefs.sensitivity }) { main.post { onActivity() } }.also { it.start() }
            scheduleSleep()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        detector?.stop()
        detector = null
        cpuLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        PanelState.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Movimento ou toque: acorda (se preciso) e reinicia a contagem para desligar. */
    private fun onActivity() {
        if (PanelState.sleeping) {
            if (SystemClock.elapsedRealtime() - sleptAt < IGNORE_AFTER_SLEEP_MS) return
            wake()
        }
        scheduleSleep()
    }

    private fun scheduleSleep() {
        main.removeCallbacks(sleepRunnable)
        main.postDelayed(sleepRunnable, prefs.timeoutSec * 1000L)
    }

    @Suppress("DEPRECATION")
    private fun wake() {
        PanelState.set(false)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "wallpanel:wake",
        ).acquire(5000)
        // A atividade já está no topo; isto só ajuda se tiver sido enviada para segundo plano.
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: Exception) {}
    }

    private fun goToSleep() {
        sleptAt = SystemClock.elapsedRealtime()
        PanelState.set(true) // a atividade põe ecrã preto + brilho 0 (fallback)
        if (isAdminActive(this)) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            try { dpm.lockNow() } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
