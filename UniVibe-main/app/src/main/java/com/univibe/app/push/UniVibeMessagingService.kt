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
        val prefs = runBlocking { AppContainer.settingsRepository.settings.first() }
        if (!PushPolicy.shouldShow(event, prefs.quietHours, LocalTime.now().hour)) return
        val wantsPreview = prefs.showMessagePreview && event.conversationId != null
        val preview = if (wantsPreview) runBlocking { AppContainer.messagePreview(event.conversationId!!) } else null
        PushNotifier.show(applicationContext, PushPolicy.withPreview(PushPolicy.forDisplay(event, prefs.showMessagePreview), preview))
    }
}
