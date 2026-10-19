package com.univibe.app.features.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.univibe.app.data.prefs.ThemeMode
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.SignedOutReason
import com.univibe.app.domain.auth.userMessage
import com.univibe.app.navigation.AppShell

private fun keyOf(state: AuthState): Int = when (state) {
    AuthState.LoadingSession -> 0
    is AuthState.SignedOut -> 1
    is AuthState.EmailVerificationRequired -> 2
    is AuthState.OnboardingRequired -> 3
    is AuthState.Authenticated -> 4
    is AuthState.GoogleRegistrationRequired -> 5
    is AuthState.GoogleLinkRequired -> 6
}

/**
 * The single place that decides what the user sees. Because each state replaces the previous
 * composition, Back can never return a signed-in user to a login or sign-up screen.
 */
@Composable
fun AuthGate(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    sessionViewModel: SessionViewModel = viewModel(),
) {
    val state by sessionViewModel.state.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = state,
        // A solid backdrop so the cross-fade between screens never shows a black flash.
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        contentKey = ::keyOf,
        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
        label = "authGate",
    ) { s ->
        when (s) {
            AuthState.LoadingSession -> SessionLoadingScreen()
            is AuthState.SignedOut -> AuthNavHost(
                notice = when (s.reason) {
                    SignedOutReason.SessionExpired -> AuthError.SessionExpired.userMessage()
                    SignedOutReason.PasswordChanged -> "Password updated. Log in with your new password."
                    null -> null
                },
            )
            is AuthState.EmailVerificationRequired -> VerifyEmailScreen(email = s.email)
            is AuthState.OnboardingRequired -> OnboardingScreen(user = s.user)
            is AuthState.GoogleRegistrationRequired -> GoogleRegistrationScreen()
            is AuthState.GoogleLinkRequired -> GoogleLinkScreen()
            is AuthState.Authenticated -> AppShell(themeMode, onThemeModeChange, onLogOut = sessionViewModel::logOut)
        }
    }
}

private object AuthRoutes {
    const val WELCOME = "welcome"
    const val LOGIN = "login"
    const val SIGN_UP = "signup"
    const val FORGOT = "forgot"
}

@Composable
private fun AuthNavHost(notice: String?) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = AuthRoutes.WELCOME,
        enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(240)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(240)) },
        popExitTransition = { slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(180)) },
    ) {
        composable(AuthRoutes.WELCOME) {
            WelcomeScreen(
                onCreateAccount = { nav.navigate(AuthRoutes.SIGN_UP) { launchSingleTop = true } },
                onLogIn = { nav.navigate(AuthRoutes.LOGIN) { launchSingleTop = true } },
                notice = notice,
            )
        }
        composable(AuthRoutes.LOGIN) {
            LoginScreen(
                onBack = { nav.popBackStack() },
                onSignUp = { nav.navigate(AuthRoutes.SIGN_UP) { popUpTo(AuthRoutes.WELCOME); launchSingleTop = true } },
                onForgotPassword = { nav.navigate(AuthRoutes.FORGOT) { launchSingleTop = true } },
                notice = notice,
            )
        }
        composable(AuthRoutes.SIGN_UP) {
            SignUpScreen(
                onBack = { nav.popBackStack() },
                onLogIn = { nav.navigate(AuthRoutes.LOGIN) { popUpTo(AuthRoutes.WELCOME); launchSingleTop = true } },
            )
        }
        composable(AuthRoutes.FORGOT) {
            ForgotPasswordScreen(
                onBack = { nav.popBackStack() },
                onDone = { nav.popBackStack(AuthRoutes.LOGIN, inclusive = false) },
            )
        }
    }
}
