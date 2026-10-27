package com.univibe.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The two icons of the photo editor, drawn like the rest of the icon family: 24 grid, 1.8 line, round caps. Tint them with Icon. */
object CropIcons {
    private fun line(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    val Crop: ImageVector = line("Crop", "M7 3.5 V16 Q7 17 8 17 H20.5", "M3.5 7 H16 Q17 7 17 8 V20.5")
    val Rotate: ImageVector = line("Rotate", "M19.5 12 A7.5 7.5 0 1 1 17.3 6.7", "M18.2 3.2 V7.2 H14.2")
}
