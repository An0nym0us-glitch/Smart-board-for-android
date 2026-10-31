package com.classboard.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.classboard.app.tools.ToolId
import com.classboard.app.ui.App
import com.classboard.app.ui.FileBridge

/** Hosts the board. Plain framework Activity: no AndroidX needed. */
class MainActivity : Activity(), FileBridge {
    private lateinit var app: App
    private val pending = HashMap<Int, (Intent?) -> Unit>()
    private var nextRequest = 1000
    private var fullScreen = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        setContentView(root)
        app = App(this, root, this)
        setFullScreen(app.settings.fullScreen)
        root.post {
            if (!handleIntent(intent)) app.checkRecovery()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Opens a lesson, PDF or image handed to us by another app. Returns true if one was handled. */
    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val found: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        val uri: Uri = found ?: return false
        val type = intent.type ?: contentResolver.getType(uri) ?: ""
        when {
            type == "application/pdf" || uri.toString().lowercase().endsWith(".pdf") -> app.importPdfUri(uri)
            type.startsWith("image/") -> app.insertImageUris(listOf(uri), app.board.viewCenterInPage())
            else -> app.confirmDiscard { app.openUri(uri) }
        }
        setIntent(Intent())
        return true
    }

    // ------------------------------------------------------------------------- FileBridge

    private fun launch(intent: Intent, callback: (Intent?) -> Unit) {
        val code = nextRequest++
        pending[code] = callback
        try {
            startActivityForResult(intent, code)
        } catch (e: Exception) {
            pending.remove(code)
            app.toast.show("No file picker is available on this device.")
            callback(null)
        }
    }

    override fun pickDocuments(mimeTypes: Array<String>, multiple: Boolean, callback: (List<Uri>) -> Unit) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
        if (mimeTypes.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        launch(i) { data ->
            val out = ArrayList<Uri>()
            data?.clipData?.let { clip -> for (k in 0 until clip.itemCount) out.add(clip.getItemAt(k).uri) }
            if (out.isEmpty()) data?.data?.let { out.add(it) }
            for (u in out) persist(u, data)
            callback(out)
        }
    }

    override fun createDocument(fileName: String, mimeType: String, callback: (Uri?) -> Unit) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mimeType
        i.putExtra(Intent.EXTRA_TITLE, fileName)
        launch(i) { data ->
            val uri = data?.data
            if (uri != null) persist(uri, data)
            callback(uri)
        }
    }

    private fun persist(uri: Uri, data: Intent?) {
        val flags = (data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags.takeIf { it != 0 } ?: Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Not every provider offers persistable permissions; the file still works this session.
        }
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val cb = pending.remove(requestCode) ?: return
        cb(if (resultCode == RESULT_OK) data else null)
    }

    override val isFullScreen get() = fullScreen

    override fun setFullScreen(on: Boolean) {
        fullScreen = on
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!on)
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullScreen) setFullScreen(true)
    }

    override fun onPause() {
        super.onPause()
        app.onPause()
    }

    @Deprecated("Framework Activity API")
    override fun onBackPressed() {
        when {
            app.hasOverlay -> app.closeOverlay()
            app.popovers.isOpen -> app.popovers.closeAll()
            app.editors.isEditingText -> app.editors.commitAll()
            !app.board.selection.isEmpty -> app.board.selection.clear()
            else -> app.confirmDiscard {
                app.autosave.discard()
                finish()
            }
        }
    }

    /** Keyboard shortcuts for boards and tablets with a keyboard (as on the desktop). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (app.editors.isEditingText || currentFocus is android.widget.EditText) return super.onKeyDown(keyCode, event)
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        when {
            ctrl && keyCode == KeyEvent.KEYCODE_Z && !shift -> app.doc.undo()
            ctrl && (keyCode == KeyEvent.KEYCODE_Y || (keyCode == KeyEvent.KEYCODE_Z && shift)) -> app.doc.redo()
            ctrl && keyCode == KeyEvent.KEYCODE_S && shift -> app.saveAs()
            ctrl && keyCode == KeyEvent.KEYCODE_S -> app.save()
            ctrl && keyCode == KeyEvent.KEYCODE_O -> app.open()
            ctrl && keyCode == KeyEvent.KEYCODE_N -> app.newLesson()
            ctrl && keyCode == KeyEvent.KEYCODE_C -> app.edit.copySelection()
            ctrl && keyCode == KeyEvent.KEYCODE_X -> app.edit.cutSelection()
            ctrl && keyCode == KeyEvent.KEYCODE_V -> if (app.edit.paste(app.board.viewCenterInPage())) app.activateTool(ToolId.Select)
            ctrl && keyCode == KeyEvent.KEYCODE_D -> app.edit.duplicateSelection()
            ctrl && keyCode == KeyEvent.KEYCODE_A -> { app.activateTool(ToolId.Select); app.edit.selectAll() }
            ctrl && keyCode == KeyEvent.KEYCODE_M -> app.addPage()
            ctrl && keyCode == KeyEvent.KEYCODE_0 -> app.board.fitPage()
            ctrl && (keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_EQUALS) -> app.board.zoomStep(1)
            ctrl && keyCode == KeyEvent.KEYCODE_MINUS -> app.board.zoomStep(-1)
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL || keyCode == KeyEvent.KEYCODE_DEL -> app.edit.deleteSelection()
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> app.doc.setCurrentPage(app.doc.currentPageIndex + 1)
            keyCode == KeyEvent.KEYCODE_PAGE_UP -> app.doc.setCurrentPage(app.doc.currentPageIndex - 1)
            keyCode == KeyEvent.KEYCODE_F11 -> app.toggleFullScreen()
            keyCode == KeyEvent.KEYCODE_P -> app.activateTool(ToolId.Pen)
            keyCode == KeyEvent.KEYCODE_E -> app.activateTool(ToolId.Eraser)
            keyCode == KeyEvent.KEYCODE_V -> app.activateTool(ToolId.Select)
            keyCode == KeyEvent.KEYCODE_T -> app.activateTool(ToolId.Text)
            keyCode == KeyEvent.KEYCODE_ESCAPE -> if (app.popovers.isOpen) app.popovers.closeAll() else app.board.selection.clear()
            keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN) && !app.board.selection.isEmpty -> {
                val step = if (shift) 20.0 else 2.0
                val d = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> com.classboard.app.core.Pt(-step, 0.0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> com.classboard.app.core.Pt(step, 0.0)
                    KeyEvent.KEYCODE_DPAD_UP -> com.classboard.app.core.Pt(0.0, -step)
                    else -> com.classboard.app.core.Pt(0.0, step)
                }
                app.board.selectTool.nudge(d)
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
