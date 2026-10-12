package com.univibe.app.data.repository

import com.univibe.app.data.model.CampusEvent
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
    suspend fun createPost(text: String, hasMedia: Boolean): Post
}

interface DiscoverRepository {
    /** Only students who opted in to discovery should ever be returned. */
    suspend fun getCandidates(): List<Student>
    suspend fun recordDecision(studentId: String, liked: Boolean)
}

interface ChatRepository {
    suspend fun getChats(): List<Chat>
    suspend fun setMuted(chatId: String, muted: Boolean)
    suspend fun archive(chatId: String)
}

interface EventRepository {
    suspend fun getEvents(): List<CampusEvent>
    suspend fun setRsvp(eventId: String, state: RsvpState)
}

interface ProfileRepository {
    suspend fun getMyProfile(): MyProfile
}
