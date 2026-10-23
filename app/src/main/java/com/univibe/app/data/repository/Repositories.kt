package com.univibe.app.data.repository

import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventActivity
import com.univibe.app.data.model.AttendeePage
import com.univibe.app.data.model.EventAttendee
import com.univibe.app.data.model.EventReportItem
import com.univibe.app.data.model.ModeratorEvent
import com.univibe.app.data.model.EventLink
import com.univibe.app.data.model.EventUpdate
import com.univibe.app.data.model.ShareTarget
import com.univibe.app.data.model.EventCategoryInfo
import com.univibe.app.data.model.EventNotificationPrefs
import com.univibe.app.data.model.EventNotificationPrefsChange
import com.univibe.app.data.model.EventDiscoverFilter
import com.univibe.app.data.model.EventDiscoverPage
import com.univibe.app.data.model.EventSection
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
import com.univibe.app.data.model.PostLimits
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.model.Student
import com.univibe.app.data.model.StudentProfile

/**
 * Repository contracts. UI and ViewModels depend only on these interfaces so a real
 * backend (paginated, authenticated) can replace the mock implementations later.
 */
interface FeedRepository {
    /** False while the server can't store photos and videos; screens then hide those buttons. */
    val supportsMedia: Boolean get() = true
    suspend fun getCurrentUser(): Student
    suspend fun getPosts(filter: FeedFilter): List<Post>

    /**
     * One page of the feed, newest first. [beforeId] is the last post already shown (null for the first page).
     * A repository without paging returns everything as the first page and nothing after it.
     */
    suspend fun getPostsPage(filter: FeedFilter, beforeId: String?, limit: Int): List<Post> =
        if (beforeId == null) getPosts(filter) else emptyList()

    /** One post by id, or null when it is gone or this student may not see it. */
    suspend fun getPost(id: String): Post? = getPosts(FeedFilter.Campus).firstOrNull { it.id == id }

    /** The newest posts by one student that this student may see (a profile's recent posts). */
    suspend fun getPostsBy(authorId: String, limit: Int): List<Post> =
        getPosts(FeedFilter.Campus).filter { it.author.id == authorId }.take(limit)

    /** One page of saved posts, most recently saved first; [beforeId] is the last one already shown. */
    suspend fun getSavedPostsPage(beforeId: String?, limit: Int): List<Post> =
        if (beforeId == null) getSavedPosts() else emptyList()

    suspend fun getStories(): List<Student>
    suspend fun setLiked(postId: String, liked: Boolean)
    suspend fun setSaved(postId: String, saved: Boolean)
    /** Reposts (or un-reposts) a post. One repost per student; repeating a request changes nothing. */
    suspend fun setReposted(postId: String, reposted: Boolean)
    /** Tells the server which posts this student has looked at. The server counts each student once per post. */
    suspend fun recordViews(postIds: List<String>)
    /** Sends a report to moderators. */
    suspend fun reportPost(postId: String, reason: String)
    /** Server must validate length/content; returns the stored post. */
    suspend fun createPost(
        text: String,
        hasMedia: Boolean,
        imagePath: String? = null,
        videoPath: String? = null,
        pollOptions: List<String> = emptyList(),
        /** How long the poll stays open, in hours (1 to 72). Ignored without poll options. */
        pollHours: Int = PostLimits.DEFAULT_POLL_HOURS,
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
    /** Asks the server for the current unread count (the badge). The mock keeps its own count, so this does nothing there. */
    suspend fun refreshUnread() {}
    /** Fires when the server says notifications changed (a new one arrived, or one was read elsewhere) or the link came back. */
    fun changes(): Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
}

interface DiscoverRepository {
    /** Only students who opted in to discovery should ever be returned. */
    suspend fun getCandidates(): List<Student>
    suspend fun recordDecision(studentId: String, liked: Boolean)
}

/** A person the app may see went online or offline. */
data class PresenceChange(val userId: String, val online: Boolean)

interface ChatRepository {
    suspend fun getChats(): List<Chat>
    suspend fun getChat(chatId: String): Chat?
    suspend fun setMuted(chatId: String, muted: Boolean)
    suspend fun archive(chatId: String)
    /** Archived conversations (the main list never includes them). */
    suspend fun getArchivedChats(): List<Chat> = throw UnsupportedOperationException("Archived chats aren't available.")
    suspend fun unarchive(chatId: String): Unit = throw UnsupportedOperationException("Unarchiving isn't available.")
    /** Per-person pin of the conversation in the list. Throws where the implementation cannot do it (never pretends). */
    suspend fun setPinned(chatId: String, pinned: Boolean): Unit = throw UnsupportedOperationException("Pinning isn't available.")
    /** Sets the server's "marked unread" flag. It does not touch read receipts; the next read clears it. */
    suspend fun markUnread(chatId: String): Unit = throw UnsupportedOperationException("Marking unread isn't available.")
    /** Same effect as opening the chat: clears the flag and moves the read cursor to the latest message. */
    suspend fun markRead(chatId: String): Unit = throw UnsupportedOperationException("Marking read isn't available.")
    /** Who is typing right now, per conversation (other people only). Entries expire by themselves. */
    fun typingByChat(): Flow<Map<String, Set<String>>> = kotlinx.coroutines.flow.emptyFlow()
    /** Whether the live connection is up. Implementations without one report true (nothing to be offline from). */
    fun connected(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)
    /** Someone went online or offline, only for people who share their activity with this student. */
    fun presence(): Flow<PresenceChange> = kotlinx.coroutines.flow.emptyFlow()
    /** Fires when the server says something changed in the chat list (new message, read elsewhere, reconnect). */
    fun changes(): Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
}

interface EventRepository {
    /** False when the server only knows "going" (no "interested"); screens then hide the Interested option. */
    val supportsInterested: Boolean get() = true
    /** False until the server stores bookmarks; screens then hide the save action instead of faking it. */
    val supportsSaved: Boolean get() = false
    suspend fun setSaved(eventId: String, saved: Boolean) { throw UnsupportedOperationException("Saving events isn't available yet.") }
    val supportsEventNotificationPrefs: Boolean get() = false
    suspend fun eventNotificationPrefs(): EventNotificationPrefs = EventNotificationPrefs()
    suspend fun saveEventNotificationPrefs(change: EventNotificationPrefsChange): EventNotificationPrefs { throw UnsupportedOperationException("Not available yet.") }
    /** True when the server ranks, filters and sections events. Otherwise Discover filters the plain list on the device. */
    val supportsDiscover: Boolean get() = false
    suspend fun eventCategories(): List<EventCategoryInfo> = emptyList()
    suspend fun discoverSections(): List<EventSection> = emptyList()
    suspend fun discoverEvents(filter: EventDiscoverFilter, cursor: String? = null): EventDiscoverPage = EventDiscoverPage(emptyList(), null)
    /** Hides an event from the person's recommendations only. It stays reachable everywhere else. */
    suspend fun setNotInterested(eventId: String, hidden: Boolean) { throw UnsupportedOperationException("Not available yet.") }
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

    /** False where the server has no organizer updates, activity log, links or sharing; screens then hide those actions. */
    val supportsOrganizerTools: Boolean get() = false
    suspend fun eventUpdates(eventId: String): List<EventUpdate> = emptyList()
    /** Organizer only. Tells everyone going or interested; the server limits it to a few a day. */
    suspend fun postEventUpdate(eventId: String, body: String) { throw UnsupportedOperationException("Updates aren't available yet.") }
    /** Organizer only: who changed what, and when. */
    suspend fun eventActivity(eventId: String): List<EventActivity> = emptyList()
    suspend fun eventLink(eventId: String): EventLink? = null
    /** Organizer only: announces a draft. The server re-checks the time and limits. */
    suspend fun publishEvent(id: String) { throw UnsupportedOperationException("Not available yet.") }
    /** The organizer's own unpublished events. */
    suspend fun myDrafts(): List<CampusEvent> = emptyList()
    /** Who is going. The organizer always sees it; others only when the organizer allows it and they are going. */
    suspend fun attendees(eventId: String, cursor: String? = null): AttendeePage = AttendeePage(emptyList(), null)

    // ---- Moderator tools. Everyone else gets "no" from [isModerator] and never sees them.
    suspend fun isModerator(): Boolean = false
    suspend fun eventReports(): List<EventReportItem> = emptyList()
    suspend fun moderatorEvent(id: String): ModeratorEvent? = null
    /** Hides an event from everyone but its organizer, or brings it back. [userMessage] is shown to the organizer in Account status. */
    suspend fun setEventHidden(id: String, hide: Boolean, note: String, userMessage: String) { throw UnsupportedOperationException("Not available.") }
    /** Organizer only: closes an event that has started. */
    suspend fun completeEvent(id: String) { throw UnsupportedOperationException("Not available yet.") }
    /** Turns reminders on or off for one event you are going to. */
    suspend fun setEventReminders(id: String, enabled: Boolean) { throw UnsupportedOperationException("Not available yet.") }
    /** Upcoming events a community hosts, as this person may see them. */
    suspend fun communityEvents(communityId: String): List<CampusEvent> = emptyList()
    /** Shares the event itself into a chat or community. The server refuses community-only events outside their community. */
    suspend fun shareEvent(eventId: String, target: ShareTarget, targetId: String, text: String) { throw UnsupportedOperationException("Sharing isn't available yet.") }
    /** [targetType] is "event" or "user" (to report an organizer). */
    suspend fun report(targetType: String, targetId: String, reason: String) { throw UnsupportedOperationException("Reporting isn't available yet.") }
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
    /** Another student's followers / following. Empty when offline or not available. */
    suspend fun getFollowersOf(studentId: String): List<FollowEntry> = emptyList()
    suspend fun getFollowingOf(studentId: String): List<FollowEntry> = emptyList()
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
    /** Sends up to [MAX_MESSAGE_PHOTOS] photos in one message, with an optional caption. */
    suspend fun sendPhotos(chatId: String, text: String, imagePaths: List<String>, replyTo: ReplyRef? = null) {
        if (imagePaths.size > 1) throw UnsupportedOperationException("Sending several photos isn't available.")
        send(chatId, text, imagePaths.firstOrNull(), replyTo)
    }
    /** Sends one document (PDF, Word, ...) with an optional caption. */
    suspend fun sendFile(chatId: String, text: String, file: com.univibe.app.data.media.PickedFile, replyTo: ReplyRef? = null): Unit =
        throw UnsupportedOperationException("Sharing files isn't available.")
    /** Sends a recorded voice message (a local .m4a file, at most five minutes). Throws where voice is not available. */
    suspend fun sendVoice(chatId: String, audioPath: String, durationMs: Int, replyTo: ReplyRef? = null, waveform: List<Int> = emptyList()): Unit =
        throw UnsupportedOperationException("Voice messages aren't available.")
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
    /** True while earlier messages exist on the server that are not loaded yet. */
    fun hasOlder(chatId: String): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    /** Loads the next page of earlier messages. */
    suspend fun loadOlder(chatId: String) {}
    /** Tells the other people in the chat that this person started or stopped typing. */
    suspend fun typingChanged(chatId: String, typing: Boolean) {}
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

/** A community Discover suggests, with the server's real reason ("interests", "active") or null. Never "trending". */
data class SuggestedCommunity(val community: Community, val reason: String?)

interface CommunityRepository {
    suspend fun getCommunities(): List<Community>
    /** Needs the server's ranking; the offline demo has none and returns nothing rather than inventing it. */
    suspend fun getSuggested(): List<SuggestedCommunity> = emptyList()
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
        /** Stable per form, so a retried submit cannot create a duplicate community. */
        clientKey: String? = null,
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

/** The server accepts at most this many photos in one message. */
const val MAX_MESSAGE_PHOTOS = 4
