package com.univibe.app.features.auth

import com.univibe.app.ui.components.VibeIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.univibe.app.features.legal.TermsOfServiceBox
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.theme.Spacing

/** Shared layout for auth screens: insets, keyboard, scrolling, optional back button and heading. */
@Composable
fun AuthScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (onBack != null) VibeIconButton(VibeIcons.Back, "Back", onBack, Modifier.padding(start = 0.dp))
        else Spacer(Modifier.height(48.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

enum class LegalDoc(val title: String) { Terms("Terms of Service"), Privacy("Privacy Policy") }

/**
 * The Terms of Service are real (see features/legal). The Privacy Policy is still a placeholder: write it and
 * show it here before UniVibe is released to students.
 */
@Composable
fun LegalSheet(doc: LegalDoc, onDismiss: () -> Unit) {
    VibeBottomSheet(onDismiss = onDismiss) {
        Text(doc.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
        when (doc) {
            LegalDoc.Terms -> TermsOfServiceBox()
            LegalDoc.Privacy -> Text(
                "The Privacy Policy isn't published yet. It will be available here before UniVibe is released to students.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
