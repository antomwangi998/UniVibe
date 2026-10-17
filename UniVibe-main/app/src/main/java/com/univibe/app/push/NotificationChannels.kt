package com.univibe.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.univibe.app.R

object NotificationChannels {
    /** Safe to call often: creating a channel that already exists changes nothing (people's own settings are kept). */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        fun channel(c: PushChannel, name: Int, description: Int, importance: Int) =
            NotificationChannel(c.id, context.getString(name), importance).apply { this.description = context.getString(description) }
        manager.createNotificationChannels(
            listOf(
                channel(PushChannel.DirectMessages, R.string.channel_direct_messages, R.string.channel_direct_messages_desc, NotificationManager.IMPORTANCE_HIGH),
                channel(PushChannel.GroupMessages, R.string.channel_group_messages, R.string.channel_group_messages_desc, NotificationManager.IMPORTANCE_HIGH),
                channel(PushChannel.Social, R.string.channel_social, R.string.channel_social_desc, NotificationManager.IMPORTANCE_DEFAULT),
                channel(PushChannel.Campus, R.string.channel_campus, R.string.channel_campus_desc, NotificationManager.IMPORTANCE_DEFAULT),
                channel(PushChannel.Security, R.string.channel_security, R.string.channel_security_desc, NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }
}
