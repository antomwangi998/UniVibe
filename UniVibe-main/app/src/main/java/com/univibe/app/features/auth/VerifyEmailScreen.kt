package com.univibe.app.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.theme.Spacing

@Composable
fun VerifyEmailScreen(
    email: String,
    viewModel: VerifyEmailViewModel = viewModel(key = rememberSaveable { "verify-${java.util.UUID.randomUUID()}" }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    AuthScaffold(
        title = "Verify your email",
        subtitle = "We sent a ${AuthValidators.CODE_LENGTH}-digit code to ${AuthValidators.maskEmail(email)}. Enter it below to continue.",
    ) {
        state.devHint?.let {
            MessageBanner("DEV MODE (debug build only): no email is really sent. Your code is $it.", BannerKind.Dev)
        }
        state.error?.let { MessageBanner(it, BannerKind.Error) }
        state.info?.let { MessageBanner(it, BannerKind.Info) }

        VibeTextField(
            value = state.code,
            onValueChange = viewModel::onCode,
            label = "Verification code",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); viewModel.verify() }),
            isError = state.error != null,
        )
        VibeButton("Verify", viewModel::verify, Modifier.fillMaxWidth(), loading = state.submitting)

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TextButton(onClick = viewModel::resend, enabled = state.cooldown == 0 && !state.resending) {
                Text(if (state.cooldown > 0) "Resend code in ${state.cooldown}s" else "Resend code")
            }
            VibeButton(
                "I've verified, refresh", viewModel::refreshStatus,
                style = VibeButtonStyle.Secondary, compact = true, loading = state.refreshing,
            )
            TextButton(onClick = viewModel::logOut) { Text("Use a different email") }
            Text(
                "Codes expire after 10 minutes. Check your spam folder if you can't find it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
