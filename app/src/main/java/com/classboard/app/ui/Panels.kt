package com.classboard.app.ui

import com.classboard.app.ui.panels.BoardPanels
import com.classboard.app.ui.panels.CalculatorPanel
import com.classboard.app.ui.panels.DrawingPanels
import com.classboard.app.ui.panels.LessonPanels
import com.classboard.app.ui.panels.MagicPanel
import com.classboard.app.ui.panels.MathPanels
import com.classboard.app.ui.panels.PageNavigatorPanel

/** Builds the popover for a key (titles and widths as in the desktop app). */
object Panels {
    fun create(app: App, key: String): PopoverHost.PopoverSpec? = when (key) {
        "more" -> PopoverHost.PopoverSpec("More", BoardPanels.more(app), 420.0)
        "pen" -> PopoverHost.PopoverSpec("Pen", DrawingPanels.pen(app), 360.0)
        "eraser" -> PopoverHost.PopoverSpec("Eraser", DrawingPanels.eraser(app), 330.0)
        "select" -> PopoverHost.PopoverSpec("Select", DrawingPanels.select(app), 330.0)
        "shapes" -> PopoverHost.PopoverSpec("Shapes", DrawingPanels.shapes(app), 340.0)
        "text" -> PopoverHost.PopoverSpec("Text", DrawingPanels.text(app), 340.0)
        "color" -> PopoverHost.PopoverSpec("Colour", DrawingPanels.color(app), 300.0)
        "geometry" -> PopoverHost.PopoverSpec("Geometry", MathPanels.geometry(app, full = true), 460.0)
        "measure" -> PopoverHost.PopoverSpec("Measurements", MathPanels.geometry(app, full = false), 420.0)
        "equation" -> PopoverHost.PopoverSpec("Equation", MathPanels.equation(app), 560.0)
        "function" -> PopoverHost.PopoverSpec("Function / Graph", MathPanels.function(app), 480.0)
        "table" -> PopoverHost.PopoverSpec("Table", MathPanels.table(app), 330.0)
        "insert" -> PopoverHost.PopoverSpec("Insert", BoardPanels.insert(app), 420.0)
        "edit" -> PopoverHost.PopoverSpec("Edit", BoardPanels.edit(app), 420.0)
        "pageactions" -> PopoverHost.PopoverSpec("Page", BoardPanels.pageActions(app), 500.0)
        "pagesize" -> PopoverHost.PopoverSpec("Page size", BoardPanels.pageSize(app), 520.0)
        "background" -> PopoverHost.PopoverSpec("Background", BoardPanels.background(app), 460.0)
        "view" -> PopoverHost.PopoverSpec("View", BoardPanels.view(app), 480.0)
        "scale" -> PopoverHost.PopoverSpec("Scale", BoardPanels.scale(app), 480.0)
        "properties" -> PropertiesPanel.create(app)
        "magic" -> PopoverHost.PopoverSpec("✨ Magic Equation Maker", MagicPanel.create(app), 520.0)
        "lesson" -> PopoverHost.PopoverSpec("File", LessonPanels.lesson(app), 420.0)
        "templates" -> PopoverHost.PopoverSpec("Templates", LessonPanels.templates(app), 560.0)
        "import" -> PopoverHost.PopoverSpec("Import", LessonPanels.import(app), 420.0)
        "export" -> PopoverHost.PopoverSpec("Export", LessonPanels.export(app), 520.0)
        "pdf" -> PopoverHost.PopoverSpec("PDF", LessonPanels.exportPages(app, App.ExportFormat.Pdf), 400.0)
        "pptx" -> PopoverHost.PopoverSpec("PowerPoint", LessonPanels.exportPages(app, App.ExportFormat.Pptx), 400.0)
        "calculator" -> PopoverHost.PopoverSpec("Calculator", CalculatorPanel.create(app), 470.0)
        "settings" -> PopoverHost.PopoverSpec("Settings", LessonPanels.settings(app), 400.0)
        "pages" -> PopoverHost.PopoverSpec("Pages", PageNavigatorPanel.create(app), 560.0)
        else -> null
    }
}
