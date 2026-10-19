package com.univibe.app.features.auth

import com.univibe.app.ui.components.VibeIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.PasswordStrengthMeter
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibePasswordField
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.loginEmailAutofill
import com.univibe.app.ui.components.newPasswordAutofill
import com.univibe.app.ui.components.rememberPasswordSaver
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.ui.theme.Spacing

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: ForgotPasswordViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val savePassword = rememberPasswordSaver()

    val title = when (state.step) {
        ResetStep.RequestCode -> "Forgot password?"
        ResetStep.EnterNewPassword -> "Create a new password"
        ResetStep.Done -> "Password updated"
    }
    val subtitle = when (state.step) {
        ResetStep.RequestCode -> "Enter your registered email and we'll send you a reset code."
        ResetStep.EnterNewPassword -> null
        ResetStep.Done -> null
    }

    AuthScaffold(
        title = title, subtitle = subtitle,
        onBack = { if (state.step == ResetStep.EnterNewPassword) viewModel.backToEmail() else onBack() },
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
            },
            label = "resetStep",
        ) { step ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                when (step) {
                    ResetStep.RequestCode -> {
                        state.error?.let { MessageBanner(it, BannerKind.Error) }
                        VibeTextField(
                            value = state.email, onValueChange = viewModel::onEmail, label = "Email",
                            modifier = Modifier.loginEmailAutofill(),
                            leadingIcon = VibeIcons.Mail,
                            isError = state.emailError != null, supportingText = state.emailError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); viewModel.requestCode() }),
                        )
                        VibeButton("Send reset code", viewModel::requestCode, Modifier.fillMaxWidth(), loading = state.submitting)
                    }
                    ResetStep.EnterNewPassword -> {
                        state.info?.let { MessageBanner(it, BannerKind.Info) }
                        state.devHint?.let { MessageBanner("DEV MODE (debug build only): no email is really sent. Your code is $it.", BannerKind.Dev) }
                        state.error?.let { MessageBanner(it, BannerKind.Error) }
                        VibeTextField(
                            value = state.code, onValueChange = viewModel::onCode, label = "Reset code",
                            isError = state.codeError != null, supportingText = state.codeError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                        )
                        VibePasswordField(
                            value = state.password, onValueChange = viewModel::onPassword, label = "New password",
                            modifier = Modifier.newPasswordAutofill(),
                            isError = state.passwordError != null,
                            supportingText = state.passwordError ?: "At least ${AuthValidators.MIN_PASSWORD} characters with a letter and a number.",
                            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                        )
                        PasswordStrengthMeter(AuthValidators.passwordStrength(state.password))
                        VibePasswordField(
                            value = state.confirm, onValueChange = viewModel::onConfirm, label = "Confirm new password",
                            modifier = Modifier.newPasswordAutofill(),
                            isError = state.confirmError != null, supportingText = state.confirmError,
                            imeAction = ImeAction.Done,
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); viewModel.resetPassword(savePassword) }),
                        )
                        VibeButton("Update password", { viewModel.resetPassword(savePassword) }, Modifier.fillMaxWidth(), loading = state.submitting)
                        TextButton(onClick = viewModel::backToEmail, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use a different email") }
                    }
                    ResetStep.Done -> {
                        Icon(VibeIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text(
                            "Your password has been changed. Log in with your new password.",
                            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        VibeButton("Back to log in", onDone, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
