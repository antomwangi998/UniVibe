package com.univibe.app.features.auth

import com.univibe.app.ui.components.VibeIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LabeledCheckbox
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.PasswordStrengthMeter
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibePasswordField
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.theme.Spacing

@Composable
fun SignUpScreen(onBack: () -> Unit, onLogIn: () -> Unit, viewModel: SignUpViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var legal by remember { mutableStateOf<LegalDoc?>(null) }
    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })

    AuthScaffold(title = "Create your account", subtitle = "Join your campus community. It only takes a minute.", onBack = onBack) {
        state.error?.let { MessageBanner(it, BannerKind.Error) }

        VibeTextField(
            value = state.fullName, onValueChange = viewModel::onName, label = "Full name",
            leadingIcon = VibeIcons.Profile,
            isError = state.nameError != null, supportingText = state.nameError,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            keyboardActions = next,
        )
        VibeTextField(
            value = state.email, onValueChange = viewModel::onEmail, label = "University email",
            leadingIcon = VibeIcons.Mail,
            isError = state.emailError != null, supportingText = state.emailError ?: state.domainHint,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = next,
        )
        VibeTextField(
            value = state.admission, onValueChange = viewModel::onAdmission, label = "Admission number (optional)",
            leadingIcon = VibeIcons.Badge,
            isError = state.admissionError != null,
            supportingText = state.admissionError ?: "Helps verify you as a student. You can add it later.",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = next,
        )

        // One university: show it read-only. Several: let the student choose.
        if (state.universities.size <= 1) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(VibeIcons.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        state.university?.name ?: "Loading university…",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = Spacing.md),
                    )
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                state.universities.forEach { u ->
                    InterestChip(u.name, selected = state.universityId == u.id, onClick = { viewModel.onUniversity(u.id) })
                }
            }
        }

        VibePasswordField(
            value = state.password, onValueChange = viewModel::onPassword, label = "Password",
            isError = state.passwordError != null,
            supportingText = state.passwordError ?: "At least 8 characters with a letter and a number.",
            keyboardActions = next,
        )
        PasswordStrengthMeter(state.strength)
        VibePasswordField(
            value = state.confirm, onValueChange = viewModel::onConfirm, label = "Confirm password",
            isError = state.confirmError != null, supportingText = state.confirmError,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )

        LabeledCheckbox(state.acceptedTerms, viewModel::onTerms) {
            val link = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
            val text = buildAnnotatedString {
                append("I agree to the ")
                pushStringAnnotation("doc", "terms"); withStyle(link) { append("Terms of Service") }; pop()
                append(" and ")
                pushStringAnnotation("doc", "privacy"); withStyle(link) { append("Privacy Policy") }; pop()
            }
            @Suppress("DEPRECATION")
            ClickableText(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                onClick = { offset ->
                    text.getStringAnnotations("doc", offset, offset).firstOrNull()?.let {
                        legal = if (it.item == "terms") LegalDoc.Terms else LegalDoc.Privacy
                    }
                },
            )
        }
        state.termsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        VibeButton("Create account", viewModel::submit, Modifier.fillMaxWidth(), loading = state.submitting)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Already have an account?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onLogIn) { Text("Log in") }
        }
    }

    legal?.let { LegalSheet(it) { legal = null } }
}
