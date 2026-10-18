package com.univibe.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * UniVibe's single icon family.
 *
 * Grid: 24 x 24 with ~2.5 units of padding, so glyphs sit in a 19 x 19 live area.
 * Stroke: 1.8 for line icons (2.0 for the bottom bar's selected state), round caps and joins,
 * curved corners (about 1.5-2.5 radius). Filled variants reuse the exact outline geometry and
 * only add a fill, so switching state never makes the shape jump.
 *
 * Paths are painted black and meant to be tinted by [androidx.compose.material3.Icon].
 * Only the alpha of the fill matters: 0.22 = soft tinted selected body, 1.0 = solid.
 */
object VibeIcons {
    private fun icon(name: String, fillAlpha: Float, strokeWidth: Float, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = if (fillAlpha > 0f) SolidColor(Color.Black.copy(alpha = fillAlpha)) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private const val LINE = 1.8f
    private const val LINE_SELECTED = 2.0f
    private const val TINT = 0.22f
    private const val SOLID = 1f

    // ---- Navigation ----------------------------------------------------------------------
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

    val Home = icon("Home", 0f, LINE, HOME)
    val HomeSelected = icon("HomeSelected", TINT, LINE_SELECTED, HOME)
    val Discover = icon("Discover", 0f, LINE, COMPASS_RING, COMPASS_NEEDLE)
    val DiscoverSelected = icon("DiscoverSelected", TINT, LINE_SELECTED, COMPASS_RING, COMPASS_NEEDLE)
    val Chats = icon("Chats", 0f, LINE, CHAT)
    val ChatsSelected = icon("ChatsSelected", TINT, LINE_SELECTED, CHAT)
    val Events = icon("Events", 0f, LINE, CAL_BODY, "M4 10 H20", "M8.5 3.5 V7", "M15.5 3.5 V7")
    val EventsSelected = icon("EventsSelected", TINT, LINE_SELECTED, CAL_BODY, "M4 10 H20", "M8.5 3.5 V7", "M15.5 3.5 V7")
    val Profile = icon("Profile", 0f, LINE, PERSON_HEAD, PERSON_BODY)
    val ProfileSelected = icon("ProfileSelected", TINT, LINE_SELECTED, PERSON_HEAD, PERSON_BODY)

    // ---- Social actions ------------------------------------------------------------------
    private const val HEART =
        "M12 19.8 C12 19.8 4 15.2 4 9.2 C4 6.6 6 4.8 8.2 4.8 C9.8 4.8 11.2 5.7 12 7 C12.8 5.7 14.2 4.8 15.8 4.8 C18 4.8 20 6.6 20 9.2 C20 15.2 12 19.8 12 19.8 Z"
    private const val COMMENT =
        "M12 4.6 C7.6 4.6 4 7.7 4 11.6 C4 13.6 4.9 15.3 6.4 16.6 L5.6 19.6 L9 18.2 C9.9 18.4 10.9 18.5 12 18.5 C16.4 18.5 20 15.5 20 11.6 C20 7.7 16.4 4.6 12 4.6 Z"
    private const val SHARE_NODE_TOP = "M15.6 5.8 A2.4 2.4 0 1 0 20.4 5.8 A2.4 2.4 0 1 0 15.6 5.8 Z"
    private const val SHARE_NODE_MID = "M3.6 12 A2.4 2.4 0 1 0 8.4 12 A2.4 2.4 0 1 0 3.6 12 Z"
    private const val SHARE_NODE_BOT = "M15.6 18.2 A2.4 2.4 0 1 0 20.4 18.2 A2.4 2.4 0 1 0 15.6 18.2 Z"
    private const val BOOKMARK =
        "M7.5 4.5 H16.5 Q18 4.5 18 6 V19.2 L12 15.6 L6 19.2 V6 Q6 4.5 7.5 4.5 Z"
    private const val BELL =
        "M5.6 17.4 L6.8 16 V10.6 Q6.8 5.2 12 5.2 Q17.2 5.2 17.2 10.6 V16 L18.4 17.4 Z"
    private const val BELL_CLAPPER = "M10.2 19.9 Q12 21.4 13.8 19.9"
    private const val SEND_BODY = "M20.4 3.8 L14.2 20.2 L10.6 13.4 L3.8 9.8 Z"
    private const val SEND_FOLD = "M20.4 3.8 L10.6 13.4"

    val Heart = icon("Heart", 0f, LINE, HEART)
    val HeartFilled = icon("HeartFilled", SOLID, LINE, HEART)
    val Comment = icon("Comment", 0f, LINE, COMMENT)
    val Share = icon(
        "Share", 0f, LINE,
        SHARE_NODE_TOP, SHARE_NODE_MID, SHARE_NODE_BOT, "M8.1 10.8 L15.9 7", "M8.1 13.2 L15.9 17",
    )
    val Bookmark = icon("Bookmark", 0f, LINE, BOOKMARK)
    val BookmarkFilled = icon("BookmarkFilled", SOLID, LINE, BOOKMARK)
    /** Three dots, drawn as zero-length round-capped strokes so they stay optically even. */
    val More = icon("More", 0f, 2.8f, "M5.5 12 H5.51", "M12 12 H12.01", "M18.5 12 H18.51")
    val Bell = icon("Bell", 0f, LINE, BELL, BELL_CLAPPER)
    val BellFilled = icon("BellFilled", SOLID, LINE, BELL, BELL_CLAPPER)

    // ---- Chat ------------------------------------------------------------------------------
    val Send = icon("Send", 0f, LINE, SEND_BODY, SEND_FOLD)
    val SendFilled = icon("SendFilled", SOLID, LINE, SEND_BODY)
    val Photo = icon(
        "Photo", 0f, LINE,
        "M6.5 4.5 H17.5 Q20 4.5 20 7 V17 Q20 19.5 17.5 19.5 H6.5 Q4 19.5 4 17 V7 Q4 4.5 6.5 4.5 Z",
        "M8.6 9.2 H8.61", "M4.4 16.4 L9.4 12 L13 15.2 L15.4 13.2 L19.6 16.8",
    )
    val Check = icon("Check", 0f, LINE, "M5.5 12.6 L9.8 16.8 L18.5 7.8")
    val DoubleCheck = icon("DoubleCheck", 0f, LINE, "M2.6 12.8 L6.9 17 L15.4 8.4", "M11.2 15.9 L12.1 16.8 L21 7.8")
    val Clock = icon(
        "Clock", 0f, LINE,
        "M3.8 12 A8.2 8.2 0 1 0 20.2 12 A8.2 8.2 0 1 0 3.8 12 Z", "M12 7.4 V12 L15 13.8",
    )
}
