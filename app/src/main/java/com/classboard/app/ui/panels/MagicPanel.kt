package com.classboard.app.ui.panels

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import com.classboard.app.ai.MathInkRecognizer
import com.classboard.app.core.RectD
import com.classboard.app.doc.ObjectType
import com.classboard.app.doc.StrokeObject
import com.classboard.app.math.EquationObject
import com.classboard.app.tools.ToolId
import com.classboard.app.ui.App
import com.classboard.app.ui.vbox
import java.util.concurrent.Executors

/** ✨ Magic Equation Maker: selected handwriting → preview → Accept / Edit / Cancel (offline). */
object MagicPanel {
    private val executor = Executors.newSingleThreadExecutor()

    fun create(app: App): View {
        val k = app.kit
        val l = k.layout()
        val ids = app.magicTargets
        val main = Handler(Looper.getMainLooper())
        var generation = 0
        var relaxedTried = false
        val working = vbox(app.activity, app.t.dp(10.0).toInt())
        val result = vbox(app.activity, app.t.dp(10.0).toInt())
        val failure = vbox(app.activity, app.t.dp(10.0).toInt())
        l.addView(working); l.addView(result); l.addView(failure)
        working.addView(k.hint("Recognising the selected handwriting… Your writing stays unchanged."))
        working.addView(k.row(k.pill("close", "Cancel") { generation++; app.popovers.closeAll() }))
        val preview = MathPanels.FormulaPreview(app)
        val confidence = k.hint("")
        val latex = k.input()
        latex.visibility = View.GONE
        latex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { preview.latex = s?.toString() ?: "" }
        })
        val failureLabel = k.hint("")
        lateinit var tryAgain: com.classboard.app.ui.TouchButton
        fun state(s: Int) {
            working.visibility = if (s == 0) View.VISIBLE else View.GONE
            result.visibility = if (s == 1) View.VISIBLE else View.GONE
            failure.visibility = if (s == 2) View.VISIBLE else View.GONE
            app.popovers.relayout()
        }
        fun showFailure(reason: String) {
            failureLabel.text = "$reason Your handwriting has not been changed. Try again, write the equation in the Equation panel, or cancel."
            tryAgain.isEnabled = !relaxedTried
            state(2)
        }
        fun accept() {
            val page = app.doc.currentPage
            val text = latex.text.toString().trim()
            val strokes = ids.mapNotNull { page.objectById(it) as? StrokeObject }
            if (text.isEmpty() || strokes.size != ids.size || strokes.isEmpty()) { showFailure("The handwriting is no longer on this page."); return }
            var bounds: RectD? = null
            for (s in strokes) bounds = bounds?.union(s.sceneBounds()) ?: s.sceneBounds()
            val b = bounds!!
            val size = (b.height * (if (text.contains("\\frac")) 0.42 else 0.7)).coerceIn(24.0, 400.0)
            val eq = EquationObject.create(text, b.center, size, strokes[0].ink.color)
            page.objects.removeAll(strokes.toSet())
            page.objects.add(eq)
            app.doc.commit(page, "Magic equation")
            app.activateTool(ToolId.Select)
            app.board.selection.setSingle(eq.id)
            app.toast.show("Equation created. Double-tap it to edit.", "Undo") { app.doc.undo() }
            app.popovers.closeAll()
        }
        fun start(relaxed: Boolean) {
            val page = app.doc.currentPage
            val strokes = ids.mapNotNull { page.objectById(it) }.filter { it.type == ObjectType.Stroke }.map { (it as StrokeObject).pagePoints() }
            if (strokes.isEmpty() || strokes.size != ids.size) {
                showFailure("Select handwriting with SELECT first (only pen strokes), then tap ✨ Magic Equation Maker.")
                return
            }
            state(0)
            val gen = ++generation
            executor.execute {
                val candidates = MathInkRecognizer(relaxed = relaxed).recognize(strokes)
                main.post {
                    if (gen != generation) return@post
                    val c = candidates.firstOrNull()
                    if (c == null || c.latex.isBlank()) showFailure("The handwriting could not be recognised with confidence.")
                    else {
                        latex.setText(c.latex)
                        preview.latex = c.latex
                        val pct = Math.round(c.confidence * 100)
                        confidence.text = if (c.confidence < 0.42) "Best guess (confidence $pct %). Check it before accepting." else "Recognised (confidence $pct %)."
                        state(1)
                    }
                }
            }
        }
        result.addView(preview)
        result.addView(confidence)
        result.addView(latex)
        latex.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { accept(); true } else false }
        result.addView(k.row(
            k.pill("check", "Accept", primary = true) { accept() },
            k.pill("edit", "Edit") { latex.visibility = View.VISIBLE; latex.requestFocus(); latex.selectAll(); app.popovers.relayout() },
            k.pill("close", "Cancel") { generation++; app.popovers.closeAll() },
        ))
        result.addView(k.hint("Accept replaces the handwriting with an editable equation (undo brings it back)."))
        failure.addView(failureLabel)
        tryAgain = k.pill("reset", "Try again", primary = true) { if (!relaxedTried) { relaxedTried = true; start(true) } }
        failure.addView(k.row(
            tryAgain,
            k.pill("equation", "Edit manually") {
                val guess = if (result.visibility == View.VISIBLE) latex.text.toString() else ""
                generation++
                app.pendingEquation = guess
                app.equationTarget = null
                val r = Rect(app.root.width / 2 - 1, app.root.height / 2 - 1, app.root.width / 2 + 1, app.root.height / 2 + 1)
                app.popovers.openAt("equation", r)
            },
            k.pill("close", "Cancel") { generation++; app.popovers.closeAll() },
        ))
        start(false)
        return l
    }
}
