package pt.wallpanel.lite

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("panel", Context.MODE_PRIVATE)

    /** URL do dashboard do Home Assistant. */
    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v.trim()).apply()

    /** Segundos sem movimento até desligar o ecrã. */
    var timeoutSec: Int
        get() = sp.getInt("timeout", 15)
        set(v) = sp.edit().putInt("timeout", v.coerceIn(3, 3600)).apply()

    /** 1 (pouco sensível) .. 10 (muito sensível). */
    var sensitivity: Int
        get() = sp.getInt("sens", 6)
        set(v) = sp.edit().putInt("sens", v.coerceIn(1, 10)).apply()

    /** Aceitar certificados HTTPS auto-assinados (HA local). */
    var ignoreSsl: Boolean
        get() = sp.getBoolean("ssl", false)
        set(v) = sp.edit().putBoolean("ssl", v).apply()

    /** Abrir a app automaticamente quando o tablet arranca. */
    var bootStart: Boolean
        get() = sp.getBoolean("boot", true)
        set(v) = sp.edit().putBoolean("boot", v).apply()
}
