package com.univibe.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.univibe.app.app.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalTime

/** Receives FCM. The payload only carries ids; opening the notification loads the real content from the API. */
class UniVibeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AppContainer.pushRegistrar.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val event = PushPayload.parse(message.data) ?: return
        // Quiet hours are a device setting, so the phone applies them. This runs on FCM's background thread.
        val quiet = runBlocking { AppContainer.settingsRepository.settings.first().quietHours }
        if (!PushPolicy.shouldShow(event, quiet, LocalTime.now().hour)) return
        PushNotifier.show(applicationContext, event)
    }
}
