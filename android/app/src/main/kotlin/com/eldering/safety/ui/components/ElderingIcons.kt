package com.eldering.safety.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Stroke icons used by the design mockups (Lucide geometry, 24×24, stroke 2, round caps). Built from the same
 * SVG path data as docs/design so they match exactly, without pulling in an icon library. Tint with `Icon(tint)`.
 */
object ElderingIcons {
    val Bell = icon("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val Shield = icon("shield", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z")
    val ShieldCheck = icon("shield-check", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z", "M9 12l2 2 4-4")
    val MapPin = icon(
        "map-pin",
        "M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z",
        "M14.5 10a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z",
        strokeWidth = 2.2f,
    )
    val Crosshair = icon("crosshair", "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z", "M12 2v3M12 19v3M2 12h3M19 12h3")
    val Warning = icon(
        "alert-triangle",
        "M12 9v4M12 17h.01",
        "M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z",
        strokeWidth = 2.4f,
    )
    val Back = icon("chevron-left", "M15 18l-6-6 6-6", strokeWidth = 2.2f)
    val ChevronRight = icon("chevron-right", "M9 18l6-6-6-6")
    val Phone = icon(
        "phone",
        "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67" +
            "A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91" +
            "a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z",
    )
    val Plus = icon("plus", "M12 5v14M5 12h14")
    val Close = icon("x", "M18 6 6 18M6 6l12 12")
    val Check = icon("check", "M20 6 9 17l-5-5")
    val Trash = icon(
        "trash",
        "M3 6h18",
        "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
        "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
    )
    val Edit = icon("pencil", "M12 20h9", "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z")
    val Share = icon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13")
    val Copy = icon(
        "copy",
        "M10 8h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z",
        "M4 16a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2",
    )
    val User = icon("user", "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", "M16 7a4 4 0 1 1-8 0a4 4 0 1 1 8 0z")
    val Users = icon(
        "users",
        "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
        "M13 7a4 4 0 1 1-8 0a4 4 0 1 1 8 0z",
        "M22 21v-2a4 4 0 0 0-3-3.87",
        "M16 3.13a4 4 0 0 1 0 7.75",
    )
    val Navigation = icon("navigation", "M3 11l19-9-9 19-2-8-8-2z")
    val Battery = icon("battery", "M4 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M22 11v2")
    val Bolt = icon("zap", "M13 2 3 14h9l-1 8 10-12h-9l1-8z")
    val Message = icon("message", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
    val Activity = icon("activity", "M22 12h-4l-3 9L9 3l-3 9H2")
    val Settings = icon(
        "sliders",
        "M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3",
        "M1 14h6M9 8h6M17 16h6",
    )
    val LogOut = icon("log-out", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "M16 17l5-5-5-5", "M21 12H9")
    val Clock = icon("clock", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z", "M12 6v6l4 2")

    private fun icon(name: String, vararg paths: String, strokeWidth: Float = 2f): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = strokeWidth,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
