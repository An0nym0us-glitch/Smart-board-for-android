package com.classboard.app.doc

import com.classboard.app.core.Pt
import com.classboard.app.geometry.GeometryObject
import com.classboard.app.geometry.MeasurementObject
import com.classboard.app.math.CoordinateSystem
import com.classboard.app.math.EquationObject
import com.classboard.app.math.GraphObject
import com.classboard.app.math.TableObject
import org.json.JSONObject

object ObjectFactory {
    fun create(type: ObjectType): BoardObject = when (type) {
        ObjectType.Stroke -> StrokeObject()
        ObjectType.Shape -> ShapeObject()
        ObjectType.Text -> TextObject()
        ObjectType.Equation -> EquationObject()
        ObjectType.Image -> ImageObject()
        ObjectType.Graph -> GraphObject()
        ObjectType.Geometry -> GeometryObject()
        ObjectType.Measurement -> MeasurementObject()
        ObjectType.Table -> TableObject()
    }

    /** Parses an object; unknown or malformed objects return null. */
    fun fromJson(o: JSONObject): BoardObject? {
        val type = ObjectType.fromKey(o.optString("type")) ?: return null
        val obj = create(type)
        return try {
            if (obj.fromJson(o)) obj else null
        } catch (e: Exception) {
            null
        }
    }
}

object CoordResolver {
    /** A graph's own system when all points lie inside it, else the page-global system. */
    fun resolve(page: Page?, global: CoordinateSystem?, points: List<Pt>, exclude: BoardObject? = null): CoordinateSystem? {
        if (page == null || points.isEmpty()) return global
        for (o in page.objects.asReversed()) {
            if (o === exclude) continue
            val cs = o.mathCoordinateSystem() ?: continue
            if (points.all { o.hitTest(it, 0.0) }) return cs
        }
        return global
    }
}
