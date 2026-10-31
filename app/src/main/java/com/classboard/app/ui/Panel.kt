package com.classboard.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/** Lays children out left to right, wrapping onto new lines. */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec).let { if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else it }
        var x = 0
        var y = 0
        var lineH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams
            val ws = if (lp != null && lp.width > 0) MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(maxW.coerceAtMost(100000), MeasureSpec.AT_MOST)
            c.measure(ws, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            x += c.measuredWidth + hGap
            widest = max(widest, x - hGap)
            lineH = max(lineH, c.measuredHeight)
        }
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else widest
        setMeasuredDimension(w, y + lineH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap
            lineH = max(lineH, c.measuredHeight)
        }
    }
}

class Tile(val icon: String, val label: String, val checked: Boolean = false, val enabled: Boolean = true, val action: () -> Unit)

/** Builders for popover content in the ClassBoard style. */
class PanelKit(val ctx: Context, val t: Theme) {
    fun layout(): LinearLayout = vbox(ctx, t.dp(10.0).toInt()).also {
        it.layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
    }

    fun section(text: String) = sectionLabel(ctx, t, text)
    fun hint(text: String): TextView = bodyText(ctx, t, text, muted = true, small = true)
    fun text(text: String): TextView = bodyText(ctx, t, text)

    fun pill(icon: String, text: String, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, action: () -> Unit): TouchButton =
        TouchButton(ctx, t, icon, text, TouchButton.Style.Pill).also {
            it.primary = primary; it.danger = danger; it.isEnabled = enabled
            it.setOnClickListener { action() }
        }

    fun iconButton(icon: String, tip: String, action: () -> Unit) = TouchButton(ctx, t, icon, tip, TouchButton.Style.Icon).also { it.setOnClickListener { action() } }

    fun rowButton(icon: String, text: String, danger: Boolean = false, enabled: Boolean = true, action: () -> Unit) =
        TouchButton(ctx, t, icon, text, TouchButton.Style.Row).also { it.danger = danger; it.isEnabled = enabled; it.setOnClickListener { action() } }

    fun row(vararg views: View): FlowLayout {
        val f = FlowLayout(ctx, t.dp(8.0).toInt(), t.dp(8.0).toInt())
        views.forEach { f.addView(it) }
        return f
    }

    fun tiles(columns: Int, tiles: List<Tile>): TileGrid {
        val g = TileGrid(ctx, columns, t.dp(8.0).toInt())
        for (tile in tiles) {
            val b = TouchButton(ctx, t, tile.icon, tile.label, TouchButton.Style.Tile)
            b.checked = tile.checked
            b.isEnabled = tile.enabled
            b.setOnClickListener { tile.action() }
            g.addView(b)
        }
        return g
    }

    /** Exclusive choice among pills. */
    fun choices(labels: List<String>, current: Int, columns: Int = 4, onPick: (Int) -> Unit): View {
        val g = TileGrid(ctx, columns, t.dp(6.0).toInt())
        val buttons = ArrayList<TouchButton>()
        for ((i, l) in labels.withIndex()) {
            val b = TouchButton(ctx, t, "", l, TouchButton.Style.Pill)
            b.checked = i == current
            b.setOnClickListener {
                buttons.forEachIndexed { j, bb -> bb.checked = j == i }
                onPick(i)
            }
            buttons.add(b)
            g.addView(b)
        }
        return g
    }

    fun segmented(options: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit) =
        SegmentedControl(ctx, t, options, selected, onSelect)

    fun slider(caption: String, min: Double, max: Double, value: Double, step: Double = 0.0, dot: Boolean = false, format: (Double) -> String, onChange: (Double, Boolean) -> Unit) =
        TouchSlider(ctx, t, caption, min, max, value, format, step, dot, onChange)

    fun swatches(colors: IntArray, selected: Int?, perRow: Int = 6, onPick: (Int) -> Unit) = SwatchGrid(ctx, t, colors, selected, perRow, onPick)
    fun toggle(title: String, subtitle: String, on: Boolean, onToggle: (Boolean) -> Unit) = ToggleRow(ctx, t, title, subtitle, on, onToggle)
    fun input(hint: String = "", numeric: Boolean = false) = InputField(ctx, t, hint, numeric)

    /** Input field with a button at its end. */
    fun inputRow(field: View, button: View): LinearLayout {
        val h = hbox(ctx, t.dp(8.0).toInt())
        h.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        h.addView(button)
        return h
    }

    /** Label + numeric field ("Width  [ 30 ]  cm"). */
    fun numberField(label: String, unit: String, value: Double, onValue: (Double) -> Unit): LinearLayout {
        val h = hbox(ctx, t.dp(8.0).toInt())
        val l = text(label)
        h.addView(l, LinearLayout.LayoutParams(t.dp(120.0).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        val f = input(numeric = true)
        f.setText(com.classboard.app.core.Geom.formatNumber(value, 4).replace('−', '-'))
        f.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { s?.toString()?.replace(',', '.')?.toDoubleOrNull()?.let(onValue) }
        })
        h.addView(f, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (unit.isNotEmpty()) h.addView(text(unit))
        return h
    }
}
