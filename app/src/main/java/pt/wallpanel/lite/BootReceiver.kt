package pt.wallpanel.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Abre o painel quando o tablet arranca (ex.: depois de uma falha de energia).
 * Em Android 10+ só funciona com a permissão "Aparecer por cima de outras apps" ativa.
 * A câmara é depois iniciada pela própria atividade, já em primeiro plano.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(ctx)
        if (!prefs.bootStart || prefs.url.isBlank()) return
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
