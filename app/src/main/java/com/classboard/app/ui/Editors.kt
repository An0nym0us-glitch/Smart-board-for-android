package com.classboard.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.classboard.app.core.Pt
import com.classboard.app.doc.Align
import com.classboard.app.doc.TextFormat
import com.classboard.app.doc.TextObject
import com.classboard.app.math.TableObject
import kotlin.math.ceil
import kotlin.math.max

/** Inline editors on the board: text boxes and table cells. */
class Editors(private val app: App) {
    private val ctx: Context get() = app.activity
    private var textEditor: EditText? = null
    private var textObject: TextObject? = null
    private var textIsNew = false
    private var cellEditor: EditText? = null
    private var cellTable: String? = null
    private var cellRow = 0
    private var cellCol = 0

    val isEditingText get() = textEditor != null

    fun commitAll() {
        commitText()
        commitCell()
    }

    // ------------------------------------------------------------------------------- text

    fun beginText(objectId: String?, pagePos: Pt) {
        if (textEditor != null) { commitText(); return }
        commitCell()
        val page = app.doc.currentPage
        if (objectId != null) {
            val o = page.objectById(objectId) as? TextObject ?: return
            textObject = o.clone() as TextObject
            textIsNew = false
            app.board.setObjectHidden(objectId, true)
        } else {
            val f = app.tools.textFormat
            textObject = TextObject.create("", pagePos - Pt(0.0, f.pixelSize * 0.6), f, 60.0)
            textIsNew = true
        }
        val obj = textObject!!
        val e = EditText(ctx)
        e.setText(obj.text)
        e.setTextColor(obj.format.color)
        e.background = GradientDrawable().apply {
            setColor(Color.argb(50, 0, 0, 0))
            cornerRadius = app.t.dp(4.0)
            setStroke(max(1, app.t.dp(2.0).toInt()), app.t.accent, app.t.dp(6.0), app.t.dp(4.0))
        }
        e.setPadding(0, 0, 0, 0)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        e.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        e.isSingleLine = false
        e.includeFontPadding = false
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val o = textObject ?: return
                o.text = s?.toString() ?: ""
                if (textIsNew) o.boxWidth = max(60.0, TextObject.naturalWidth(o.text, o.format) + 12.0)
                layoutTextEditor()
            }
        })
        textEditor = e
        app.root.addView(e, app.root.indexOfChild(app.ribbon), FrameLayout.LayoutParams(10, 10))
        layoutTextEditor()
        e.requestFocus()
        e.setSelection(e.text.length)
        showKeyboard(e)
    }

    private fun layoutTextEditor() {
        val e = textEditor ?: return
        val o = textObject ?: return
        val view = app.board.view
        val zoom = view.zoom
        e.typeface = o.format.typeface()
        e.paintFlags = if (o.format.underline) e.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG else e.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, (o.format.pixelSize * zoom).toFloat())
        e.setTextColor(o.format.color)
        e.gravity = Gravity.TOP or when {
            o.format.alignment and Align.HCENTER != 0 -> Gravity.CENTER_HORIZONTAL
            o.format.alignment and Align.RIGHT != 0 -> Gravity.END
            else -> Gravity.START
        }
        val local = o.localBounds()
        val tl = view.pageToView(o.position + local.topLeft)
        val border = app.t.dp(2.0).toInt() + 2
        val lp = FrameLayout.LayoutParams(ceil(local.width * zoom).toInt() + 2 * border + 6, ceil(local.height * zoom).toInt() + 2 * border + 4)
        lp.leftMargin = tl.x.toInt() - border
        lp.topMargin = tl.y.toInt() - border
        e.layoutParams = lp
    }

    /** Applies formatting to the text being edited; false when not editing. */
    fun applyTextFormat(f: TextFormat): Boolean {
        val o = textObject ?: return false
        o.format = f
        if (textIsNew) o.boxWidth = max(60.0, TextObject.naturalWidth(o.text, f) + 12.0)
        layoutTextEditor()
        return true
    }

    fun commitText() {
        val e = textEditor ?: return
        val o = textObject ?: return
        textEditor = null
        textObject = null
        hideKeyboard(e)
        app.root.removeView(e)
        val page = app.doc.currentPage
        val text = e.text.toString()
        if (textIsNew) {
            if (text.isNotBlank()) {
                o.text = text.trimEnd()
                page.objects.add(o)
                app.doc.commit(page, "Add text")
            }
        } else {
            app.board.setObjectHidden(o.id, false)
            val idx = page.indexOf(o.id)
            if (idx >= 0) {
                if (text.isBlank()) {
                    page.objects.removeAt(idx)
                    app.doc.commit(page, "Delete text")
                } else {
                    val live = page.objects[idx] as TextObject
                    if (live.text != text || live.format != o.format || live.boxWidth != o.boxWidth) {
                        page.objects[idx] = o
                        app.doc.commit(page, "Edit text", listOf(o.id))
                    }
                }
            }
        }
        app.board.invalidateContent()
    }

    // ------------------------------------------------------------------------------- cells

    fun beginCell(tableId: String, row: Int, col: Int) {
        commitText()
        commitCell()
        val table = app.doc.currentPage.objectById(tableId) as? TableObject ?: return
        cellTable = tableId
        cellRow = row
        cellCol = col
        val e = EditText(ctx)
        e.setText(table.cell(row, col))
        e.setTextColor(table.colorValue)
        e.background = GradientDrawable().apply { setColor(Color.argb(220, 20, 26, 24)); setStroke(max(1, app.t.dp(2.0).toInt()), app.t.accent) }
        e.gravity = Gravity.CENTER
        e.isSingleLine = true
        e.imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        e.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_NEXT || action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                moveCell(1); true
            } else false
        }
        cellEditor = e
        app.root.addView(e, app.root.indexOfChild(app.ribbon), FrameLayout.LayoutParams(10, 10))
        layoutCellEditor()
        e.requestFocus()
        e.selectAll()
        showKeyboard(e)
    }

    private fun layoutCellEditor() {
        val e = cellEditor ?: return
        val table = cellTable?.let { app.doc.currentPage.objectById(it) as? TableObject } ?: return
        val r = table.cellRect(cellRow, cellCol)
        val view = app.board.view
        val a = view.pageToView(table.mapToPage(r.topLeft))
        val b = view.pageToView(table.mapToPage(r.bottomRight))
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, (table.fontSize * view.zoom).toFloat())
        val lp = FrameLayout.LayoutParams(max(40, (b.x - a.x).toInt()), max(30, (b.y - a.y).toInt()))
        lp.leftMargin = a.x.toInt()
        lp.topMargin = a.y.toInt()
        e.layoutParams = lp
    }

    private fun storeCell(): Boolean {
        val e = cellEditor ?: return false
        val page = app.doc.currentPage
        val table = cellTable?.let { page.objectById(it) as? TableObject } ?: return false
        val text = e.text.toString()
        if (table.cell(cellRow, cellCol) == text) return false
        table.setCell(cellRow, cellCol, text)
        app.doc.commit(page, "Edit cell", listOf(table.id))
        return true
    }

    private fun moveCell(step: Int) {
        val table = cellTable?.let { app.doc.currentPage.objectById(it) as? TableObject } ?: return
        storeCell()
        var c = cellCol + step
        var r = cellRow
        if (c >= table.cols) { c = 0; r++ }
        if (r >= table.rows) { commitCell(); return }
        cellRow = r; cellCol = c
        cellEditor?.setText(table.cell(r, c))
        cellEditor?.selectAll()
        layoutCellEditor()
    }

    fun commitCell() {
        val e = cellEditor ?: return
        storeCell()
        cellEditor = null
        cellTable = null
        hideKeyboard(e)
        app.root.removeView(e)
    }

    private fun showKeyboard(e: EditText) {
        e.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(e: EditText) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(e.windowToken, 0)
    }

    /** Keeps editors aligned after pan / zoom. */
    fun relayout() {
        layoutTextEditor()
        layoutCellEditor()
    }
}
