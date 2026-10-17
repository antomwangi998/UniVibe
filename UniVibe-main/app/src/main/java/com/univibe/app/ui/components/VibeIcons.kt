package com.univibe.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Smooth rounded line icons for the bottom bar: round caps and joins, curved corners.
 * Selected versions get a heavier stroke plus a soft tinted fill.
 */
object VibeIcons {
    private fun icon(name: String, selected: Boolean, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = if (selected) SolidColor(Color.Black.copy(alpha = 0.22f)) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = if (selected) 2.2f else 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private const val HOME =
        "M4.5 10.3 L12 4.3 L19.5 10.3 V18.5 Q19.5 20 18 20 H15 V15 Q15 14 14 14 H10 Q9 14 9 15 V20 H6 Q4.5 20 4.5 18.5 Z"
    private const val COMPASS_RING = "M3.5 12 A8.5 8.5 0 1 0 20.5 12 A8.5 8.5 0 1 0 3.5 12 Z"
    private const val COMPASS_NEEDLE = "M15.6 8.4 L13.4 13.4 L8.4 15.6 L10.6 10.6 Z"
    private const val CHAT =
        "M6 5 H18 Q20.5 5 20.5 7.5 V13.5 Q20.5 16 18 16 H12 L8 19.5 V16 H6 Q3.5 16 3.5 13.5 V7.5 Q3.5 5 6 5 Z"
    private const val CAL_BODY =
        "M6.5 5.5 H17.5 Q20 5.5 20 8 V17.5 Q20 20 17.5 20 H6.5 Q4 20 4 17.5 V8 Q4 5.5 6.5 5.5 Z"
    private const val PERSON_HEAD = "M8.5 8 A3.5 3.5 0 1 0 15.5 8 A3.5 3.5 0 1 0 8.5 8 Z"
    private const val PERSON_BODY = "M5 19.5 Q5 14.8 12 14.8 Q19 14.8 19 19.5 Z"

    val Home = icon("Home", false, HOME)
    val HomeSelected = icon("HomeSelected", true, HOME)
    val Discover = icon("Discover", false, COMPASS_RING, COMPASS_NEEDLE)
    val DiscoverSelected = icon("DiscoverSelected", true, COMPASS_RING, COMPASS_NEEDLE)
    val Chats = icon("Chats", false, CHAT)
    val ChatsSelected = icon("ChatsSelected", true, CHAT)
    val Events = icon("Events", false, CAL_BODY, "M4 10 H20", "M8.5 3.5 V7", "M15.5 3.5 V7")
    val EventsSelected = icon("EventsSelected", true, CAL_BODY, "M4 10 H20", "M8.5 3.5 V7", "M15.5 3.5 V7")
    val Profile = icon("Profile", false, PERSON_HEAD, PERSON_BODY)
    val ProfileSelected = icon("ProfileSelected", true, PERSON_HEAD, PERSON_BODY)
}
