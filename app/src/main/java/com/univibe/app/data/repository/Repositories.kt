package com.univibe.app.data.repository

import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.Comment
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.FollowEntry
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.ProfileUpdate
import com.univibe.app.data.model.ReportItem
import kotlinx.coroutines.flow.Flow
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.model.Student

/**
 * Repository contracts. UI and ViewModels depend only on these interfaces so a real
 * backend (paginated, authenticated) can replace the mock implementations later.
 */
interface FeedRepository {
    suspend fun getCurrentUser(): Student
    suspend fun getPosts(filter: FeedFilter): List<Post>
    suspend fun getStories(): List<Student>
    suspend fun setLiked(postId: String, liked: Boolean)
    suspend fun setSaved(postId: String, saved: Boolean)
    /** Sends a report to moderators. */
    suspend fun reportPost(postId: String, reason: String)
    /** Server must validate length/content; returns the stored post. */
    suspend fun createPost(text: String, hasMedia: Boolean, imagePath: String? = null): Post
    suspend fun getSavedPosts(): List<Post>
}

interface DiscoverRepository {
    /** Only students who opted in to discovery should ever be returned. */
    suspend fun getCandidates(): List<Student>
    suspend fun recordDecision(studentId: String, liked: Boolean)
}

interface ChatRepository {
    suspend fun getChats(): List<Chat>
    suspend fun getChat(chatId: String): Chat?
    suspend fun setMuted(chatId: String, muted: Boolean)
    suspend fun archive(chatId: String)
}

interface EventRepository {
    suspend fun getEvents(): List<CampusEvent>
    /** Null when the id is unknown (ids from deep links are untrusted). */
    suspend fun getEvent(id: String): EventDetails?
    suspend fun setRsvp(eventId: String, state: RsvpState)
}

interface ProfileRepository {
    suspend fun getMyProfile(): MyProfile
    suspend fun updateProfile(update: ProfileUpdate)
    suspend fun addPhoto(path: String)
    suspend fun removePhoto(path: String)
    suspend fun getStudent(id: String): Student?
    suspend fun getFollowers(): List<FollowEntry>
    suspend fun getFollowing(): List<FollowEntry>
    suspend fun setFollowing(studentId: String, following: Boolean)
}

interface CommentRepository {
    suspend fun getComments(postId: String): List<Comment>
    suspend fun addComment(postId: String, text: String): Comment
    suspend fun setLiked(commentId: String, liked: Boolean)
    suspend fun deleteComment(commentId: String)
    suspend fun reportComment(commentId: String, reason: String)
}

interface MessageRepository {
    fun messages(chatId: String): Flow<List<Message>>
    fun typing(chatId: String): Flow<Boolean>
    suspend fun send(chatId: String, text: String, imagePath: String? = null)
    suspend fun deleteMessage(chatId: String, messageId: String)
    suspend fun markRead(chatId: String)
    /** Returns the id of the existing or newly created 1:1 chat. */
    suspend fun openChatWith(student: Student): String
}

interface CommunityRepository {
    suspend fun getCommunities(): List<Community>
    suspend fun getCommunity(id: String): Community?
    suspend fun setMembership(id: String, join: Boolean)
    suspend fun setMuted(id: String, muted: Boolean)
    suspend fun getReports(id: String): List<ReportItem>
    suspend fun resolveReport(communityId: String, reportId: String, removePost: Boolean)
}
