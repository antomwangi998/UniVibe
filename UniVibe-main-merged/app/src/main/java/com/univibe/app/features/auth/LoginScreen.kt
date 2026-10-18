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

    AuthScaffold(title = "Welcome back", subtitle = "Log in with your university or registered email.", onBack = onBack) {
        if (notice != null) MessageBanner(notice, BannerKind.Info)
        state.error?.let { MessageBanner(it, BannerKind.Error) }

        VibeTextField(
            value = state.email,
            onValueChange = viewModel::onEmail,
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
    }
}
