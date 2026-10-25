package com.univibe.app.push

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.univibe.app.R

object PushNotifier {
    /**
     * Shows one notification. Returns false when the person has notifications switched off (permission declined, or
     * turned off in system settings); the in-app notification list still has everything in that case.
     */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() below covers the Android 13 permission
    fun show(context: Context, event: PushEvent): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        NotificationChannels.ensure(context)
        // Tapping opens MainActivity through the same deep-link route as any univibe:// link. That covers a cold start,
        // an app that is already open, and a signed-out person (the link is kept until sign-in).
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(event.deepLink)).setPackage(context.packageName)
        val pending = PendingIntent.getActivity(
            context, event.tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, event.channel.id)
            .setSmallIcon(R.drawable.ic_stat_univibe)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(if (event.channel == PushChannel.DirectMessages || event.channel == PushChannel.GroupMessages) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // Shown instead on a locked screen (unless the person allowed sensitive content there).
            .setPublicVersion(
                NotificationCompat.Builder(context, event.channel.id)
                    .setSmallIcon(R.drawable.ic_stat_univibe)
                    .setContentTitle("UniVibe")
                    .setContentText("New message")
                    .build(),
            )
            .build()
        // Same tag and id replace the earlier notification, so a retried or repeated push never stacks.
        manager.notify(event.tag, 1, notification)
        return true
    }
}
