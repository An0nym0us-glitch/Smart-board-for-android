package com.classboard.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Hosts the popovers: one at a time (with a back stack for sub-pages), attached to the button
 * that opened it. A tap outside closes it.
 */
@SuppressLint("ViewConstructor")
class PopoverHost(context: Context, private val t: Theme) : FrameLayout(context) {
    /** Builds a popover for a key: returns the title, content and minimum content width (dp). */
    var factory: ((String) -> PopoverSpec?)? = null
    var onOpened: ((String) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    class PopoverSpec(val title: String, val content: View, val widthDp: Double = 320.0, val onClose: (() -> Unit)? = null)

    private class Entry(val key: String, val view: PopoverView, val anchor: Rect, val anchorView: View?, val spec: PopoverSpec)

    private val stack = ArrayList<Entry>()
    /** Area the popovers may use (above the ribbon). */
    var bounds = Rect()

    init {
        visibility = GONE
        clipChildren = false
    }

    val isOpen get() = stack.isNotEmpty()
    val currentKey: String? get() = stack.lastOrNull()?.key
    val anchorView: View? get() = stack.firstOrNull()?.anchorView

    private fun rectOf(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val me = IntArray(2)
        getLocationInWindow(me)
        return Rect(loc[0] - me[0], loc[1] - me[1], loc[0] - me[0] + v.width, loc[1] - me[1] + v.height)
    }

    fun toggle(key: String, anchor: View) {
        if (isOpen && anchorView === anchor && stack.first().key == key) { closeAll(); return }
        open(key, anchor)
    }

    fun open(key: String, anchor: View) = openInternal(key, rectOf(anchor), anchor, replace = true)
    fun openAt(key: String, anchorRect: Rect) = openInternal(key, anchorRect, null, replace = true)

    /** Opens a sub-page with a back button in the same place. */
    fun push(key: String) {
        val base = stack.lastOrNull() ?: return
        val spec = factory?.invoke(key) ?: return
        base.view.visibility = GONE
        val e = Entry(key, makeView(key, spec), base.anchor, base.anchorView, spec)
        e.view.backButton.visibility = VISIBLE
        e.view.backButton.setOnClickListener { pop() }
        stack.add(e)
        addView(e.view)
        place(e)
        onOpened?.invoke(key)
    }

    fun pop() {
        if (stack.size <= 1) { closeAll(); return }
        val top = stack.removeAt(stack.size - 1)
        top.spec.onClose?.invoke()
        removeView(top.view)
        val prev = stack.last()
        prev.view.visibility = VISIBLE
        refresh()
    }

    private fun makeView(key: String, spec: PopoverSpec): PopoverView {
        val v = PopoverView(context, t, spec.title, key)
        v.minContentWidth = t.dp(spec.widthDp).toInt()
        v.setContent(spec.content)
        v.closeButton.setOnClickListener { closeAll() }
        return v
    }

    private fun openInternal(key: String, anchor: Rect, anchorView: View?, replace: Boolean) {
        val spec = factory?.invoke(key) ?: return
        if (replace) clearStack()
        val e = Entry(key, makeView(key, spec), anchor, anchorView, spec)
        stack.add(e)
        addView(e.view)
        visibility = VISIBLE
        place(e)
        e.view.alpha = 0f
        e.view.translationY = (if (e.view.notchBelow) 1 else -1) * t.dp(10.0)
        e.view.animate().alpha(1f).translationY(0f).setDuration(150).start()
        onOpened?.invoke(key)
    }

    /** Rebuilds the top popover (state changed underneath it). */
    fun refresh() {
        val top = stack.lastOrNull() ?: return
        val spec = factory?.invoke(top.key) ?: return
        val idx = stack.size - 1
        removeView(top.view)
        top.spec.onClose?.invoke()
        val e = Entry(top.key, makeView(top.key, spec), top.anchor, top.anchorView, spec)
        if (stack.size > 1) { e.view.backButton.visibility = VISIBLE; e.view.backButton.setOnClickListener { pop() } }
        stack[idx] = e
        addView(e.view)
        place(e)
    }

    private fun clearStack() {
        for (e in stack) { e.spec.onClose?.invoke(); removeView(e.view) }
        stack.clear()
    }

    fun closeAll() {
        if (stack.isEmpty()) return
        clearStack()
        visibility = GONE
        onClosed?.invoke()
    }

    private fun place(e: Entry) {
        val v = e.view
        val b = if (bounds.isEmpty) Rect(0, 0, width, height) else bounds
        val anchor = if (e.anchorView != null && e.anchorView.isAttachedToWindow) rectOf(e.anchorView) else e.anchor
        val s = v.shadow.toInt()
        val pad = t.dp(t.popoverPadding).toInt()
        val notch = v.notch.toInt()
        val margin = t.dp(8.0).toInt()
        val headerH = t.dp(56.0).toInt()
        // Measure content at the desired width.
        var innerW = v.minContentWidth + 2 * pad
        innerW = min(innerW, b.width() - 2 * margin)
        val content = (v.scroll.getChildAt(0))
        content?.measure(MeasureSpec.makeMeasureSpec(innerW - 2 * pad, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val hintH = content?.measuredHeight ?: 0
        val wantedInnerH = headerH + hintH + pad * 7 / 4 + t.dp(4.0).toInt()
        val availAbove = anchor.top - b.top - margin - notch
        val availBelow = b.bottom - anchor.bottom - margin - notch
        v.notchBelow = availAbove >= wantedInnerH || availAbove >= availBelow
        val avail = max(t.dp(160.0).toInt(), if (v.notchBelow) availAbove else availBelow)
        val innerH = min(wantedInnerH, avail)
        val w = innerW + 2 * s
        val h = innerH + 2 * s + notch
        var x = anchor.centerX() - w / 2
        x = x.coerceIn(b.left + margin - s, max(b.left + margin - s, b.right - margin - w + s))
        val y = if (v.notchBelow) {
            val tip = min(anchor.top - t.dp(2.0).toInt(), b.bottom)
            tip - (s + innerH + notch)
        } else {
            val tip = max(anchor.bottom + t.dp(2.0).toInt(), b.top)
            tip - s
        }
        val radius = t.dp(t.popoverRadius)
        v.notchX = (anchor.centerX() - x).toFloat().coerceIn(s + radius + notch, s + innerW - radius - notch)
        v.updatePadding()
        val lp = LayoutParams(w, h)
        lp.leftMargin = x
        lp.topMargin = y
        v.layoutParams = lp
        v.invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Touches that reach the host are outside every popover: close.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isOpen) {
            closeAll()
            return true
        }
        return isOpen
    }

    fun relayout() { stack.lastOrNull()?.let { place(it) } }

    @Suppress("unused")
    private fun unusedParams(): ViewGroup.LayoutParams = LayoutParams(0, 0)
}
