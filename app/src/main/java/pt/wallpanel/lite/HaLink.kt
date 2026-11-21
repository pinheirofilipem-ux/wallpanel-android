package pt.wallpanel.lite

import org.json.JSONObject

/** Regra: quando [entity] muda de estado (e, se indicado, o novo estado ou event_type é [whenValue]) acorda o ecrã. */
data class WakeRule(val entity: String, val whenValue: String, val panel: String)

class HaConfig(val wsUrl: String, val token: String, val trustAll: Boolean, val rules: List<WakeRule>)

private class AuthFailed : Exception()

/**
 * Ligação ao Home Assistant (API WebSocket) numa thread própria, com reconexão automática.
 * Subscreve `state_changed` e chama [onTrigger] quando uma entidade mapeada dispara.
 * Sem dependências do Android, para poder ser testada na JVM.
 */
class HaLink(
    private val cfg: HaConfig,
    private val onStatus: (text: String, connected: Boolean) -> Unit,
    private val onTrigger: (WakeRule) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var ws: MiniWebSocket? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "ha-link").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { ws?.close() } catch (_: Exception) {}
        thread?.interrupt()
    }

    private fun loop() {
        var backoff = 2000L
        while (running) {
            var authFailed = false
            try {
                onStatus("A ligar…", false)
                session()
                backoff = 2000L
            } catch (e: AuthFailed) {
                authFailed = true
                onStatus("Token inválido", false)
            } catch (e: Exception) {
                if (!running) break
                onStatus("Sem ligação, a tentar de novo…", false)
            } finally {
                try { ws?.close() } catch (_: Exception) {}
                ws = null
            }
            if (!running) break
            try { Thread.sleep(if (authFailed) 60_000L else backoff) } catch (_: InterruptedException) { break }
            backoff = minOf(backoff * 2, 30_000L)
        }
    }

    private fun session() {
        val socket = MiniWebSocket(cfg.wsUrl, cfg.trustAll, readTimeoutMs = 25_000)
        ws = socket
        socket.connect()

        var msg = JSONObject(socket.receive() ?: throw java.io.IOException("fechado"))
        if (msg.optString("type") != "auth_required") throw java.io.IOException("resposta inesperada")
        socket.send(JSONObject().put("type", "auth").put("access_token", cfg.token).toString())
        msg = JSONObject(socket.receive() ?: throw java.io.IOException("fechado"))
        when (msg.optString("type")) {
            "auth_ok" -> {}
            "auth_invalid" -> throw AuthFailed()
            else -> throw java.io.IOException("resposta inesperada")
        }
        socket.send(JSONObject().put("id", 1).put("type", "subscribe_events").put("event_type", "state_changed").toString())

        var pingId = 100
        var lastRx = System.currentTimeMillis()
        var subscribed = false
        while (running) {
            val text = try {
                socket.receive() ?: throw java.io.IOException("fechado pelo servidor")
            } catch (e: IdleTimeoutException) {
                // Sem tráfego há 25 s: confirma que a ligação está viva (a pergunta ping devolve pong).
                if (System.currentTimeMillis() - lastRx > 70_000) throw java.io.IOException("sem resposta ao ping")
                socket.send(JSONObject().put("id", pingId++).put("type", "ping").toString())
                continue
            }
            lastRx = System.currentTimeMillis()
            if (!subscribed) {
                val m = try { JSONObject(text) } catch (_: Exception) { null }
                if (m != null && m.optString("type") == "result" && m.optInt("id") == 1) {
                    if (m.optBoolean("success")) { subscribed = true; onStatus("Ligado", true) }
                    else throw java.io.IOException("subscrição recusada")
                    continue
                }
            }
            handle(text)
        }
    }

    private fun handle(text: String) {
        // Filtro barato: o HA envia todos os state_changed; só analisa os que mencionam entidades mapeadas.
        if (!text.contains("state_changed") || cfg.rules.none { text.contains(it.entity) }) return
        val data = try { JSONObject(text).optJSONObject("event")?.optJSONObject("data") } catch (_: Exception) { null } ?: return
        val entity = data.optString("entity_id")
        val new = data.optJSONObject("new_state") ?: return
        val newState = new.optString("state")
        if (newState == "unavailable" || newState == "unknown") return
        val oldState = data.optJSONObject("old_state")?.optString("state")
        if (oldState == newState) return // só mudanças de estado (um event.* muda sempre: o estado é a hora do evento)
        val eventType = new.optJSONObject("attributes")?.optString("event_type").orEmpty()
        for (rule in cfg.rules) {
            if (!rule.entity.equals(entity, ignoreCase = true)) continue
            val w = rule.whenValue.trim()
            if (w.isEmpty() || w.equals(newState, ignoreCase = true) || w.equals(eventType, ignoreCase = true)) {
                onTrigger(rule)
            }
        }
    }
}

/** Estado da ligação ao HA, lido pelo ecrã de definições. */
object HaStatus {
    @Volatile var text: String = "Inativo"
    @Volatile var connected: Boolean = false
}
