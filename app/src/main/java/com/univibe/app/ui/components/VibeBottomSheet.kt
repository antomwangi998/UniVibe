package com.univibe.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibeBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val glass = LocalGlassEnabled.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // A thin teal highlight along the rounded top edge; skipped when glass is off.
        modifier = modifier.then(
            if (!glass) Modifier else Modifier.drawWithContent {
                drawContent()
                val inset = 28.dp.toPx()
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, primary.copy(alpha = 0.55f), Color.Transparent), startX = inset, endX = size.width - inset),
                    Offset(inset, 0.5.dp.toPx()), Offset(size.width - inset, 0.5.dp.toPx()), strokeWidth = 1.dp.toPx(),
                )
            },
        ),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = glassPopupColor(),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg).navigationBarsPadding(),
            content = content,
        )
    }
}
