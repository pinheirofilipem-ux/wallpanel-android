package pt.wallpanel.lite

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    // Paleta Pine3 View: ameixa + creme + âmbar
    private val bg = Color.parseColor("#1C0F1E")
    private val surface = Color.parseColor("#2B1A2D")
    private val field = Color.parseColor("#3A2339")
    private val tonal = Color.parseColor("#44294A")
    private val textPrimary = Color.parseColor("#F8F2E6")
    private val textSecondary = Color.parseColor("#BFA9BB")
    private val hintColor = Color.parseColor("#7C687A")
    private val accent = Color.parseColor("#F6B83F")
    private val onAccent = Color.parseColor("#2B1700")
    private val ok = Color.parseColor("#8FD9A8")
    private val bad = Color.parseColor("#F2755C")

    private lateinit var prefs: Prefs
    private lateinit var url: EditText
    private lateinit var timeout: EditText
    private lateinit var sens: SeekBar
    private lateinit var sensValue: TextView
    private lateinit var ssl: Switch
    private lateinit var boot: Switch

    private class StatusRow(val dot: View, val action: TextView, val check: () -> Boolean)
    private val rows = mutableListOf<StatusRow>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun withRipple(content: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), content, null)

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun card(parent: LinearLayout, title: String, subtitle: String? = null, weight: Float = 1f): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 20)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        c.addView(text(title, 16f, textPrimary, true))
        if (subtitle != null) c.addView(text(subtitle, 13f, textSecondary), lp(top = 2))
        if (parent.orientation == LinearLayout.HORIZONTAL) {
            parent.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                marginEnd = dp(14)
            })
        } else {
            parent.addView(c, lp(bottom = 14))
        }
        return c
    }

    private fun labeled(parent: LinearLayout, label: String, view: View, hint: String? = null) {
        parent.addView(text(label, 13f, textSecondary), lp(top = 16, bottom = 6))
        parent.addView(view, LinearLayout.LayoutParams(-1, -2))
        if (hint != null) parent.addView(text(hint, 12f, textSecondary), lp(top = 6))
    }

    private fun input(value: String, hint: String, type: Int) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(hintColor)
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        inputType = type
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = rounded(field, 12)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun switchRow(parent: LinearLayout, label: String, checked: Boolean, top: Int = 16): Switch {
        val sw = Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, Color.parseColor("#D9CCD6"))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.argb(110, 246, 184, 63), Color.parseColor("#5A4259"))
            )
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 15f, textPrimary), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(sw)
        parent.addView(row, lp(top = top))
        return sw
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (filled) onAccent else accent)
        background = withRipple(rounded(if (filled) accent else tonal, 14))
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun statusRow(parent: LinearLayout, label: String, actionLabel: String, check: () -> Boolean, onAction: () -> Unit) {
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bad) } }
        val action = TextView(this).apply {
            text = actionLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(accent)
            background = withRipple(rounded(tonal, 10))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onAction() }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(14) })
        row.addView(text(label, 15f, textPrimary), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(action)
        parent.addView(row, lp(top = 14))
        rows += StatusRow(dot, action, check)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Cabeçalho: ícone + nome da app
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            android.widget.ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(16) }
        )
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("Pine3 Wallpanel", 24f, textPrimary, true))
        titles.addView(text("Definições", 14f, textSecondary), lp(top = 1))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Guardar e abrir painel", true) { save(); finish() }, LinearLayout.LayoutParams(-2, -2))
        col.addView(header, lp(bottom = 16))

        // Três colunas lado a lado (ecrã horizontal, sem scroll)
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(cards, LinearLayout.LayoutParams(-1, -2))

        // Painel
        val panel = card(cards, "Painel", "Endereço do teu dashboard do Home Assistant")
        url = input(prefs.url, "http://homeassistant.local:8123/lovelace/0", InputType.TYPE_TEXT_VARIATION_URI)
        labeled(panel, "URL", url)
        ssl = switchRow(panel, "Aceitar certificado HTTPS auto-assinado", prefs.ignoreSsl)
        panel.addView(
            button("Limpar cache e recarregar página", false) {
                save()
                prefs.reloadRequested = true
                Toast.makeText(this, "Cache limpa. A recarregar…", Toast.LENGTH_SHORT).show()
                finish()
            },
            lp(top = 18)
        )

        // Movimento
        val motion = card(cards, "Movimento e ecrã", "Acorda com movimento, desliga quando não há")
        timeout = input(prefs.timeoutSec.toString(), "15", InputType.TYPE_CLASS_NUMBER)
        labeled(motion, "Desligar o ecrã após (segundos sem movimento)", timeout)

        val sensHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sensHeader.addView(text("Sensibilidade", 13f, textSecondary), LinearLayout.LayoutParams(0, -2, 1f))
        sensValue = text("", 15f, accent, true)
        sensHeader.addView(sensValue)
        motion.addView(sensHeader, lp(top = 20))
        sens = SeekBar(this).apply {
            max = 9
            progress = prefs.sensitivity - 1
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { showSens(p + 1) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        motion.addView(sens, lp(top = 6))
        showSens(prefs.sensitivity)

        // Sistema
        val system = card(cards, "Sistema", "Permissões para funcionar sempre, mesmo com o ecrã desligado", 1.15f)
        statusRow(system, "Câmara", "Permitir", {
            checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }) { requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1) }
        statusRow(system, "Desligar ecrã por completo (administrador)", "Ativar", { MotionService.isAdminActive(this) }) {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Necessário para desligar o ecrã por completo (sem luz).")
            )
        }
        statusRow(system, "Sem otimização de bateria", "Ativar", {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
        }
        statusRow(system, "Aparecer por cima (arranque automático)", "Ativar", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(Uri.parse("package:$packageName")))
        }
        boot = switchRow(system, "Abrir quando o tablet arranca", prefs.bootStart, top = 22)
        system.addView(
            button("Definições da app (bateria / nunca adormecer)", false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
            },
            lp(top = 18)
        )

        col.addView(
            text("Atalho: dois dedos premidos 2 s no painel abrem estas definições.", 12f, textSecondary),
            lp(top = 4)
        )

        val holder = FrameLayout(this).apply {
            setPadding(dp(24), dp(18), dp(10), dp(18))
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // ScrollView só como rede de segurança em ecrãs pequenos; num tablet horizontal não há scroll.
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(holder)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) MotionService.start(this)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refreshStatus()
    }

    private fun refreshStatus() {
        for (r in rows) {
            val good = r.check()
            (r.dot.background as GradientDrawable).setColor(if (good) ok else bad)
            r.action.visibility = if (good) View.GONE else View.VISIBLE
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        MotionService.poke() // não desliga o ecrã enquanto estás a configurar
        return super.dispatchTouchEvent(ev)
    }

    private fun showSens(v: Int) { sensValue.text = "$v / 10" }

    private fun save() {
        prefs.url = url.text.toString()
        prefs.timeoutSec = timeout.text.toString().toIntOrNull() ?: 15
        prefs.sensitivity = sens.progress + 1
        prefs.ignoreSsl = ssl.isChecked
        prefs.bootStart = boot.isChecked
    }
}
