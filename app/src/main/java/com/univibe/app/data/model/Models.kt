package com.univibe.app.data.model

data class Student(
    val id: String,
    val name: String,
    val handle: String,
    val age: Int,
    val course: String,
    val year: String,
    val university: String,
    val interests: List<String>,
    val verified: Boolean,
    val online: Boolean = false,
    val accent: Int = 0,
    val isNew: Boolean = false,
    /** Interests shared with the signed-in student (computed by the data layer). */
    val shared: List<String> = emptyList(),
    val sameCourse: Boolean = false,
)

enum class FeedFilter { Campus, Following }

/** Who may see a post. The server enforces this; the client only offers the choices. */
sealed interface PostAudience {
    data object Campus : PostAudience
    data object Followers : PostAudience
    data class Community(val id: String, val name: String) : PostAudience
}

/** What the create-post screen sends. Photos are already private, re-encoded local files. */
data class NewPost(val text: String, val imagePaths: List<String>, val audience: PostAudience)

data class Post(
    val id: String,
    val author: Student,
    val timeAgo: String,
    val text: String,
    val likeCount: Int,
    val commentCount: Int,
    val shareCount: Int = 0,
    val likedByMe: Boolean = false,
    val savedByMe: Boolean = false,
    val mediaAccent: Int? = null,
    val mediaPortrait: Boolean = false,
    /** Local file of an attached photo (app storage). */
    val imagePath: String? = null,
    val fromFollowing: Boolean = false,
    /** All attached photos in order; [imagePath] is the first one, kept for older screens. */
    val imagePaths: List<String> = emptyList(),
    val audience: PostAudience = PostAudience.Campus,
)

data class Chat(
    val id: String,
    val title: String,
    val lastMessage: String,
    val timeLabel: String,
    val unread: Int = 0,
    val isGroup: Boolean = false,
    val online: Boolean = false,
    val pinned: Boolean = false,
    val muted: Boolean = false,
)

enum class RsvpState { None, Interested, Going }

data class CampusEvent(
    val id: String,
    val title: String,
    val month: String,
    val day: String,
    val timeLabel: String,
    val location: String,
    val going: Int,
    val interested: Int,
    val category: String,
    val accent: Int,
    val rsvp: RsvpState = RsvpState.None,
    val isPast: Boolean = false,
)

enum class EventsTab { Upcoming, MyEvents, Past }

/** Everything the full student profile shows. Counts and communities come from the server. */
data class StudentProfile(
    val student: Student,
    val bio: String,
    val posts: Int,
    val followers: Int,
    val following: Int,
    val communityIds: List<String> = emptyList(),
)

enum class NotificationType { Like, Comment, Follow, Connection, Event, Community, Message, System }

/** Where tapping a notification goes. Ids are untrusted; destination screens handle "not found". */
sealed interface NotificationTarget {
    data class Post(val id: String) : NotificationTarget
    data class Student(val id: String) : NotificationTarget
    data class Event(val id: String) : NotificationTarget
    data class Community(val id: String) : NotificationTarget
    data class Chat(val id: String) : NotificationTarget
    data object None : NotificationTarget
}

/** [title] is the actor or sender (bold), [body] the rest of the sentence. [actor] is null for system items. */
data class AppNotification(
    val id: String,
    val type: NotificationType,
    val title: String,
    val body: String,
    val timeAgo: String,
    val read: Boolean,
    val target: NotificationTarget = NotificationTarget.None,
    val actor: Student? = null,
)

data class AgendaItem(val time: String, val title: String)

/** Everything the event details screen shows. [capacity] == null means unlimited places. */
data class EventDetails(
    val event: CampusEvent,
    val description: String,
    val host: String,
    val hostAccent: Int,
    val agenda: List<AgendaItem> = emptyList(),
    val capacity: Int? = null,
    /** Venue position for the map; null hides the map (e.g. online events). */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    /** Full for everyone except people who already hold a place. */
    val isFull: Boolean
        get() = capacity != null && event.going >= capacity && event.rsvp != RsvpState.Going
    val spotsLeft: Int?
        get() = capacity?.let { (it - event.going).coerceAtLeast(0) }
}

data class GroupSummary(val name: String, val members: Int, val accent: Int)

data class MyProfile(
    val student: Student,
    val bio: String,
    val posts: Int,
    val followers: Int,
    val following: Int,
    val savedPosts: Int,
    val groups: List<GroupSummary>,
    val avatarPath: String? = null,
    val coverPath: String? = null,
    val photos: List<String> = emptyList(),
)

data class Comment(
    val id: String,
    val postId: String,
    val author: Student,
    val text: String,
    val timeAgo: String,
    val likeCount: Int,
    val likedByMe: Boolean = false,
    val isMine: Boolean = false,
)

enum class MessageStatus { Sending, Sent, Seen }

data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timeMillis: Long,
    val isMine: Boolean,
    val status: MessageStatus = MessageStatus.Sent,
    val imagePath: String? = null,
)

enum class CommunityRole { None, Member, Moderator }

data class Community(
    val id: String,
    val name: String,
    val description: String,
    val members: Int,
    val category: String,
    val accent: Int,
    val role: CommunityRole = CommunityRole.None,
    val muted: Boolean = false,
    val rules: List<String> = emptyList(),
    val moderators: List<String> = emptyList(),
)

data class ReportItem(val id: String, val excerpt: String, val authorName: String, val reason: String)

data class FollowEntry(val student: Student, val isFollowing: Boolean)

data class ProfileUpdate(
    val name: String,
    val bio: String,
    val course: String,
    val year: String,
    val interests: List<String>,
    val avatarPath: String?,
    val coverPath: String?,
)
