package com.univibe.app.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** The outcome of asking the person to pick a Google account. Cancelling is a normal outcome, not an error. */
sealed interface GoogleCredentialResult {
    /** An ID token for the server to verify. Never trusted by the app, never stored, never logged. */
    data class Token(val idToken: String) : GoogleCredentialResult
    data object Cancelled : GoogleCredentialResult
    /** No Google account on the device (or none the person could pick). */
    data object NoAccount : GoogleCredentialResult
    /** Google Play services or Credential Manager is missing or too old on this device. */
    data object Unavailable : GoogleCredentialResult
    data object Failed : GoogleCredentialResult
}

/** Android's password-manager side of Credential Manager: saving or updating the email/password sign-in. */
enum class SavePasswordResult { Saved, Declined, Unavailable }

/** Wraps Credential Manager so view models can be tested without Android. */
interface GoogleCredentialProvider {
    /** True when a server client id is configured. */
    val isConfigured: Boolean
    /** Shows Google's own account chooser ("Sign in with Google" flow). [activityContext] must be an Activity. */
    suspend fun requestIdToken(activityContext: Context, nonce: String): GoogleCredentialResult
    /** Offers to save (or update) the password in the person's password manager. Best effort; never required. */
    suspend fun savePassword(activityContext: Context, email: String, password: String): SavePasswordResult
    /** Tells Credential Manager the person signed out, so Google does not sign them straight back in. */
    suspend fun clearSignInState(context: Context)
}

class CredentialManagerGoogleProvider(private val serverClientId: String) : GoogleCredentialProvider {
    override val isConfigured: Boolean get() = serverClientId.isNotBlank()

    override suspend fun requestIdToken(activityContext: Context, nonce: String): GoogleCredentialResult {
        if (!isConfigured) return GoogleCredentialResult.Unavailable
        // The explicit button flow: always Google's account chooser, including "add another account" when none is on the device.
        val option = GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleCredentialResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleCredentialResult.Failed
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleCredentialResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleCredentialResult.NoAccount
        } catch (e: GetCredentialProviderConfigurationException) {
            GoogleCredentialResult.Unavailable
        } catch (e: GoogleIdTokenParsingException) {
            GoogleCredentialResult.Failed
        } catch (e: GetCredentialException) {
            GoogleCredentialResult.Failed
        }
    }

    override suspend fun savePassword(activityContext: Context, email: String, password: String): SavePasswordResult =
        try {
            CredentialManager.create(activityContext).createCredential(activityContext, CreatePasswordRequest(id = email, password = password))
            SavePasswordResult.Saved
        } catch (e: CreateCredentialCancellationException) {
            SavePasswordResult.Declined
        } catch (e: CreateCredentialException) {
            SavePasswordResult.Unavailable
        }

    override suspend fun clearSignInState(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}

/** Used when Google sign-in is off (no server client id) and in tests. */
object NoGoogleCredentialProvider : GoogleCredentialProvider {
    override val isConfigured = false
    override suspend fun requestIdToken(activityContext: Context, nonce: String) = GoogleCredentialResult.Unavailable
    override suspend fun savePassword(activityContext: Context, email: String, password: String) = SavePasswordResult.Unavailable
    override suspend fun clearSignInState(context: Context) = Unit
}
