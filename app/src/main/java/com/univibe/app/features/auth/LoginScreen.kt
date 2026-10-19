package com.univibe.app.features.auth

import com.univibe.app.ui.components.VibeIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.LabeledCheckbox
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibePasswordField
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.loginEmailAutofill
import com.univibe.app.ui.components.loginPasswordAutofill

@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
    notice: String?,
    viewModel: LoginViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var legal by remember { mutableStateOf<LegalDoc?>(null) }

    AuthScaffold(
        title = "Welcome back",
        subtitle = "Log in to pick up where you left off.",
        onBack = onBack,
        showLogo = true,
    ) {
        if (notice != null) MessageBanner(notice, BannerKind.Info)

        GoogleSection()

        state.error?.let { MessageBanner(it, BannerKind.Error) }
        VibeTextField(
            value = state.email,
            onValueChange = viewModel::onEmail,
            modifier = Modifier.loginEmailAutofill(),
            label = "Email",
            leadingIcon = VibeIcons.Mail,
            isError = state.emailError != null,
            supportingText = state.emailError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        VibePasswordField(
            value = state.password,
            onValueChange = viewModel::onPassword,
            modifier = Modifier.loginPasswordAutofill(),
            label = "Password",
            isError = state.passwordError != null,
            supportingText = state.passwordError,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); viewModel.submit() }),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LabeledCheckbox(state.rememberMe, viewModel::onRemember, Modifier.weight(1f)) {
                Text("Keep me signed in", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onForgotPassword) { Text("Forgot password?") }
        }
        VibeButton("Log in", viewModel::submit, Modifier.fillMaxWidth(), loading = state.submitting)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("New to UniVibe?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onSignUp) { Text("Create account") }
        }
        LegalNotice(onOpen = { legal = it })
    }
    legal?.let { LegalSheet(it) { legal = null } }
}
