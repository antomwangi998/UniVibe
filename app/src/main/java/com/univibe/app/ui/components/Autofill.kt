package com.univibe.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import com.univibe.app.app.AppContainer

/*
 * Autofill: these tell Android (and so Google Password Manager) what each field is. The fields themselves stay
 * ordinary Material text fields, which is what lets password managers fill and save them.
 */

/** Existing-account email field: suggests saved logins. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.loginEmailAutofill(): Modifier = semantics { contentType = ContentType.Username + ContentType.EmailAddress }

/** Existing-account password field. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.loginPasswordAutofill(): Modifier = semantics { contentType = ContentType.Password }

/** Sign-up email field. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.newEmailAutofill(): Modifier = semantics { contentType = ContentType.NewUsername + ContentType.EmailAddress }

/** Sign-up and reset password fields: lets the password manager offer a strong generated password. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.newPasswordAutofill(): Modifier = semantics { contentType = ContentType.NewPassword }

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.fullNameAutofill(): Modifier = semantics { contentType = ContentType.PersonFullName }

/** Credential Manager needs the Activity; composables inside dialogs and sheets can hold a wrapper around it. */
fun Context.activityContext(): Context {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return this
}

/**
 * Offers to save or update the email and password in the person's password manager (Google Password Manager).
 * Optional and best effort: declining or an unavailable service changes nothing, and the password is never kept by the app.
 */
@Composable
fun rememberPasswordSaver(): suspend (String, String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        val save: suspend (String, String) -> Unit = { email, password ->
            AppContainer.credentialProvider.savePassword(context.activityContext(), email, password)
            Unit
        }
        save
    }
}
