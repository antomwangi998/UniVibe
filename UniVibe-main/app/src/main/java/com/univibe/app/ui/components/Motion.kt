package com.univibe.app.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** True when animations should be minimal (system animations off, or the in-app "Reduce motion" setting). */
val LocalReduceMotion = compositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean = LocalReduceMotion.current

fun systemAnimationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
