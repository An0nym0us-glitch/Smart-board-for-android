package com.classboard.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun View.lp(w: Int, h: Int): View { layoutParams = ViewGroup.LayoutParams(w, h); return this }
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun darker(c: Int, f: Double): Int = Color.argb(Color.alpha(c), (Color.red(c) / f).toInt(), (Color.green(c) / f).toInt(), (Color.blue(c) / f).toInt())
private fun lighter(c: Int, f: Double): Int = Color.argb(Color.alpha(c), min(255, (Color.red(c) * f).toInt()), min(255, (Color.green(c) * f).toInt()), min(255, (Color.blue(c) * f).toInt()))

/** The ClassBoard button: ribbon button, tile, row, round icon or pill. */
@SuppressLint("ViewConstructor")
class TouchButton(context: Context, private val t: Theme, icon: String, text: String, var style: Style, val ribbon: Boolean = false) : View(context) {
    enum class Style { Ribbon, Tile, Row, Icon, Pill }

    var iconName = icon
        set(v) { field = v; requestLayout(); invalidate() }
    var label = text
        set(v) { field = v; requestLayout(); invalidate() }
    var checked = false
        set(v) { field = v; invalidate() }
    var hasOptions = false
        set(v) { field = v; invalidate() }
    var indicatorColor: Int? = null
        set(v) { field = v; invalidate() }
    var primary = false
        set(v) { field = v; invalidate() }
    var danger = false
        set(v) { field = v; invalidate() }
    var minWidthPx = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    init {
        isClickable = true
        isFocusable = false
        contentDescription = text
    }

    private fun d(v: Double) = if (ribbon) t.rdp(v) else t.dp(v)

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); invalidate() }
    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    private fun smallSize() = d(t.smallFontSize)
    private fun normalSize() = d(t.fontSize)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        textPaint.textSize = smallSize()
        val (w, h) = when (style) {
            Style.Ribbon -> Pair(max(d(t.ribbonButtonWidth), textPaint.measureText(label) + d(20.0)), d(t.ribbonHeight) - d(12.0))
            Style.Tile -> Pair(max(d(96.0), textPaint.measureText(label) + d(16.0)), d(88.0))
            Style.Row -> { textPaint.textSize = normalSize(); Pair(d(56.0) + textPaint.measureText(label) + d(24.0), d(t.touchTarget)) }
            Style.Icon -> Pair(d(t.touchTarget), d(t.touchTarget))
            Style.Pill -> {
                textPaint.textSize = normalSize()
                val iconW = if (iconName.isEmpty()) 0f else d(30.0)
                Pair(textPaint.measureText(label) + iconW + d(36.0), d(48.0))
            }
        }
        val ww = max(w, minWidthPx.toFloat())
        setMeasuredDimension(resolveSize(ww.roundToInt(), widthMeasureSpec), resolveSize(h.roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val inset = d(2.0)
        val r = RectF(inset, inset, width - inset, height - inset)
        var bg = 0
        var fg = if (isEnabled) t.text else t.textDisabled
        if (primary && isEnabled) { bg = t.accent; fg = t.accentText }
        if (danger && isEnabled) fg = t.danger
        if (checked) { bg = t.buttonChecked; fg = t.accent }
        if (isEnabled && isPressed) bg = if (primary) darker(bg, 1.15) else t.buttonPressed
        if (style == Style.Tile && Color.alpha(bg) == 0) bg = darker(t.buttonHover, 1.15)
        if (style == Style.Pill && Color.alpha(bg) == 0 && !primary) bg = t.buttonHover
        val radius = when (style) {
            Style.Icon, Style.Pill -> r.height() / 2
            else -> d(t.cornerRadius)
        }
        if (Color.alpha(bg) > 0) {
            paint.style = Paint.Style.FILL
            paint.color = bg
            c.drawRoundRect(r, radius, radius, paint)
        }
        when (style) {
            Style.Ribbon, Style.Tile -> {
                val iconSize = if (style == Style.Ribbon) d(t.ribbonIconSize) else d(t.iconSize) * 1.15f
                val hasText = label.isNotEmpty()
                textPaint.textSize = smallSize()
                textPaint.typeface = if (checked) t.typefaceBold else t.typeface
                val fm = textPaint.fontMetrics
                val textH = if (hasText) fm.descent - fm.ascent else 0f
                val gap = if (hasText) d(5.0) else 0f
                val total = iconSize + gap + textH
                val top = r.centerY() - total / 2
                if (iconName.isNotEmpty()) Icons.draw(c, iconName, r.centerX(), top + iconSize / 2, iconSize, fg)
                if (hasText) {
                    textPaint.color = fg
                    textPaint.textAlign = Paint.Align.CENTER
                    val txt = TextUtils.ellipsize(label, android.text.TextPaint(textPaint), r.width() - d(4.0), TextUtils.TruncateAt.END).toString()
                    c.drawText(txt, r.centerX(), top + iconSize + gap - fm.ascent, textPaint)
                }
                indicatorColor?.let { ic ->
                    val dd = d(9.0)
                    val cx = r.centerX() + iconSize * 0.62f
                    val cy = top + iconSize * 0.18f
                    paint.style = Paint.Style.FILL; paint.color = ic
                    c.drawCircle(cx, cy, dd / 2 + d(1.0), paint)
                    paint.style = Paint.Style.STROKE; paint.color = t.ribbon; paint.strokeWidth = d(2.0)
                    c.drawCircle(cx, cy, dd / 2 + d(1.0), paint)
                }
                if (hasOptions && checked) {
                    val w = d(10.0)
                    val cx = r.centerX()
                    val cy = r.top + d(5.0)
                    val p = Path()
                    p.moveTo(cx - w / 2, cy + w / 4); p.lineTo(cx, cy - w / 4); p.lineTo(cx + w / 2, cy + w / 4)
                    paint.style = Paint.Style.STROKE; paint.color = fg; paint.strokeWidth = d(2.0)
                    paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                    c.drawPath(p, paint)
                }
            }
            Style.Row -> {
                if (iconName.isNotEmpty()) Icons.draw(c, iconName, r.left + d(28.0), r.centerY(), d(t.iconSize), fg)
                textPaint.textSize = normalSize(); textPaint.typeface = if (primary) t.typefaceBold else t.typeface
                textPaint.color = fg; textPaint.textAlign = Paint.Align.LEFT
                val fm = textPaint.fontMetrics
                c.drawText(label, r.left + d(56.0), r.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
            }
            Style.Icon -> {
                if (iconName.isNotEmpty()) Icons.draw(c, iconName, r.centerX(), r.centerY(), d(t.iconSize), fg)
                indicatorColor?.let { ic ->
                    paint.style = Paint.Style.FILL; paint.color = ic
                    c.drawCircle(r.centerX(), r.centerY(), r.width() * 0.3f, paint)
                    paint.style = Paint.Style.STROKE; paint.color = t.popover; paint.strokeWidth = d(2.0)
                    c.drawCircle(r.centerX(), r.centerY(), r.width() * 0.3f, paint)
                }
            }
            Style.Pill -> {
                textPaint.textSize = normalSize(); textPaint.typeface = if (primary) t.typefaceBold else t.typeface
                textPaint.color = fg
                val fm = textPaint.fontMetrics
                val baseline = r.centerY() - (fm.ascent + fm.descent) / 2
                if (iconName.isEmpty()) {
                    textPaint.textAlign = Paint.Align.CENTER
                    c.drawText(label, r.centerX(), baseline, textPaint)
                } else {
                    val iconSize = d(t.iconSize) * 0.85f
                    val textW = textPaint.measureText(label)
                    val total = iconSize + d(8.0) + textW
                    val left = r.centerX() - total / 2
                    Icons.draw(c, iconName, left + iconSize / 2, r.centerY(), iconSize, fg)
                    textPaint.textAlign = Paint.Align.LEFT
                    c.drawText(label, left + iconSize + d(8.0), baseline, textPaint)
                }
            }
        }
    }
}

/** Uppercase section header ("STYLE", "COLOUR"). */
fun sectionLabel(ctx: Context, t: Theme, text: String): TextView = TextView(ctx).apply {
    this.text = text.uppercase()
    setTextColor(t.textMuted)
    typeface = t.typefaceBold
    letterSpacing = 0.06f
    setTextSize(TypedValue.COMPLEX_UNIT_PX, t.dp(14.5))
    setPadding(t.dp(5.0).toInt(), t.dp(10.0).toInt(), 0, t.dp(4.0).toInt())
}

fun bodyText(ctx: Context, t: Theme, text: String, muted: Boolean = false, small: Boolean = false): TextView = TextView(ctx).apply {
    this.text = text
    setTextColor(if (muted) t.textMuted else t.text)
    typeface = t.typeface
    setTextSize(TypedValue.COMPLEX_UNIT_PX, t.dp(if (small) t.smallFontSize else t.fontSize))
}

fun vbox(ctx: Context, spacing: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    if (spacing > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(1, spacing); setColor(0) }
    }
}

fun hbox(ctx: Context, spacing: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (spacing > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(spacing, 1); setColor(0) }
    }
}

fun LinearLayout.addWeighted(v: View, weight: Float = 1f) {
    val p = LinearLayout.LayoutParams(0, WRAP, weight)
    addView(v, p)
}

/** Dark rounded input field. */
@SuppressLint("ViewConstructor")
class InputField(ctx: Context, t: Theme, hint: String = "", numeric: Boolean = false) : EditText(ctx) {
    init {
        setTextColor(t.text)
        setHintTextColor(t.textDisabled)
        this.hint = hint
        typeface = t.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_PX, t.dp(t.fontSize))
        background = GradientDrawable().apply {
            setColor(t.input)
            cornerRadius = t.dp(10.0)
            setStroke(t.dp(1.0).toInt().coerceAtLeast(1), t.inputBorder)
        }
        val pad = t.dp(12.0).toInt()
        setPadding(pad, t.dp(10.0).toInt(), pad, t.dp(10.0).toInt())
        minHeight = t.dp(46.0).toInt()
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }
}

/** Segmented choice with icon + label per option (like the Pen style chooser). */
@SuppressLint("ViewConstructor")
class SegmentedControl(ctx: Context, private val t: Theme, private val options: List<Pair<String, String>>, selected: Int, private val onSelect: (Int) -> Unit) : View(ctx) {
    var selected = selected
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val showIcons = options.any { it.first.isNotEmpty() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        tp.textSize = t.dp(t.smallFontSize)
        val minSeg = options.maxOf { tp.measureText(it.second) } + t.dp(18.0)
        val w = max(minSeg * options.size, t.dp(80.0) * options.size)
        val h = if (showIcons) t.dp(72.0) else t.dp(48.0)
        setMeasuredDimension(resolveSize(w.roundToInt(), widthMeasureSpec), h.roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val outer = RectF(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL; paint.color = t.input
        c.drawRoundRect(outer, t.dp(12.0), t.dp(12.0), paint)
        val segW = width.toFloat() / options.size
        for ((i, o) in options.withIndex()) {
            val r = RectF(i * segW + t.dp(3.0), t.dp(3.0), (i + 1) * segW - t.dp(3.0), height - t.dp(3.0))
            val sel = i == selected
            val fg = if (sel) t.accent else t.text
            if (sel) {
                paint.style = Paint.Style.FILL; paint.color = t.buttonChecked
                c.drawRoundRect(r, t.dp(10.0), t.dp(10.0), paint)
                paint.style = Paint.Style.STROKE; paint.color = t.accent; paint.strokeWidth = t.dp(1.5)
                c.drawRoundRect(r, t.dp(10.0), t.dp(10.0), paint)
            }
            tp.textSize = t.dp(t.smallFontSize)
            tp.typeface = if (sel) t.typefaceBold else t.typeface
            tp.color = fg
            tp.textAlign = Paint.Align.CENTER
            val fm = tp.fontMetrics
            if (showIcons && o.first.isNotEmpty()) {
                val iconSize = t.dp(24.0)
                Icons.draw(c, o.first, r.centerX(), r.centerY() - t.dp(10.0), iconSize, fg)
                c.drawText(o.second, r.centerX(), r.centerY() + t.dp(10.0) - fm.ascent - t.dp(2.0), tp)
            } else c.drawText(o.second, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.x / (width.toFloat() / options.size)).toInt().coerceIn(0, options.size - 1)
            selected = i
            onSelect(i)
        }
        return true
    }
}

/** Big-knob slider with a caption row ("Thickness ... 4.0 px"). */
@SuppressLint("ViewConstructor")
class TouchSlider(ctx: Context, private val t: Theme, private val caption: String, private val min: Double, private val max: Double, value: Double,
                  private val format: (Double) -> String, private val step: Double = 0.0, private val showDot: Boolean = false,
                  private val onChange: (Double, Boolean) -> Unit) : View(ctx) {
    var value = value.coerceIn(min, max)
        set(v) { field = v.coerceIn(min, max); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (if (caption.isEmpty()) 0f else t.dp(26.0)) + t.dp(44.0)
        setMeasuredDimension(resolveSize(t.dp(300.0).roundToInt(), widthMeasureSpec), h.roundToInt())
    }

    private fun trackLeft() = t.dp(if (showDot) 46.0 else 16.0)
    private fun trackRight() = width - t.dp(16.0)

    override fun onDraw(c: Canvas) {
        var top = 0f
        if (caption.isNotEmpty()) {
            tp.textSize = t.dp(t.smallFontSize + 1); tp.typeface = t.typefaceBold; tp.color = t.textMuted; tp.textAlign = Paint.Align.LEFT
            c.drawText(caption, 0f, t.dp(18.0), tp)
            tp.color = t.text; tp.textAlign = Paint.Align.RIGHT
            c.drawText(format(value), width.toFloat(), t.dp(18.0), tp)
            top = t.dp(26.0)
        }
        val cy = top + t.dp(22.0)
        if (showDot) {
            paint.style = Paint.Style.FILL; paint.color = t.text
            c.drawCircle(t.dp(22.0), cy, t.dp(2.5), paint)
        }
        val l = trackLeft(); val r = trackRight()
        val f = ((value - min) / (max - min)).toFloat()
        val x = l + (r - l) * f
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = t.dp(5.0)
        paint.color = t.separator
        c.drawLine(l, cy, r, cy, paint)
        paint.color = t.accent
        c.drawLine(l, cy, x, cy, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        c.drawCircle(x, cy, t.dp(13.0), paint)
        paint.style = Paint.Style.STROKE; paint.color = t.accent; paint.strokeWidth = t.dp(2.5)
        c.drawCircle(x, cy, t.dp(13.0), paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val l = trackLeft(); val r = trackRight()
        var v = min + ((e.x - l) / (r - l)).coerceIn(0f, 1f) * (max - min)
        if (step > 0) v = (Math.round(v / step) * step).coerceIn(min, max)
        value = v
        val final = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        onChange(value, final)
        return true
    }
}

/** Grid of round colour swatches; the selected one has an accent ring. */
@SuppressLint("ViewConstructor")
class SwatchGrid(ctx: Context, private val t: Theme, private val colors: IntArray, selected: Int?, private val perRow: Int = 6, private val onPick: (Int) -> Unit) : View(ctx) {
    var selected = selected
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cell get() = t.dp(50.0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = (colors.size + perRow - 1) / perRow
        setMeasuredDimension(resolveSize((cell * perRow).roundToInt(), widthMeasureSpec), (cell * rows).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        for ((i, col) in colors.withIndex()) {
            val cx = (i % perRow) * cell + cell / 2
            val cy = (i / perRow) * cell + cell / 2
            val rr = t.dp(17.0)
            paint.style = Paint.Style.FILL; paint.color = col
            c.drawCircle(cx, cy, rr, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = t.dp(1.0); paint.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(cx, cy, rr, paint)
            if (selected != null && (col and 0xFFFFFF) == (selected!! and 0xFFFFFF)) {
                paint.color = t.accent; paint.strokeWidth = t.dp(3.0)
                c.drawCircle(cx, cy, rr + t.dp(5.0), paint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val col = (e.x / cell).toInt(); val row = (e.y / cell).toInt()
            val i = row * perRow + col
            if (col in 0 until perRow && i in colors.indices) { selected = colors[i]; onPick(colors[i]) }
        }
        return true
    }
}

/** Title + description with a pill switch on the right. */
@SuppressLint("ViewConstructor")
class ToggleRow(ctx: Context, private val t: Theme, private val title: String, private val subtitle: String, on: Boolean, private val onToggle: (Boolean) -> Unit) : View(ctx) {
    var on = on
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var subLayout: android.text.StaticLayout? = null

    private fun layoutFor(w: Int): android.text.StaticLayout? {
        if (subtitle.isEmpty()) return null
        tp.textSize = t.dp(t.smallFontSize); tp.color = t.textMuted; tp.typeface = t.typeface
        val avail = max(10, w - t.dp(76.0).toInt())
        return android.text.StaticLayout.Builder.obtain(subtitle, 0, subtitle.length, tp, avail).build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(t.dp(320.0).roundToInt(), widthMeasureSpec)
        subLayout = layoutFor(w)
        val h = t.dp(30.0) + (subLayout?.height ?: 0) + t.dp(14.0)
        setMeasuredDimension(w, max(h, t.dp(56.0)).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        tp.textSize = t.dp(t.fontSize + 1); tp.color = t.text; tp.typeface = t.typeface
        c.drawText(title, 0f, t.dp(22.0), tp)
        subLayout?.let { c.save(); c.translate(0f, t.dp(28.0)); it.draw(c); c.restore() }
        val sw = t.dp(52.0); val sh = t.dp(30.0)
        val r = RectF(width - sw - t.dp(2.0), (height - sh) / 2, width - t.dp(2.0), (height + sh) / 2)
        paint.style = Paint.Style.FILL
        paint.color = if (on) t.accent else t.buttonPressed
        c.drawRoundRect(r, sh / 2, sh / 2, paint)
        paint.color = Color.WHITE
        val kx = if (on) r.right - sh / 2 else r.left + sh / 2
        c.drawCircle(kx, r.centerY(), sh / 2 - t.dp(3.5), paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) { on = !on; onToggle(on) }
        return true
    }
}

/** Popover bubble with header (title + close), scrollable content and a notch towards its anchor. */
@SuppressLint("ViewConstructor")
class PopoverView(ctx: Context, private val t: Theme, title: String, val key: String) : LinearLayout(ctx) {
    val titleView: TextView
    val closeButton: TouchButton
    val backButton: TouchButton
    val scroll: android.widget.ScrollView
    var notchBelow = true
    var notchX = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val shadow = t.dp(14.0)
    val notch = t.dp(t.popoverNotch)
    var minContentWidth = t.dp(320.0).toInt()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        val header = hbox(ctx, t.dp(6.0).toInt())
        backButton = TouchButton(ctx, t, "back", "Back", TouchButton.Style.Icon).apply { visibility = GONE }
        titleView = TextView(ctx).apply {
            text = title
            setTextColor(t.popoverHeader)
            typeface = t.typefaceBold
            setTextSize(TypedValue.COMPLEX_UNIT_PX, t.dp(t.titleFontSize))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        closeButton = TouchButton(ctx, t, "close", "Close", TouchButton.Style.Icon)
        val bs = t.dp(44.0).toInt()
        header.addView(backButton, LayoutParams(bs, bs))
        header.addView(titleView, LayoutParams(0, WRAP, 1f))
        header.addView(closeButton, LayoutParams(bs, bs))
        addView(header, LayoutParams(MATCH, WRAP))
        scroll = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = true; isFillViewport = false; overScrollMode = OVER_SCROLL_NEVER }
        addView(scroll, LayoutParams(MATCH, 0, 1f))
        updatePadding()
    }

    fun setContent(v: View) {
        scroll.removeAllViews()
        scroll.addView(v, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    fun updatePadding() {
        val pad = t.dp(t.popoverPadding).toInt()
        val s = shadow.toInt()
        setPadding(s + pad, s + pad * 3 / 4 + (if (notchBelow) 0 else notch.toInt()), s + pad, s + pad + (if (notchBelow) notch.toInt() else 0))
    }

    private fun bubble(): Path {
        val s = shadow
        val r = t.dp(t.popoverRadius)
        val top = s + if (notchBelow) 0f else notch
        val bottom = height - s - if (notchBelow) notch else 0f
        val rect = RectF(s, top, width - s, bottom)
        val p = Path()
        p.addRoundRect(rect, r, r, Path.Direction.CW)
        val nw = notch * 1.25f
        val tri = Path()
        if (notchBelow) {
            val b = rect.bottom
            tri.moveTo(notchX - nw, b - 1)
            tri.quadTo(notchX - nw * 0.35f, b + notch * 0.35f, notchX - nw * 0.12f, b + notch * 0.9f)
            tri.quadTo(notchX, b + notch * 1.05f, notchX + nw * 0.12f, b + notch * 0.9f)
            tri.quadTo(notchX + nw * 0.35f, b + notch * 0.35f, notchX + nw, b - 1)
        } else {
            val tp = rect.top
            tri.moveTo(notchX - nw, tp + 1)
            tri.quadTo(notchX - nw * 0.35f, tp - notch * 0.35f, notchX - nw * 0.12f, tp - notch * 0.9f)
            tri.quadTo(notchX, tp - notch * 1.05f, notchX + nw * 0.12f, tp - notch * 0.9f)
            tri.quadTo(notchX + nw * 0.35f, tp - notch * 0.35f, notchX + nw, tp + 1)
        }
        tri.close()
        p.op(tri, Path.Op.UNION)
        return p
    }

    override fun onDraw(c: Canvas) {
        val path = bubble()
        c.save()
        c.translate(0f, t.dp(3.0))
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        for (i in 8 downTo 1) {
            paint.color = Color.argb(((0.035 + 0.01 * (8 - i)) * 255).toInt(), 0, 0, 0)
            paint.strokeWidth = t.dp(1.6) * i
            c.drawPath(path, paint)
        }
        c.restore()
        paint.style = Paint.Style.FILL; paint.color = t.popover
        c.drawPath(path, paint)
        paint.style = Paint.Style.STROKE; paint.color = t.popoverBorder; paint.strokeWidth = t.dp(1.0)
        c.drawPath(path, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}

/** A wrapping grid of equal-width tiles (MORE popover, Insert, ...). */
class TileGrid(ctx: Context, private val columns: Int, private val gap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var maxChildW = 0
        var maxChildH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            maxChildW = max(maxChildW, c.measuredWidth)
            maxChildH = max(maxChildH, c.measuredHeight)
        }
        val wanted = maxChildW * columns + gap * (columns - 1)
        val w = resolveSize(wanted, widthMeasureSpec)
        val cellW = (w - gap * (columns - 1)) / columns
        var count = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxChildH, MeasureSpec.EXACTLY))
            count++
        }
        val rows = (count + columns - 1) / columns
        setMeasuredDimension(w, rows * maxChildH + max(0, rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var k = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val col = k % columns; val row = k / columns
            val x = col * (c.measuredWidth + gap)
            val y = row * (c.measuredHeight + gap)
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            k++
        }
    }
}

/** Short message near the bottom of the board, with an optional action ("Undo"). */
@SuppressLint("ViewConstructor")
class ToastView(ctx: Context, private val t: Theme) : LinearLayout(ctx) {
    private val text = bodyText(ctx, t, "")
    private val action = TouchButton(ctx, t, "", "", TouchButton.Style.Pill)
    private val hide = Runnable { animate().alpha(0f).setDuration(200).withEndAction { visibility = GONE }.start() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Color.parseColor("#F0202624")); cornerRadius = t.dp(24.0); setStroke(1, t.popoverBorder) }
        val p = t.dp(18.0).toInt()
        setPadding(p, t.dp(8.0).toInt(), t.dp(8.0).toInt(), t.dp(8.0).toInt())
        addView(text, LayoutParams(WRAP, WRAP))
        val lp = LayoutParams(WRAP, WRAP)
        lp.leftMargin = t.dp(12.0).toInt()
        addView(action, lp)
        visibility = GONE
        elevation = t.dp(6.0)
    }

    fun show(message: String, actionText: String? = null, durationMs: Long = 3500, onAction: (() -> Unit)? = null) {
        removeCallbacks(hide)
        text.text = message
        val p = t.dp(18.0).toInt()
        if (actionText != null && onAction != null) {
            action.visibility = VISIBLE
            action.label = actionText
            action.primary = true
            action.setOnClickListener { onAction(); hide.run() }
            setPadding(p, t.dp(6.0).toInt(), t.dp(6.0).toInt(), t.dp(6.0).toInt())
        } else {
            action.visibility = GONE
            setPadding(p, t.dp(12.0).toInt(), p, t.dp(12.0).toInt())
        }
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(150).start()
        postDelayed(hide, if (actionText != null) max(durationMs, 6000) else durationMs)
    }
}

fun roundedBg(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

@Suppress("unused")
private fun unused(a: Double) = abs(a) + Typeface.BOLD
