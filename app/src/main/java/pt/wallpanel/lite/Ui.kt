package pt.wallpanel.lite

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Blocos de interface no estilo Pine3 (ameixa, creme, âmbar), usados pelos ecrãs de definições. */
class Ui(private val ctx: Context) {
    val bg = Color.parseColor("#1C0F1E")
    val surface = Color.parseColor("#2B1A2D")
    val field = Color.parseColor("#3A2339")
    val tonal = Color.parseColor("#44294A")
    val textPrimary = Color.parseColor("#F8F2E6")
    val textSecondary = Color.parseColor("#BFA9BB")
    val hint = Color.parseColor("#7C687A")
    val accent = Color.parseColor("#F6B83F")
    val onAccent = Color.parseColor("#2B1700")
    val ok = Color.parseColor("#8FD9A8")
    val bad = Color.parseColor("#F2755C")

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    fun ripple(content: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), content, null)

    fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    /** Cartão; se [parent] for horizontal, ocupa uma coluna de largura [weight]. */
    fun card(parent: LinearLayout, title: String, subtitle: String? = null, weight: Float = 1f): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 20)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        c.addView(text(title, 16f, textPrimary, true))
        if (subtitle != null) c.addView(text(subtitle, 13f, textSecondary), lp(top = 2))
        if (parent.orientation == LinearLayout.HORIZONTAL) {
            parent.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply { marginEnd = dp(14) })
        } else parent.addView(c, lp(bottom = 14))
        return c
    }

    fun labeled(parent: LinearLayout, label: String, view: View, note: String? = null) {
        parent.addView(text(label, 13f, textSecondary), lp(top = 14, bottom = 6))
        parent.addView(view, LinearLayout.LayoutParams(-1, -2))
        if (note != null) parent.addView(text(note, 12f, textSecondary), lp(top = 6))
    }

    fun input(value: String, hintText: String, type: Int) = EditText(ctx).apply {
        setText(value)
        hint = hintText
        setHintTextColor(this@Ui.hint)
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        inputType = type
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = rounded(field, 12)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (filled) onAccent else accent)
        background = ripple(rounded(if (filled) accent else tonal, 14))
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isClickable = true
        setOnClickListener { onClick() }
    }

    companion object {
        const val TYPE_TEXT = InputType.TYPE_CLASS_TEXT
    }
}
