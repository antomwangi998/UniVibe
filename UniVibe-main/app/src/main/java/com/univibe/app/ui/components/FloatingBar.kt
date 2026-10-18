package com.univibe.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Approximate height of the floating bar (without the system inset). */
val FloatingBarClearance = 96.dp

/** True while the floating bar is on screen. Screens use it to move floating buttons with it. */
val LocalFloatingBarVisible = compositionLocalOf { true }

/**
 * Bottom padding that lets the last item of a scrolling screen rest fully above the floating bar.
 * Content itself runs edge to edge underneath it.
 */
@Composable
fun rememberBottomClearance(): Dp =
    FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
