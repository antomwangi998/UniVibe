package com.univibe.app.features.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.app.AppContainer
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.GoogleSignInButton
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.OrDivider
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibePasswordField
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.activityContext
import com.univibe.app.ui.components.fullNameAutofill
import com.univibe.app.ui.components.loginPasswordAutofill
import com.univibe.app.ui.theme.Spacing

/**
 * The "Continue with Google" button, its messages and the "or" divider. Shown on the log-in and sign-up screens, and
 * only when this build is set up for Google sign-in. Cancelling Google's chooser leaves everything as it was.
 */
@Composable
fun GoogleSection(viewModel: GoogleAuthViewModel = viewModel()) {
    if (!viewModel.available) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        state.error?.let { MessageBanner(it, BannerKind.Error) }
        state.notice?.let { MessageBanner(it, BannerKind.Info) }
        GoogleSignInButton(
            onClick = {
                viewModel.continueWithGoogle { nonce -> AppContainer.credentialProvider.requestIdToken(context.activityContext(), nonce) }
            },
            loading = state.busy,
        )
        OrDivider()
    }
}

/** First-time Google person: confirm the name, pick the university, accept the Terms. The account is created on the button. */
@Composable
fun GoogleRegistrationScreen(viewModel: GoogleRegistrationViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var legal by remember { mutableStateOf<LegalDoc?>(null) }
    BackHandler(onBack = viewModel::cancel)

    AuthScaffold(
        title = "Finish creating your account",
        subtitle = "Google confirmed your email. Check your name and choose your university to join.",
        onBack = viewModel::cancel,
        showLogo = true,
    ) {
        state.error?.let { MessageBanner(it, BannerKind.Error) }

        VibeTextField(
            value = state.fullName, onValueChange = viewModel::onName, label = "Full name",
            modifier = Modifier.fullNameAutofill(viewModel::onName),
            leadingIcon = VibeIcons.Profile,
            isError = state.nameError != null, supportingText = state.nameError,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        // The email is what Google verified. It is shown, not editable.
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(VibeIcons.Mail, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(state.email, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.md))
            }
        }
        VibeTextField(
            value = state.admission, onValueChange = viewModel::onAdmission, label = "Admission number (optional)",
            leadingIcon = VibeIcons.Badge,
            isError = state.admissionError != null,
            supportingText = state.admissionError ?: "Helps verify you as a student. You can add it later.",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
        if (state.universities.size <= 1) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(VibeIcons.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(state.university?.name ?: "Loading university…", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.md))
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                state.universities.forEach { u -> InterestChip(u.name, selected = state.universityId == u.id, onClick = { viewModel.onUniversity(u.id) }) }
            }
        }
        // Signing in with Google does not make anyone a verified student; the server decides that from the email domain.
        state.domainHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        TermsCheckbox(state.acceptedTerms, viewModel::onTerms) { legal = it }
        state.termsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        VibeButton("Create account", viewModel::submit, Modifier.fillMaxWidth(), loading = state.submitting)
        TextButton(onClick = viewModel::cancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use a different account") }
    }
    legal?.let { LegalSheet(it) { legal = null } }
}

/** The email already belongs to a UniVibe account. Its password proves it is the same person before Google is linked. */
@Composable
fun GoogleLinkScreen(viewModel: GoogleLinkViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    BackHandler(onBack = viewModel::cancel)

    AuthScaffold(
        title = "Link your account",
        subtitle = "${state.email} already has a UniVibe account. Enter its password once to connect Google to it.",
        onBack = viewModel::cancel,
        showLogo = true,
    ) {
        state.error?.let { MessageBanner(it, BannerKind.Error) }
        VibePasswordField(
            value = state.password, onValueChange = viewModel::onPassword, label = "UniVibe password",
            modifier = Modifier.loginPasswordAutofill(viewModel::onPassword),
            isError = state.passwordError != null, supportingText = state.passwordError,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); viewModel.submit() }),
        )
        VibeButton("Link account", viewModel::submit, Modifier.fillMaxWidth(), loading = state.submitting)
        TextButton(onClick = viewModel::cancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
    }
}
