package pt.wallpanel.lite

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Panel(val name: String, val url: String)

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("panel", Context.MODE_PRIVATE)

    /** Lista de painéis guardados. Migra automaticamente o URL único das versões anteriores. */
    var panels: List<Panel>
        get() {
            val raw = sp.getString("panels", null)
            if (raw == null) {
                val legacy = sp.getString("url", "") ?: ""
                return if (legacy.isBlank()) emptyList() else listOf(Panel("Painel", legacy))
            }
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Panel(o.optString("name"), o.optString("url"))
                }
            } catch (_: Exception) { emptyList() }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
            sp.edit().putString("panels", arr.toString()).apply()
        }

    /** Índice do painel ativo em [panels]. */
    var activeIndex: Int
        get() = sp.getInt("active", 0)
        set(v) = sp.edit().putInt("active", v).apply()

    /** URL do painel ativo ("" se não houver nenhum). */
    val url: String
        get() {
            val list = panels
            if (list.isEmpty()) return ""
            return list[activeIndex.coerceIn(0, list.lastIndex)].url
        }

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

    /** Pedido (vindo das definições) para limpar a cache e recarregar o painel; consumido pela MainActivity. */
    var reloadRequested: Boolean
        get() = sp.getBoolean("reload", false)
        set(v) = sp.edit().putBoolean("reload", v).apply()
}
