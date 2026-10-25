package com.univibe.app.data.repository

import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.GroupInfo
import com.univibe.app.data.model.GroupRole
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.Reaction
import com.univibe.app.data.model.ReplyRef
import com.univibe.app.data.model.Student
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.IOException

private fun offline(): Nothing = throw IOException("Messaging needs a connection to the UniVibe server.")

/**
 * Used only when the app has no server to talk to (the offline preview build). There is no invented conversation data:
 * every call says plainly that messaging is unavailable, and the screens show their normal error state.
 */
object UnavailableChatRepository : ChatRepository {
    override suspend fun getChats(): List<Chat> = offline()
    override suspend fun getChat(chatId: String): Chat? = offline()
    override suspend fun setMuted(chatId: String, muted: Boolean) = offline()
    override suspend fun archive(chatId: String) = offline()
}

object UnavailableMessageRepository : MessageRepository {
    override fun messages(chatId: String): Flow<List<Message>> = flowOf(emptyList())
    override fun typing(chatId: String): Flow<Boolean> = flowOf(false)
    override suspend fun send(chatId: String, text: String, imagePath: String?, replyTo: ReplyRef?) = offline()
    override suspend fun deleteMessage(chatId: String, messageId: String) = offline()
    override suspend fun react(chatId: String, messageId: String, reaction: Reaction?) = offline()
    override suspend fun clearChat(chatId: String) = offline()
    override suspend fun editMessage(chatId: String, messageId: String, text: String) = offline()
    override suspend fun forward(fromChatId: String, messageId: String, toChatIds: List<String>) = offline()
    override fun pinnedMessageId(chatId: String): Flow<String?> = flowOf(null)
    override suspend fun pin(chatId: String, messageId: String?) = offline()
    override suspend fun markRead(chatId: String) = offline()
    override suspend fun openChatWith(student: Student): String = offline()
}

object UnavailableGroupRepository : GroupRepository {
    override suspend fun getGroup(chatId: String): GroupInfo? = offline()
    override suspend fun updateInfo(chatId: String, name: String, description: String) = offline()
    override suspend fun setPhoto(chatId: String, path: String?) = offline()
    override suspend fun getAddable(chatId: String): List<Student> = offline()
    override suspend fun addMembers(chatId: String, studentIds: List<String>) = offline()
    override suspend fun removeMember(chatId: String, studentId: String) = offline()
    override suspend fun setRole(chatId: String, studentId: String, role: GroupRole) = offline()
    override suspend fun setPermissions(chatId: String, onlyAdminsCanMessage: Boolean, onlyAdminsCanEditInfo: Boolean, onlyAdminsCanAddMembers: Boolean) = offline()
    override suspend fun leave(chatId: String) = offline()
    override suspend fun report(chatId: String, reason: String) = offline()
    override suspend fun getContacts(): List<Student> = offline()
    override suspend fun createGroup(name: String, description: String, photoPath: String?, memberIds: List<String>): String = offline()
}
