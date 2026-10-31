package com.classboard.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.classboard.app.core.RectD
import kotlin.math.max

/** Modal question with buttons over a dimmed board. */
@SuppressLint("ViewConstructor")
class ConfirmOverlay(ctx: Context, t: Theme, title: String, message: String, actions: List<Action>, onDone: () -> Unit) : FrameLayout(ctx) {
    class Action(val label: String, val primary: Boolean = false, val danger: Boolean = false, val run: (() -> Unit)? = null)

    init {
        setBackgroundColor(t.scrim)
        isClickable = true
        val card = vbox(ctx, t.dp(12.0).toInt())
        card.background = roundedBg(t.popover, t.dp(t.popoverRadius), t.dp(1.0).toInt().coerceAtLeast(1), t.popoverBorder)
        val p = t.dp(24.0).toInt()
        card.setPadding(p, p, p, t.dp(16.0).toInt())
        card.elevation = t.dp(12.0)
        val tv = bodyText(ctx, t, title)
        tv.typeface = t.typefaceBold
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, t.dp(t.titleFontSize + 2))
        card.addView(tv)
        card.addView(bodyText(ctx, t, message, muted = true))
        val row = hbox(ctx, t.dp(8.0).toInt())
        row.gravity = Gravity.END
        for (a in actions) {
            val b = TouchButton(ctx, t, "", a.label, TouchButton.Style.Pill)
            b.primary = a.primary
            b.danger = a.danger
            b.setOnClickListener { onDone(); a.run?.invoke() }
            row.addView(b)
        }
        card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        val lp = LayoutParams(t.dp(460.0).toInt(), WRAP, Gravity.CENTER)
        addView(card, lp)
    }
}

/** Floating action bar above the selection: edit, exact values, magic, colour, arrange, duplicate, copy, delete. */
@SuppressLint("ViewConstructor")
class SelectionBar(ctx: Context, private val t: Theme) : LinearLayout(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val buttons = LinkedHashMap<String, TouchButton>()

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        val pv = t.dp(4.0).toInt(); val ph = t.dp(6.0).toInt()
        setPadding(ph, pv, ph, pv)
        elevation = t.dp(6.0)
        visibility = GONE
    }

    fun add(key: String, icon: String, tip: String, danger: Boolean = false, action: (View) -> Unit) {
        val b = TouchButton(context, t, icon, tip, TouchButton.Style.Icon)
        b.danger = danger
        b.setOnClickListener { action(it) }
        val s = t.dp(52.0).toInt()
        addView(b, LayoutParams(s, s))
        buttons[key] = b
    }

    fun show(key: String, visible: Boolean) { buttons[key]?.visibility = if (visible) VISIBLE else GONE }

    /** Positions the bar above (or below) the selection rectangle in parent coordinates. */
    fun place(sel: RectD, parentW: Int, parentH: Int, bottomInset: Int) {
        measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val w = measuredWidth
        val h = measuredHeight
        val gap = t.dp(56.0).toInt()
        var x = (sel.center.x - w / 2).toInt()
        var y = (sel.top - gap - h).toInt()
        val m = t.dp(8.0).toInt()
        if (y < m) y = (sel.bottom + t.dp(20.0)).toInt()
        if (y + h > parentH - bottomInset - m) y = max(m, (sel.top + m).toInt())
        x = x.coerceIn(m, max(m, parentW - w - m))
        val lp = (layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(WRAP, WRAP)
        lp.leftMargin = x
        lp.topMargin = y
        layoutParams = lp
        visibility = VISIBLE
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(1f, 1f, width - 1f, height - 1f)
        paint.style = Paint.Style.FILL; paint.color = t.popover
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
        paint.style = Paint.Style.STROKE; paint.color = t.popoverBorder; paint.strokeWidth = t.dp(1.0)
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
