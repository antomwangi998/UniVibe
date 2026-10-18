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
 * True while the user has scrolled content away (down) and the app chrome should get out of the way.
 * Driven by the same scroll connection as the floating bar in AppShell, so the top toolbar and the
 * bottom bar always show and hide together.
 */
val LocalChromeHidden = compositionLocalOf { false }

/**
 * Bottom padding that lets the last item of a scrolling screen rest fully above the floating bar.
 * Content itself runs edge to edge underneath it.
 */
@Composable
fun rememberBottomClearance(): Dp =
    FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
