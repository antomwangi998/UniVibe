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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.univibe.app.features.legal.PrivacyPolicyBox
import com.univibe.app.features.legal.TermsOfServiceBox
import com.univibe.app.ui.components.LabeledCheckbox
import com.univibe.app.ui.components.UniVibeMark
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
    showLogo: Boolean = false,
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
        if (showLogo) UniVibeMark(markSize = 56.dp)
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

enum class LegalDoc(val title: String) { Terms("Terms of Service"), Privacy("Privacy Policy") }

/** The Terms of Service and the Privacy Policy are real documents (see features/legal), shown in a sheet. */
@Composable
fun LegalSheet(doc: LegalDoc, onDismiss: () -> Unit) {
    VibeBottomSheet(onDismiss = onDismiss) {
        Text(doc.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
        when (doc) {
            LegalDoc.Terms -> TermsOfServiceBox()
            LegalDoc.Privacy -> PrivacyPolicyBox()
        }
    }
}

/** The Terms checkbox with tappable links to the real Terms of Service and Privacy Policy. */
@Composable
fun TermsCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, onOpen: (LegalDoc) -> Unit) {
    LabeledCheckbox(checked, onCheckedChange) {
        val text = legalLinkText("I agree to the ")
        @Suppress("DEPRECATION")
        ClickableText(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            onClick = { offset -> text.getStringAnnotations("doc", offset, offset).firstOrNull()?.let { onOpen(if (it.item == "terms") LegalDoc.Terms else LegalDoc.Privacy) } },
        )
    }
}

/** A short line under a sign-in button that points to the Terms and Privacy Policy. */
@Composable
fun LegalNotice(onOpen: (LegalDoc) -> Unit, modifier: Modifier = Modifier) {
    val text = legalLinkText("By continuing you agree to our ")
    @Suppress("DEPRECATION")
    ClickableText(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center),
        modifier = modifier.fillMaxWidth(),
        onClick = { offset -> text.getStringAnnotations("doc", offset, offset).firstOrNull()?.let { onOpen(if (it.item == "terms") LegalDoc.Terms else LegalDoc.Privacy) } },
    )
}

@Composable
private fun legalLinkText(prefix: String): AnnotatedString {
    val link = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
    return buildAnnotatedString {
        append(prefix)
        pushStringAnnotation("doc", "terms"); withStyle(link) { append("Terms of Service") }; pop()
        append(" and ")
        pushStringAnnotation("doc", "privacy"); withStyle(link) { append("Privacy Policy") }; pop()
        append(".")
    }
}
