package com.univibe.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Keeps this device's FCM token registered with the server for the signed-in student.
 * The token is only a delivery address: the server identifies the student from the access token on the request.
 * Without google-services.json (no Firebase project yet) or without a server connection, everything here is a no-op.
 */
class PushRegistrar(
    private val context: Context,
    private val api: () -> AuthedApi?,
    private val userId: () -> String?,
) {
    private val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Random per install, survives token changes. Not a secret and not an identity. */
    private fun installationId(): String =
        prefs.getString(KEY_INSTALLATION, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_INSTALLATION, it).apply() }

    private fun firebaseReady() = FirebaseApp.getApps(context).isNotEmpty()

    /** Firebase says the token changed. */
    fun onNewToken(token: String) {
        scope.launch { runCatching { register(token) } }
    }

    /** Registers the current token if this student on this device has not been registered with it yet. */
    suspend fun registerIfNeeded() {
        if (!firebaseReady()) return
        val token = currentToken() ?: return
        runCatching { register(token) } // offline or server hiccup: tried again at the next sign-in or app start
    }

    private suspend fun register(token: String) {
        lock.withLock {
            val api = api() ?: return@withLock
            val user = userId() ?: return@withLock
            val marker = "$user:${token.hashCode()}"
            if (prefs.getString(KEY_REGISTERED, null) == marker) return@withLock // server already knows; do not repeat needlessly
            api.send(
                "PUT", "/api/v1/me/devices",
                JSONObject().put("installationId", installationId()).put("fcmToken", token),
                ok = setOf(204),
            )
            prefs.edit().putString(KEY_REGISTERED, marker).apply()
        }
    }

    /** Called just before log-out: this device stops receiving this student's pushes. Best effort. */
    suspend fun unregister() {
        runCatching {
            api()?.send("DELETE", "/api/v1/me/devices/${installationId()}", ok = setOf(204))
        }
        prefs.edit().remove(KEY_REGISTERED).apply()
        // A fresh token for the next person who signs in on this phone.
        if (firebaseReady()) runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
        }
    }

    private companion object {
        const val KEY_INSTALLATION = "installation_id"
        const val KEY_REGISTERED = "registered"
    }
}
