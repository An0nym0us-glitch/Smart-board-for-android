package com.classboard.app.ui

import android.graphics.Color
import android.graphics.Typeface

/** "Blackboard Dark" theme of the desktop ClassBoard; metrics are scaled by dp(). */
class Theme(var density: Float) {
    /** Interface size (menus + ribbon) chosen in Settings. */
    var uiScale = 1.0
    /** Extra factor for the ribbon only. */
    var ribbonScale = 1.0

    val window = Color.parseColor("#141816")
    val ribbon = Color.parseColor("#191d1b")
    val ribbonBorder = Color.parseColor("#2a302d")
    val buttonHover = Color.parseColor("#262c29")
    val buttonPressed = Color.parseColor("#313a35")
    val buttonChecked = Color.parseColor("#2a3a32")
    val accent = Color.parseColor("#5fd3a5")
    val accentText = Color.parseColor("#0d1a14")
    val text = Color.parseColor("#e9eeeb")
    val textMuted = Color.parseColor("#9aa7a0")
    val textDisabled = Color.parseColor("#56605b")
    val popover = Color.parseColor("#202624")
    val popoverBorder = Color.parseColor("#353e3a")
    val popoverHeader = Color.parseColor("#c9d3ce")
    val separator = Color.parseColor("#2f3633")
    val selection = Color.parseColor("#4fb3ff")
    val selectionFill = Color.parseColor("#1a4fb3ff")
    val handle = Color.WHITE
    val danger = Color.parseColor("#ef6a64")
    val success = Color.parseColor("#6fcf97")
    val input = Color.parseColor("#151a18")
    val inputBorder = Color.parseColor("#3a4440")
    val scrim = Color.parseColor("#99000000")

    val ribbonHeight = 78.0
    val ribbonButtonWidth = 78.0
    val iconSize = 26.0
    val ribbonIconSize = 28.0
    val cornerRadius = 12.0
    val popoverRadius = 18.0
    val popoverPadding = 16.0
    val popoverNotch = 12.0
    val spacing = 8.0
    val touchTarget = 56.0
    val fontSize = 15.0
    val smallFontSize = 12.5
    val titleFontSize = 17.0
    val handleSize = 14.0
    val handleHitSize = 44.0

    val typeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val typefaceBold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val typefaceMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Logical design pixels -> device pixels. */
    fun dp(v: Double): Float = (v * density * uiScale * BASE).toFloat()
    fun dpd(v: Double): Double = v * density * uiScale * BASE
    fun rdp(v: Double): Float = (v * density * uiScale * ribbonScale * BASE).toFloat()
    fun rdpd(v: Double): Double = v * density * uiScale * ribbonScale * BASE

    companion object {
        /** The desktop design targets large boards; Android dp are a little larger. */
        const val BASE = 0.82
    }
}
