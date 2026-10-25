package com.univibe.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import com.univibe.app.app.AppContainer

/*
 * Autofill: these tell Android (and so Google Password Manager) what each field is. The fields themselves stay
 * ordinary Material text fields, which is what lets password managers fill and save them.
 */

/**
 * Registers the field with Android's autofill framework. Compose UI 1.7 (BOM 2024.09) keeps the newer
 * `contentType` semantics internal, so this uses the AutofillNode API that is public in this version.
 * [onFill] receives the value the password manager fills in.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.autofillAs(types: List<AutofillType>, onFill: (String) -> Unit): Modifier = composed {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val latest = rememberUpdatedState(onFill)
    val node = remember { AutofillNode(autofillTypes = types, onFill = { latest.value(it) }) }
    SideEffect { tree += node }
    this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focus ->
            autofill?.run { if (focus.isFocused) requestAutofillForNode(node) else cancelAutofillForNode(node) }
        }
}

/** Existing-account email field: suggests saved logins. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.loginEmailAutofill(onFill: (String) -> Unit): Modifier =
    autofillAs(listOf(AutofillType.Username, AutofillType.EmailAddress), onFill)

/** Existing-account password field. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.loginPasswordAutofill(onFill: (String) -> Unit): Modifier =
    autofillAs(listOf(AutofillType.Password), onFill)

/** Sign-up email field. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.newEmailAutofill(onFill: (String) -> Unit): Modifier =
    autofillAs(listOf(AutofillType.NewUsername, AutofillType.EmailAddress), onFill)

/** Sign-up and reset password fields: lets the password manager offer a strong generated password. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.newPasswordAutofill(onFill: (String) -> Unit): Modifier =
    autofillAs(listOf(AutofillType.NewPassword), onFill)

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.fullNameAutofill(onFill: (String) -> Unit): Modifier =
    autofillAs(listOf(AutofillType.PersonFullName), onFill)

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
