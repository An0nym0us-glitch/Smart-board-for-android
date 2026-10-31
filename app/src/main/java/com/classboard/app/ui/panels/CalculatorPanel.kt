package com.classboard.app.ui.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.classboard.app.math.Calculator
import com.classboard.app.math.EquationObject
import com.classboard.app.tools.ToolId
import com.classboard.app.ui.App
import com.classboard.app.ui.MATCH
import com.classboard.app.ui.TileGrid
import com.classboard.app.ui.TouchButton
import com.classboard.app.ui.WRAP
import com.classboard.app.ui.roundedBg
import com.classboard.app.ui.vbox

/** Classroom calculator with big touch keys, Ans, DEG/RAD and "Put on board". */
object CalculatorPanel {
    private const val MINUS = "−"
    private val KEYS = listOf(
        listOf("sin", "cos", "tan", "ln", "log", "π"),
        listOf("sin⁻¹", "cos⁻¹", "tan⁻¹", "√", "x²", "xʸ"),
        listOf("7", "8", "9", "÷", "⌫", "AC"),
        listOf("4", "5", "6", "×", "(", ")"),
        listOf("1", "2", "3", MINUS, "n!", "%"),
        listOf("0", ".", "e", "+", "Ans", "="),
    )
    private val INSERT = mapOf("sin" to "sin(", "cos" to "cos(", "tan" to "tan(", "ln" to "ln(", "log" to "log(",
        "sin⁻¹" to "asin(", "cos⁻¹" to "acos(", "tan⁻¹" to "atan(", "√" to "√(", "x²" to "²", "xʸ" to "^", "n!" to "!")
    private val CONTINUES = setOf("+", MINUS, "×", "÷", "^", "²", "!", "%")
    private fun isBinary(c: Char) = c == '+' || c == '−' || c == '-' || c == '×' || c == '÷' || c == '*' || c == '/' || c == '^'

    fun create(app: App): View {
        val k = app.kit
        val t = app.t
        val calc = app.calculator
        val l = k.layout()
        var input = calc.input
        var justEvaluated = calc.showingResult && calc.history.isNotEmpty()
        val display = TextView(app.activity).apply {
            setTextColor(t.text)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, t.dp(30.0))
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            background = roundedBg(t.input, t.dp(10.0), t.dp(1.0).toInt().coerceAtLeast(1), t.inputBorder)
            val p = t.dp(12.0).toInt()
            setPadding(p, p, p, p)
            minHeight = t.dp(60.0).toInt()
            hint = "Type or tap a calculation"
            setHintTextColor(t.textDisabled)
            maxLines = 2
        }
        val result = TextView(app.activity).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            minHeight = t.dp(56.0).toInt()
        }
        l.addView(display, LinearLayout.LayoutParams(MATCH, WRAP))
        l.addView(result, LinearLayout.LayoutParams(MATCH, WRAP))
        val history = vbox(app.activity, t.dp(2.0).toInt())

        fun showResult(text: String) {
            result.setTextColor(t.accent)
            result.typeface = t.typefaceBold
            result.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.dp(40.0))
            result.text = "= $text"
        }
        fun preview() {
            if (justEvaluated) return
            val r = calc.evaluate(input)
            result.setTextColor(t.textMuted)
            result.typeface = t.typeface
            result.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.dp(30.0))
            result.text = if (r.ok) "= ${r.text}" else ""
        }
        fun setInput(s: String) {
            input = s
            calc.input = s
            display.text = s
            preview()
        }
        fun setJust(on: Boolean) { justEvaluated = on; calc.showingResult = on }
        lateinit var rebuildHistory: () -> Unit
        fun insert(text: String) {
            var current = input
            if (justEvaluated) {
                current = if (text in CONTINUES) "Ans" else ""
                setJust(false)
            } else if (current.isEmpty() && text in CONTINUES && text != MINUS && calc.history.isNotEmpty()) current = "Ans"
            if (text.length == 1 && isBinary(text[0]) && text != MINUS) {
                var before = current.length - 1
                while (before >= 0 && current[before].isWhitespace()) before--
                if (before >= 0 && isBinary(current[before])) current = current.substring(0, before)
            }
            setInput(current + text)
        }
        fun backspace() {
            setJust(false)
            if (input.isEmpty()) return
            val tokens = listOf("asin(", "acos(", "atan(", "sin(", "cos(", "tan(", "ln(", "log(", "√(", "Ans")
            val n = tokens.firstOrNull { input.endsWith(it) }?.length ?: 1
            setInput(input.dropLast(n))
        }
        fun equals() {
            val r = calc.calculate(input)
            if (r.ok) { showResult(r.text); setJust(true); rebuildHistory() }
            else {
                result.setTextColor(t.danger)
                result.typeface = t.typeface
                result.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.dp(16.0))
                result.text = r.error
            }
        }
        rebuildHistory = {
            history.removeAllViews()
            if (calc.history.isEmpty()) history.addView(k.hint("Your calculations appear here. Tap one to use its result."))
            for (e in calc.history.take(4)) {
                history.addView(k.rowButton("", "${e.expression}  =  ${e.result}") {
                    insert(if (e.value < 0) "(" + Calculator.toInput(e.value) + ")" else Calculator.toInput(e.value))
                })
            }
        }
        val degrees = calc.angleUnit == Calculator.AngleUnit.Degrees
        val unitRow = com.classboard.app.ui.hbox(app.activity, t.dp(8.0).toInt())
        unitRow.addView(k.hint("Angles"))
        val units = k.choices(listOf("DEG", "RAD"), if (degrees) 0 else 1, 2) { i ->
            calc.angleUnit = if (i == 0) Calculator.AngleUnit.Degrees else Calculator.AngleUnit.Radians
            preview()
        }
        unitRow.addView(units, LinearLayout.LayoutParams(t.dp(200.0).toInt(), WRAP))
        l.addView(unitRow)
        val grid = TileGrid(app.activity, 6, t.dp(6.0).toInt())
        for (row in KEYS) for (key in row) {
            val b = TouchButton(app.activity, t, "", key, TouchButton.Style.Pill)
            b.minWidthPx = t.dp(54.0).toInt()
            if (key == "=") b.primary = true
            if (key == "AC") b.danger = true
            b.setOnClickListener {
                when (key) {
                    "=" -> equals()
                    "AC" -> { setJust(false); setInput(""); result.text = "" }
                    "⌫" -> backspace()
                    else -> insert(INSERT[key] ?: key)
                }
            }
            grid.addView(b)
        }
        l.addView(grid)
        l.addView(k.row(
            k.pill("equation", "Put on board") {
                val expression: String
                val res: String
                if (justEvaluated && calc.history.isNotEmpty()) {
                    expression = calc.history[0].expression; res = calc.history[0].result
                } else {
                    val r = calc.evaluate(input)
                    if (!r.ok) { app.toast.show(if (input.isBlank()) "Type a calculation first." else r.error, durationMs = 3000); return@pill }
                    expression = Calculator.substituteAns(input.trim(), calc.ans); res = r.text
                }
                val page = app.doc.currentPage
                val eq = EquationObject.create(Calculator.toLatex(expression, res), app.board.viewCenterInPage(), 56.0, app.tools.penColor)
                page.objects.add(eq)
                app.doc.commit(page, "Put calculation on board")
                app.activateTool(ToolId.Select)
                app.board.selection.setSingle(eq.id)
                app.popovers.closeAll()
            },
            k.pill("copy", "Copy result") {
                val r = calc.evaluate(input)
                if (r.ok) {
                    val cm = app.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("result", Calculator.toInput(r.value)))
                    app.toast.show("Copied ${r.text}", durationMs = 1500)
                }
            },
        ))
        l.addView(k.section("Recent"))
        l.addView(history)
        display.text = input
        if (justEvaluated) showResult(calc.history[0].result) else preview()
        rebuildHistory()
        return l
    }
}
