package com.univibe.app.data.repository

import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.CommunityMember
import com.univibe.app.data.model.CommunityPrivacy
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.NewEvent
import com.univibe.app.data.model.Venue
import com.univibe.app.data.model.GroupInfo
import com.univibe.app.data.model.GroupRole
import com.univibe.app.data.model.Reaction
import com.univibe.app.data.model.ReplyRef
import com.univibe.app.data.model.Comment
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.FollowEntry
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.ProfileUpdate
import com.univibe.app.data.model.ReportItem
import kotlinx.coroutines.flow.Flow
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.AppNotification
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.model.Student
import com.univibe.app.data.model.StudentProfile

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
    suspend fun createPost(
        text: String,
        hasMedia: Boolean,
        imagePath: String? = null,
        videoPath: String? = null,
        pollOptions: List<String> = emptyList(),
    ): Post
    /**
     * Creates a post with attached photos and an audience. The server must validate text length, photo count/size/type
     * and that the author may post to the chosen community; client checks are convenience only.
     */
    suspend fun publishPost(post: NewPost): Post
    suspend fun getSavedPosts(): List<Post>
    /** One vote per student per poll; the server must reject a second vote and out-of-range options. Returns the updated post. */
    suspend fun vote(postId: String, optionIndex: Int): Post
    /** Author only. The server re-validates the text and marks the post as edited. */
    suspend fun editPost(postId: String, text: String): Post
    /** Author only. The server also removes the post's media and comments. */
    suspend fun deletePost(postId: String)
    /** Counts one share. Only campus-wide posts may be shared outside the app. */
    suspend fun recordShare(postId: String)
}

interface NotificationRepository {
    /** Live unread count for badges. A real backend would push this (or poll cheaply). */
    val unreadCount: kotlinx.coroutines.flow.StateFlow<Int>
    suspend fun getNotifications(): List<AppNotification>
    suspend fun markRead(id: String)
    suspend fun markAllRead()
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
    /** Returns the new event's id. Hosting for a community needs moderator or owner rights there. */
    suspend fun createEvent(draft: NewEvent): String
    /** Host only, and only before the event ends. The host and community can't change. */
    suspend fun updateEvent(id: String, draft: NewEvent)
    /** Host only. Keeps the event visible, marked cancelled, until it expires. The server must notify attendees. */
    suspend fun cancelEvent(id: String)
    /** Host only, and only while nobody else has RSVPed (otherwise cancel it so attendees are told). */
    suspend fun deleteEvent(id: String)
    suspend fun getVenues(): List<Venue>
}

interface ProfileRepository {
    suspend fun getMyProfile(): MyProfile
    suspend fun updateProfile(update: ProfileUpdate)
    suspend fun addPhoto(path: String)
    suspend fun removePhoto(path: String)
    /** Sets (or clears, with null) the photo on your Discover card. The server must moderate it before others see it. */
    suspend fun setDiscoverPhoto(path: String?)
    suspend fun getStudent(id: String): Student?
    /** Null when the id is unknown (ids from deep links are untrusted). */
    suspend fun getStudentProfile(id: String): StudentProfile?
    suspend fun getFollowers(): List<FollowEntry>
    suspend fun getFollowing(): List<FollowEntry>
    suspend fun setFollowing(studentId: String, following: Boolean)
}

interface CommentRepository {
    suspend fun getComments(postId: String): List<Comment>
    /** [replyToId] makes it a reply; the server attaches it to the thread's top-level comment and validates the id. */
    suspend fun addComment(postId: String, text: String, replyToId: String? = null): Comment
    suspend fun setLiked(commentId: String, liked: Boolean)
    suspend fun deleteComment(commentId: String)
    suspend fun reportComment(commentId: String, reason: String)
}

interface MessageRepository {
    fun messages(chatId: String): Flow<List<Message>>
    fun typing(chatId: String): Flow<Boolean>
    /** Fails if you may not post here (e.g. an admins-only group). The server must check this too. */
    suspend fun send(chatId: String, text: String, imagePath: String? = null, replyTo: ReplyRef? = null)
    suspend fun deleteMessage(chatId: String, messageId: String)
    /** One reaction per person per message; null removes yours. */
    suspend fun react(chatId: String, messageId: String, reaction: Reaction?)
    /** Clears the conversation for you only. */
    suspend fun clearChat(chatId: String)
    /** Own text messages only, within [com.univibe.app.data.model.EDIT_WINDOW_MILLIS]. Marks the message as edited. */
    suspend fun editMessage(chatId: String, messageId: String, text: String)
    /** Sends a copy (marked forwarded) to 1 to 5 chats. All-or-nothing: fails if you can't post in one of them. */
    suspend fun forward(fromChatId: String, messageId: String, toChatIds: List<String>)
    fun pinnedMessageId(chatId: String): Flow<String?>
    /** In groups only admins may pin. Pass null to unpin. */
    suspend fun pin(chatId: String, messageId: String?)
    suspend fun markRead(chatId: String)
    /** Returns the id of the existing or newly created 1:1 chat. */
    suspend fun openChatWith(student: Student): String
}

interface GroupRepository {
    /** Null when the id is unknown or is not a group you belong to. */
    suspend fun getGroup(chatId: String): GroupInfo?
    suspend fun updateInfo(chatId: String, name: String, description: String)
    suspend fun setPhoto(chatId: String, path: String?)
    /** Students you could add (not already members). */
    suspend fun getAddable(chatId: String): List<com.univibe.app.data.model.Student>
    suspend fun addMembers(chatId: String, studentIds: List<String>)
    suspend fun removeMember(chatId: String, studentId: String)
    suspend fun setRole(chatId: String, studentId: String, role: GroupRole)
    suspend fun setPermissions(chatId: String, onlyAdminsCanMessage: Boolean, onlyAdminsCanEditInfo: Boolean, onlyAdminsCanAddMembers: Boolean)
    /** If you are the owner, ownership passes to someone else first. */
    suspend fun leave(chatId: String)
    suspend fun report(chatId: String, reason: String)
    /** People you can add when creating a group. */
    suspend fun getContacts(): List<com.univibe.app.data.model.Student>
    /** Creates the group with you as owner and returns its chat id. */
    suspend fun createGroup(name: String, description: String, photoPath: String?, memberIds: List<String>): String
}

interface CommunityRepository {
    suspend fun getCommunities(): List<Community>
    suspend fun getCommunity(id: String): Community?
    suspend fun setMembership(id: String, join: Boolean)
    suspend fun setMuted(id: String, muted: Boolean)
    suspend fun getReports(id: String): List<ReportItem>
    suspend fun resolveReport(communityId: String, reportId: String, removePost: Boolean)

    /**
     * Creates a community with you as owner and returns its id. The server must validate everything again,
     * keep names unique, and rate limit creation.
     */
    suspend fun createCommunity(
        name: String, description: String, category: String, accent: Int, rules: List<String>, privacy: CommunityPrivacy,
    ): String
    /** Owner only. Switching a private community to public approves everyone waiting. */
    suspend fun updateCommunity(id: String, description: String, category: String, accent: Int, rules: List<String>, privacy: CommunityPrivacy)
    /** Owner only. Removes the community, its posts and the events it hosts. */
    suspend fun deleteCommunity(id: String)
    /** Members of private communities are only visible to members. */
    suspend fun getMembers(id: String): List<CommunityMember>
    /** Owner only; [role] is Member or Moderator. */
    suspend fun setMemberRole(id: String, studentId: String, role: CommunityRole)
    /** Moderators remove members; only the owner removes moderators. */
    suspend fun removeMember(id: String, studentId: String)
    suspend fun getJoinRequests(id: String): List<com.univibe.app.data.model.Student>
    suspend fun resolveJoinRequest(id: String, studentId: String, approve: Boolean)
    /** Posts addressed to this community. Private communities only show them to members. */
    suspend fun getPosts(id: String): List<com.univibe.app.data.model.Post>
}
