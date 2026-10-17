package com.univibe.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.univibe.app.app.AppContainer

/** Receives FCM. The payload only carries ids; opening the notification loads the real content from the API. */
class UniVibeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AppContainer.pushRegistrar.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val event = PushPayload.parse(message.data) ?: return
        PushNotifier.show(applicationContext, event)
    }
}
