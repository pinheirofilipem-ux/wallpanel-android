package pt.wallpanel.lite

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Mapeia entidades do Home Assistant para acordarem o ecrã (ex.: event.doorbell da integração Comelit). */
class WakeActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var urlField: android.widget.EditText
    private lateinit var tokenField: android.widget.EditText
    private lateinit var holdField: android.widget.EditText
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var ruleList: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            (statusDot.background as GradientDrawable).setColor(if (HaStatus.connected) ui.ok else ui.bad)
            statusText.text = HaStatus.text
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(ui.text("Acordar com o Home Assistant", 24f, ui.textPrimary, true))
        titles.addView(ui.text("Quando uma entidade muda de estado, o ecrã acorda", 14f, ui.textSecondary), ui.lp(top = 1))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ui.button("Guardar e ligar", true) { applyAndConnect(); finish() }, LinearLayout.LayoutParams(-2, -2))
        col.addView(header, ui.lp(bottom = 16))

        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(cards, LinearLayout.LayoutParams(-1, -2))

        // Ligação
        val conn = ui.card(cards, "Ligação", "A app liga-se ao HA por WebSocket e fica à escuta")
        urlField = ui.input(prefs.haUrl, "Automático: " + (prefs.haWsUrl()?.substringBefore("/api")?.replace("ws", "http") ?: "http://homeassistant.local:8123"),
            InputType.TYPE_TEXT_VARIATION_URI)
        ui.labeled(conn, "Endereço do Home Assistant", urlField, "Vazio = usa o endereço do painel ativo.")
        tokenField = ui.input(prefs.haToken, "Cola aqui o token",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        ui.labeled(conn, "Token de acesso de longa duração", tokenField, "HA: o teu perfil, separador Segurança, no fim da página.")
        holdField = ui.input(prefs.wakeHoldSec.toString(), "60", InputType.TYPE_CLASS_NUMBER)
        ui.labeled(conn, "Tempo geral com o ecrã ligado (segundos)", holdField, "Cada entidade pode ter o seu próprio tempo.")

        val status = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.bad) } }
        status.addView(statusDot, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { rightMargin = ui.dp(12) })
        statusText = ui.text(HaStatus.text, 14f, ui.textPrimary)
        status.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        conn.addView(status, ui.lp(top = 18))

        // Entidades
        val ents = ui.card(cards, "Entidades que acordam o ecrã", "Toca para editar. Podes ter várias.", 1.2f)
        ruleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ents.addView(
            ScrollView(this).apply { addView(ruleList) },
            LinearLayout.LayoutParams(-1, ui.dp(190)).apply { topMargin = ui.dp(10) }
        )
        ents.addView(ui.button("+ Adicionar entidade", false) { editRule(-1) }, ui.lp(top = 10))
        ents.addView(ui.button("+ Campainha Comelit (ring, 120 s)", false) {
            val l = prefs.wakeRules.toMutableList()
            if (l.any { it.entity == DEFAULT_DOORBELL.entity }) {
                Toast.makeText(this, "A campainha Comelit já está na lista.", Toast.LENGTH_SHORT).show()
            } else {
                l.add(DEFAULT_DOORBELL)
                prefs.wakeRules = l
                renderRules()
            }
        }, ui.lp(top = 8))
        ents.addView(
            ui.text("\"Só quando\" compara com o novo estado ou o event_type. Vazio = qualquer mudança de estado.", 12f, ui.textSecondary),
            ui.lp(top = 10)
        )
        renderRules()

        val holder = FrameLayout(this).apply {
            setPadding(ui.dp(24), ui.dp(18), ui.dp(10), ui.dp(18))
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ui.bg)
            isFillViewport = true
            addView(holder)
        })
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        applyAndConnect()
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        MotionService.poke() // não desliga o ecrã enquanto configuras
        return super.dispatchTouchEvent(ev)
    }

    private fun applyAndConnect() {
        prefs.haUrl = urlField.text.toString()
        prefs.haToken = tokenField.text.toString()
        prefs.wakeHoldSec = holdField.text.toString().toIntOrNull() ?: 60
        MotionService.start(this) // o serviço (re)liga ao HA se a configuração mudou
    }

    private fun renderRules() {
        ruleList.removeAllViews()
        val list = prefs.wakeRules
        if (list.isEmpty()) ruleList.addView(ui.text("Ainda não há entidades.", 14f, ui.textSecondary), ui.lp(top = 6))
        list.forEachIndexed { i, r ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.ripple(ui.rounded(ui.tonal, 12))
                setPadding(ui.dp(14), ui.dp(10), ui.dp(8), ui.dp(10))
                isClickable = true
                setOnClickListener { editRule(i) }
            }
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.addView(ui.text(r.entity, 15f, ui.textPrimary, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            val detail = buildString {
                append(if (r.whenValue.isBlank()) "qualquer mudança" else "quando: ${r.whenValue}")
                if (r.holdSec > 0) append("  ·  ${r.holdSec} s")
                if (r.panel.isNotBlank()) append("  →  painel: ${r.panel}")
            }
            names.addView(ui.text(detail, 12f, ui.textSecondary))
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.text("Editar", 13f, ui.accent).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) })
            ruleList.addView(row, ui.lp(top = if (i == 0) 0 else 6))
        }
    }

    /** Diálogo para criar (index = -1) ou editar/apagar uma entidade. */
    private fun editRule(index: Int) {
        val list = prefs.wakeRules.toMutableList()
        val existing = list.getOrNull(index)
        val entity = ui.input(existing?.entity ?: "", "event.doorbell", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val whenField = ui.input(existing?.whenValue ?: "", "ring  (opcional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val ruleHold = ui.input(if ((existing?.holdSec ?: 0) > 0) existing!!.holdSec.toString() else "", "geral (${prefs.wakeHoldSec})  (opcional)", InputType.TYPE_CLASS_NUMBER)
        val panels = prefs.panels.map { it.name.ifBlank { it.url } }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@WakeActivity, android.R.layout.simple_spinner_dropdown_item, listOf("(não mudar de painel)") + panels)
            val at = if (existing == null || existing.panel.isBlank()) 0
            else prefs.panels.indexOfFirst { it.name.trim().equals(existing.panel.trim(), true) } + 1
            setSelection(at.coerceAtLeast(0))
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(10), ui.dp(22), 0) }
        ui.labeled(box, "ID da entidade", entity)
        ui.labeled(box, "Só quando (estado ou event_type)", whenField)
        ui.labeled(box, "Manter o ecrã ligado (segundos)", ruleHold)
        ui.labeled(box, "Ao disparar, mostrar o painel", spinner)

        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (existing == null) "Nova entidade" else "Editar entidade")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val id = entity.text.toString().trim().lowercase()
                if (!Regex("^[a-z0-9_]+\\.[a-z0-9_]+$").matches(id)) {
                    Toast.makeText(this, "ID inválido (ex.: event.doorbell)", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                val sel = spinner.selectedItemPosition
                val hold = (ruleHold.text.toString().toIntOrNull() ?: 0).let { if (it <= 0) 0 else it.coerceIn(5, 3600) }
                val rule = WakeRule(id, whenField.text.toString().trim(), if (sel <= 0) "" else prefs.panels[sel - 1].name, hold)
                if (existing == null) list.add(rule) else list[index] = rule
                prefs.wakeRules = list
                renderRules()
            }
        if (existing != null) {
            b.setNeutralButton("Apagar") { _, _ ->
                list.removeAt(index)
                prefs.wakeRules = list
                renderRules()
            }
        }
        b.show()
    }
}
