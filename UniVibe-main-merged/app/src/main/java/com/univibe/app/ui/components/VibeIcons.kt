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
    private fun icon(name: String, fillAlpha: Float, strokeWidth: Float, vararg paths: String): ImageVector =
        build(name, fillAlpha, strokeWidth, false, paths)

    /** For direction-bearing glyphs (back, reply, chevron, send): flips automatically in right-to-left layouts. */
    private fun directional(name: String, strokeWidth: Float, vararg paths: String): ImageVector =
        build(name, 0f, strokeWidth, true, paths)

    private fun build(name: String, fillAlpha: Float, strokeWidth: Float, autoMirror: Boolean, paths: Array<out String>): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
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
    val Send = directional("Send", LINE, SEND_BODY, SEND_FOLD)
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

    // ---- Controls and navigation chrome ---------------------------------------------------
    val Back = directional("Back", LINE, "M19 12 H5", "M11 6 L5 12 L11 18")
    val Close = icon("Close", 0f, LINE, "M6.5 6.5 L17.5 17.5", "M17.5 6.5 L6.5 17.5")
    val Add = icon("Add", 0f, LINE, "M12 5.5 V18.5", "M5.5 12 H18.5")
    val ChevronRight = directional("ChevronRight", LINE, "M9.5 6 L15.5 12 L9.5 18")
    val ChevronDown = icon("ChevronDown", 0f, LINE, "M6 9.5 L12 15.5 L18 9.5")
    val ChevronUp = icon("ChevronUp", 0f, LINE, "M6 14.5 L12 8.5 L18 14.5")
    val Filter = icon("Filter", 0f, LINE, "M4.5 7 H19.5", "M7.5 12 H16.5", "M10.5 17 H13.5")
    val Search = icon("Search", 0f, LINE, "M4.5 10.8 A6.3 6.3 0 1 0 17.1 10.8 A6.3 6.3 0 1 0 4.5 10.8 Z", "M15.6 15.6 L19.8 19.8")
    val Settings = icon(
        "Settings", 0f, LINE,
        "M4.5 7.5 H6.8", "M11.2 7.5 H19.5", "M6.8 7.5 A2.2 2.2 0 1 0 11.2 7.5 A2.2 2.2 0 1 0 6.8 7.5 Z",
        "M4.5 16.5 H12.8", "M17.2 16.5 H19.5", "M12.8 16.5 A2.2 2.2 0 1 0 17.2 16.5 A2.2 2.2 0 1 0 12.8 16.5 Z",
    )
    private const val RING = "M3.8 12 A8.2 8.2 0 1 0 20.2 12 A8.2 8.2 0 1 0 3.8 12 Z"
    val Info = icon("Info", 0f, LINE, RING, "M12 11 V16", "M12 8 H12.01")
    val CheckCircle = icon("CheckCircle", 0f, LINE, RING, "M8.5 12.3 L11 14.8 L15.8 9.5")
    val Block = icon("Block", 0f, LINE, RING, "M6.2 6.2 L17.8 17.8")

    // ---- Content actions ---------------------------------------------------------------------
    val Edit = icon(
        "Edit", 0f, LINE,
        "M5 19 L5.8 15.4 L15.8 5.4 Q16.8 4.4 17.8 5.4 L18.6 6.2 Q19.6 7.2 18.6 8.2 L8.6 18.2 Z", "M14 7.2 L16.8 10",
    )
    val Delete = icon(
        "Delete", 0f, LINE,
        "M5 7 H19", "M9.5 7 V5.2 Q9.5 4.5 10.2 4.5 H13.8 Q14.5 4.5 14.5 5.2 V7",
        "M6.8 7 L7.5 18.2 Q7.6 19.5 8.9 19.5 H15.1 Q16.4 19.5 16.5 18.2 L17.2 7", "M10.2 10.5 V16", "M13.8 10.5 V16",
    )
    val Report = icon(
        "Report", 0f, LINE,
        "M6 20 V4.5", "M6 5.5 Q9 4 12 5.5 Q15 7 18 5.5 V13 Q15 14.5 12 13 Q9 11.5 6 13",
    )
    val Reply = directional("Reply", LINE, "M10 6.5 L4.5 12 L10 17.5", "M4.5 12 H14 Q19.5 12 19.5 17.5 V18.5")
    val Pin = icon("Pin", 0f, LINE, "M9 4.5 H15 L14.2 10 L17 13 H7 L9.8 10 Z", "M12 13 V19.5")
    val Copy = icon(
        "Copy", 0f, LINE,
        "M10 9 H17.5 Q19 9 19 10.5 V17.5 Q19 19 17.5 19 H10 Q8.5 19 8.5 17.5 V10.5 Q8.5 9 10 9 Z",
        "M15.5 6.5 V6.5 Q15.5 5 14 5 H6.5 Q5 5 5 6.5 V14 Q5 15.5 6.5 15.5",
    )

    // ---- People ---------------------------------------------------------------------------------
    private const val ADD_HEAD = "M6.2 8 A3.3 3.3 0 1 0 12.8 8 A3.3 3.3 0 1 0 6.2 8 Z"
    private const val ADD_BODY = "M3.5 19 Q3.5 14.5 9.5 14.5 Q12 14.5 13.5 15.3"
    val PersonAdd = icon("PersonAdd", 0f, LINE, ADD_HEAD, ADD_BODY, "M18 9.5 V15.5", "M15 12.5 H21")
    val PersonRemove = icon("PersonRemove", 0f, LINE, ADD_HEAD, ADD_BODY, "M15 12.5 H21")
    val Groups = icon(
        "Groups", 0f, LINE,
        "M6 8.5 A3 3 0 1 0 12 8.5 A3 3 0 1 0 6 8.5 Z", "M3 19 Q3 14.5 9 14.5 Q15 14.5 15 19",
        "M15 5.8 A2.8 2.8 0 0 1 15 11.2", "M17 14.6 Q21 15 21 19",
    )

    // ---- Objects ----------------------------------------------------------------------------------
    val Lock = icon(
        "Lock", 0f, LINE,
        "M6.5 11 H17.5 Q18.5 11 18.5 12 V18.5 Q18.5 19.5 17.5 19.5 H6.5 Q5.5 19.5 5.5 18.5 V12 Q5.5 11 6.5 11 Z",
        "M8.5 11 V8.5 A3.5 3.5 0 0 1 15.5 8.5 V11",
    )
    val Place = icon(
        "Place", 0f, LINE,
        "M12 20 C12 20 5.5 14.5 5.5 9.8 A6.5 6.5 0 0 1 18.5 9.8 C18.5 14.5 12 20 12 20 Z",
        "M10.2 9.8 A1.8 1.8 0 1 0 13.8 9.8 A1.8 1.8 0 1 0 10.2 9.8 Z",
    )
    val Mail = icon(
        "Mail", 0f, LINE,
        "M5.5 6 H18.5 Q20 6 20 7.5 V16.5 Q20 18 18.5 18 H5.5 Q4 18 4 16.5 V7.5 Q4 6 5.5 6 Z", "M4.5 8 L12 13 L19.5 8",
    )
    private const val EYE = "M3 12 Q7 5.5 12 5.5 Q17 5.5 21 12 Q17 18.5 12 18.5 Q7 18.5 3 12 Z"
    private const val EYE_PUPIL = "M9.8 12 A2.2 2.2 0 1 0 14.2 12 A2.2 2.2 0 1 0 9.8 12 Z"
    val Visibility = icon("Visibility", 0f, LINE, EYE, EYE_PUPIL)
    val VisibilityOff = icon("VisibilityOff", 0f, LINE, EYE, EYE_PUPIL, "M5 19 L19 5")
    val Camera = icon(
        "Camera", 0f, LINE,
        "M4.5 8.5 Q4.5 7 6 7 H8 L9.3 5.2 H14.7 L16 7 H18 Q19.5 7 19.5 8.5 V17 Q19.5 18.5 18 18.5 H6 Q4.5 18.5 4.5 17 Z",
        "M8.8 12.8 A3.2 3.2 0 1 0 15.2 12.8 A3.2 3.2 0 1 0 8.8 12.8 Z",
    )
    val Video = icon(
        "Video", 0f, LINE,
        "M4.5 7.5 H13 Q14.5 7.5 14.5 9 V15 Q14.5 16.5 13 16.5 H4.5 Q3.5 16.5 3.5 15.5 V8.5 Q3.5 7.5 4.5 7.5 Z",
        "M14.5 11 L20.5 7.8 V16.2 L14.5 13",
    )
    val BellOff = icon("BellOff", 0f, LINE, BELL, BELL_CLAPPER, "M4.5 4.5 L19.5 19.5")

    // ---- Message reactions ------------------------------------------------------------------------
    val ThumbUp = icon(
        "ThumbUp", 0f, LINE,
        "M8 11 L11.5 4.8 Q13.5 4.8 13.5 7 V9.5 H17.8 Q19.5 9.5 19.2 11.2 L18.2 17.2 Q17.9 19 16.2 19 H8 Z", "M4.5 11 H8 V19 H4.5 Z",
    )
    val Laugh = icon("Laugh", 0f, LINE, RING, "M8 13.5 Q12 18 16 13.5 Z", "M9 9.8 H9.01", "M15 9.8 H15.01")
    val Sad = icon("Sad", 0f, LINE, RING, "M8.5 16 Q12 12.8 15.5 16", "M9 9.8 H9.01", "M15 9.8 H15.01")
    val Fire = icon(
        "Fire", 0f, LINE,
        "M12 20 C8 20 5.8 17.2 5.8 14.2 C5.8 11 8.5 9.5 9 6.5 C10.5 7.5 11.2 9 11.2 10.5 C12.8 9 13 7 12.5 4.5 C15.5 6.5 18.2 10 18.2 14.2 C18.2 17.2 16 20 12 20 Z",
    )
}
