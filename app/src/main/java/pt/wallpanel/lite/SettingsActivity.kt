package pt.wallpanel.lite

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var url: EditText
    private lateinit var timeout: EditText
    private lateinit var sens: SeekBar
    private lateinit var sensLabel: TextView
    private lateinit var ssl: CheckBox
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(16), 0, dp(4)) }.also { col.addView(it) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }.also { col.addView(it) }

        label("URL do dashboard (Home Assistant)")
        url = EditText(this).apply {
            setText(prefs.url)
            hint = "http://homeassistant.local:8123/lovelace/0"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }.also { col.addView(it) }

        label("Desligar o ecrã após (segundos sem movimento)")
        timeout = EditText(this).apply {
            setText(prefs.timeoutSec.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }.also { col.addView(it) }

        sensLabel = label("")
        sens = SeekBar(this).apply {
            max = 9
            progress = prefs.sensitivity - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { showSens(p + 1) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }.also { col.addView(it) }
        showSens(prefs.sensitivity)

        ssl = CheckBox(this).apply {
            text = "Aceitar certificado HTTPS auto-assinado"
            isChecked = prefs.ignoreSsl
        }.also { col.addView(it) }

        label("Permissões")
        status = TextView(this).also { col.addView(it) }
        button("Permitir câmara") { requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1) }
        button("Ativar desligar ecrã total (administrador)") {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Necessário para desligar o ecrã por completo (sem luz).")
            )
        }
        button("Ignorar otimização de bateria") {
            @Suppress("BatteryLife")
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        button("Guardar e abrir painel") { save(); finish() }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val cam = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val admin = MotionService.isAdminActive(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        fun m(ok: Boolean) = if (ok) "✔" else "✘"
        status.text = "${m(cam)} Câmara\n${m(admin)} Administrador (ecrã totalmente desligado)\n${m(battery)} Sem otimização de bateria"
        if (cam) MotionService.start(this)
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        MotionService.poke() // não desliga o ecrã enquanto estás a configurar
        return super.dispatchTouchEvent(ev)
    }

    private fun showSens(v: Int) { sensLabel.text = "Sensibilidade ao movimento: $v / 10" }

    private fun save() {
        prefs.url = url.text.toString()
        prefs.timeoutSec = timeout.text.toString().toIntOrNull() ?: 15
        prefs.sensitivity = sens.progress + 1
        prefs.ignoreSsl = ssl.isChecked
    }
}
