package com.univibe.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.univibe.app.ui.theme.BrandMint
import com.univibe.app.ui.theme.BrandTeal

/**
 * Original UniVibe mark: a "U" cradle (the campus) with a spark above one arm
 * (the connection between people). Scales as a vector via Canvas.
 */
@Composable
fun UniVibeMark(modifier: Modifier = Modifier, markSize: Dp = 40.dp) {
    Canvas(modifier.size(markSize).semantics { contentDescription = "UniVibe logo" }) {
        val w = this.size.minDimension
        val path = Path().apply {
            moveTo(w * 0.22f, w * 0.32f)
            lineTo(w * 0.22f, w * 0.52f)
            arcTo(Rect(w * 0.22f, w * 0.24f, w * 0.78f, w * 0.80f), 180f, -180f, false)
            lineTo(w * 0.78f, w * 0.40f)
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(listOf(BrandTeal, BrandMint), Offset(0f, 0f), Offset(w, w)),
            style = Stroke(width = w * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(BrandMint, radius = w * 0.075f, center = Offset(w * 0.78f, w * 0.17f))
    }
}

@Composable
fun UniVibeWordmark(modifier: Modifier = Modifier, fontSize: Int = 24) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("Uni") }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Vibe") }
        },
        style = MaterialTheme.typography.headlineMedium.copy(fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold),
        modifier = modifier,
    )
}

@Composable
fun UniVibeLockup(modifier: Modifier = Modifier, markSize: Dp = 36.dp, fontSize: Int = 24) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        UniVibeMark(markSize = markSize)
        Spacer(Modifier.width(6.dp))
        UniVibeWordmark(fontSize = fontSize)
    }
}
