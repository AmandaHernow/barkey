package net.hernow.barkey

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/** Swedish QWERTY and number/symbol layouts for BarKey's in-IME manual entry mode. */
internal class SwedishManualKeyboardView(
    context: Context,
    private val state: SwedishKeyboardState,
    private val onText: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onEnter: () -> Unit,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        contentDescription = context.getString(R.string.manual_keyboard_description)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        refresh()
    }

    fun refresh() {
        removeAllViews()
        val rows = if (!state.symbols) listOf("qwertyuiop", "asdfghjklå", "zxcvbnmäö")
            else listOf("1234567890", "@#$%&-*+()", "=/:;!?_€£")
        rows.forEach { chars ->
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER }
            chars.forEach { char ->
                val value = char.toString()
                line.addView(key(value) { onText(state.consumeCharacter(value)); refresh() },
                    LinearLayout.LayoutParams(0, 0, 1f).apply {
                        setMargins(1, 1, 1, 1)
                    })
            }
            addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val commands = LinearLayout(context).apply { gravity = Gravity.CENTER }
        addCommand(commands, "⇧", 1.1f) { state.toggleShift(); refresh() }
        addCommand(commands, if (state.symbols) "ABC" else "?123", 1.3f) { state.toggleSymbols(); refresh() }
        addCommand(commands, "⌫", 1.2f, context.getString(R.string.keyboard_delete), onBackspace)
        addView(commands, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER }
        addCommand(bottom, ",", 1f) { onText(",") }
        addCommand(bottom, "Space", 4f) { onText(" ") }
        addCommand(bottom, ".", 1f) { onText(".") }
        addCommand(bottom, "↵", 1.4f, context.getString(R.string.keyboard_enter), onEnter)
        addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun addCommand(
        row: LinearLayout,
        label: String,
        weight: Float,
        description: String? = null,
        action: () -> Unit,
    ) {
        row.addView(key(label, description, action), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(1, 1, 1, 1)
        })
    }

    private fun key(label: String, description: String? = null, action: () -> Unit) = Button(context).apply {
        text = label
        contentDescription = description ?: when (label) {
            "⌫" -> context.getString(R.string.keyboard_delete)
            "↵" -> context.getString(R.string.keyboard_enter)
            "⇧" -> context.getString(R.string.keyboard_shift)
            "?123" -> context.getString(R.string.keyboard_symbols)
            "ABC" -> context.getString(R.string.keyboard_letters)
            else -> label
        }
        minWidth = 0
        minHeight = dp(40)
        setPadding(0, 0, 0, 0)
        isAllCaps = false
        isFocusable = false
        setBackgroundResource(R.drawable.keyboard_button_background)
        backgroundTintList = null
        setTextColor(ContextCompat.getColorStateList(context, R.color.keyboard_button_text))
        textSize = 17f
        elevation = 0f
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
