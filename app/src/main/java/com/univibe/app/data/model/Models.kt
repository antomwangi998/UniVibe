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
    /** Photo shown on the Discover card; null falls back to a gradient with initials. */
    val photoPath: String? = null,
    /** Short-lived link to the student's profile picture; null when they have not uploaded one (initials are shown). */
    val avatarUrl: String? = null,
) {
    /** "Computer Science · 2nd Year", or just the course when no year is set. */
    val courseLine: String
        get() = listOf(course, year).filter { it.isNotBlank() }.joinToString(" · ")
}

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
    /** Local file of an attached video (app storage). Mock-only: a real backend returns a streaming URL. */
    val videoPath: String? = null,
    val poll: Poll? = null,
    val edited: Boolean = false,
    /** Width / height of each attached photo, in [imagePaths] order. Null where the server doesn't know (older uploads). */
    val mediaRatios: List<Float?> = emptyList(),
    /** Width / height of the attached video, when known. */
    val videoRatio: Float? = null,
    /** Small feed copies of [imagePaths], same order and length, or empty when the server made none. */
    val thumbPaths: List<String> = emptyList(),
    /** Why Discover is showing this post, from a real ranking signal (see [DiscoverReason]); null when none applies. */
    val discoverReason: String? = null,
    /** Set when the post was published in a community; the author and the community stay distinct. */
    val community: PostCommunity? = null,
    val announcement: Boolean = false,
    /**
     * Set when the post announces an event. It is the event's CURRENT state, from the same server data as the Events tab and
     * the details screen, so a cancellation or RSVP shows up here too. The text of such a post is empty.
     */
    val event: CampusEvent? = null,
) {
    /** True when the post carries something besides text, so its text may be empty. */
    val hasAttachment: Boolean
        get() = imagePath != null || imagePaths.isNotEmpty() || videoPath != null || mediaAccent != null || poll != null
}

/** Limits shared by the composer and the mock repository. The server must enforce the same rules. */
object PostLimits {
    const val MAX_TEXT = 280
    const val MIN_POLL_OPTIONS = 2
    const val MAX_POLL_OPTIONS = 4
    const val MAX_POLL_OPTION_LENGTH = 40
    const val MAX_VIDEO_BYTES = 50L * 1024 * 1024

    /** Largest photo file the server accepts. The server also re-encodes photos, so what is stored is much smaller. */
    const val MAX_IMAGE_UPLOAD_BYTES = 8L * 1024 * 1024
}

data class PollOption(val text: String, val votes: Int = 0)

/** [myVote] is the index the signed-in student chose, or null if they have not voted. One vote per student. */
data class Poll(val options: List<PollOption>, val myVote: Int? = null) {
    val totalVotes: Int get() = options.sumOf { it.votes }
}

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
    /** The other student in a 1:1 chat (null for groups); lets the header open their profile. */
    val studentId: String? = null,
    /** What the last message was, taken from the server's message (never guessed from the preview text). */
    val lastKind: LastKind = LastKind.Text,
    val lastFromMe: Boolean = false,
    /** Sender's name, for group previews ("Jane: ..."). Null when the server did not say. */
    val lastSenderName: String? = null,
    val photoCount: Int = 0,
    /** True when the server flag "marked unread" is set (the chat has no real unread messages of its own). */
    val markedOnly: Boolean = false,
    /** For groups: up to six other members (names only), straight from the chat list. */
    val members: List<ChatMember> = emptyList(),
    val memberCount: Int = 0,
    /** How far our own last message got. None when the last message is not ours. */
    val lastDelivery: Delivery = Delivery.None,
    /** Profile picture of the other person in a direct chat. */
    val avatarUrl: String? = null,
    /** For groups: the uploaded group photo (a signed link), when there is one. */
    val groupPhotoUrl: String? = null,
) {
    val hasUnread: Boolean get() = unread > 0
}

data class ChatMember(val id: String, val name: String)

/** A recorded voice message: where to play it from and how long it is. */
data class VoiceNote(val source: String, val durationMs: Int)

enum class LastKind { None, Text, Photo, Voice, Deleted }

enum class Delivery { None, Sent, Delivered, Read }

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
    /** Real times (UTC millis). The display fields above are derived from them by the repository. */
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val hostName: String = "",
    /** Set when a community hosts the event. */
    val hostCommunityId: String? = null,
    val cancelled: Boolean = false,
    /** You created it, so you can edit, cancel or delete it. */
    val mine: Boolean = false,
    /** Signed link to the cover photo, when the organizer added one. Expires, so it is never stored. */
    val coverUrl: String? = null,
    /** You bookmarked it (saved on the server, so it follows you across devices). */
    val saved: Boolean = false,
    /** Why Discover recommends it ("From a community you joined"), from a real ranking signal; null elsewhere. */
    val reason: String? = null,
) {
    /** After this moment the event is deleted for everyone (see [EVENT_RETENTION_MILLIS]). */
    val expiresAtMillis: Long get() = endMillis + EVENT_RETENTION_MILLIS

    fun status(now: Long = System.currentTimeMillis()): EventStatus = when {
        cancelled -> EventStatus.Cancelled
        now >= endMillis -> EventStatus.Ended
        now >= startMillis -> EventStatus.Live
        else -> EventStatus.Upcoming
    }
}

enum class EventStatus { Upcoming, Live, Ended, Cancelled }

/** Ended events stay visible under Past for a week (photos, memories, deep links), then are deleted with their RSVPs. */
const val EVENT_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

/** One category the server offers. The list is data on the server, so new categories appear without an app update. */
data class EventCategoryInfo(val code: String, val label: String)

/** A named group of events on the Discover page. An event is in at most one section. */
data class EventSection(val key: String, val title: String, val events: List<CampusEvent>)

/** Filters the server applies when browsing events. Empty means no filter. */
data class EventDiscoverFilter(
    /** Server category codes. */
    val categories: Set<String> = emptySet(),
    val toMillis: Long? = null,
    val onlineOnly: Boolean = false,
    val registrationOpen: Boolean = false,
    val savedOnly: Boolean = false,
) {
    val isActive: Boolean get() = categories.isNotEmpty() || toMillis != null || onlineOnly || registrationOpen || savedOnly
}

data class EventDiscoverPage(val events: List<CampusEvent>, val nextCursor: String?)

val EventCategoryOptions = listOf("Workshop", "Party", "Sports", "Academic", "Social", "Career")

/** A campus venue with a position for the map. A real backend serves the venue directory. */
data class Venue(val name: String, val latitude: Double, val longitude: Double)

/** What the create/edit screen sends. Times are UTC millis; the server must validate everything again. */
data class NewEvent(
    val title: String,
    val description: String,
    val category: String,
    val startMillis: Long,
    val endMillis: Long,
    val venue: String,
    val latitude: Double?,
    val longitude: Double?,
    val capacity: Int?,
    val accent: Int,
    val hostCommunityId: String?,
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
    /** Photo for your Discover card. Only shown to others while you are discoverable. */
    val discoverPhotoPath: String? = null,
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
    /** Set on replies. Replies always hang under the top-level comment of their thread. */
    val parentId: String? = null,
    /** Handle of the person this reply answers, shown as a highlighted mention. */
    val replyToHandle: String? = null,
    /** True when the commenter wrote the post (computed by the server). */
    val isPostAuthor: Boolean = false,
)

enum class MessageStatus { Sending, Sent, Delivered, Seen }

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
    /** Every attached photo in order ([imagePath] is the first). Empty when there is at most the one. */
    val imagePaths: List<String> = emptyList(),
    /** Width / height of the first photo as the server knows it, so the bubble has the right shape before it loads. */
    val imageRatio: Float? = null,
    /** A voice message, when this is one. A local file path while it is still sending, a link afterwards. */
    val voice: VoiceNote? = null,
    val replyTo: ReplyRef? = null,
    /** How many people used each reaction. */
    val reactions: Map<Reaction, Int> = emptyMap(),
    val myReaction: Reaction? = null,
    val forwarded: Boolean = false,
    val edited: Boolean = false,
) {
    val photos: List<String> get() = imagePaths.ifEmpty { listOfNotNull(imagePath) }
}

/** Messages can be edited for 15 minutes after sending. The server enforces this too. */
const val EDIT_WINDOW_MILLIS = 15 * 60_000L

enum class Reaction { Heart, Like, Laugh, Fire, Sad }

/** A quote of the message being answered. [preview] is already shortened ("Photo" for image-only messages). */
data class ReplyRef(val messageId: String, val senderName: String, val preview: String)

enum class GroupRole { Member, Admin, Owner }

data class GroupMember(val student: Student, val role: GroupRole, val isMe: Boolean = false)

/** Everything the group info screen shows. Permission flags are enforced by the server; the UI only mirrors them. */
data class GroupInfo(
    val chatId: String,
    val name: String,
    val description: String,
    val photoPath: String?,
    val createdBy: String,
    val createdLabel: String,
    val members: List<GroupMember>,
    val onlyAdminsCanMessage: Boolean = false,
    val onlyAdminsCanEditInfo: Boolean = true,
    val onlyAdminsCanAddMembers: Boolean = true,
    val muted: Boolean = false,
) {
    val myRole: GroupRole get() = members.firstOrNull { it.isMe }?.role ?: GroupRole.Member
    val iAmAdmin: Boolean get() = myRole != GroupRole.Member
    val canMessage: Boolean get() = !onlyAdminsCanMessage || iAmAdmin
    val canEditInfo: Boolean get() = !onlyAdminsCanEditInfo || iAmAdmin
    val canAddMembers: Boolean get() = !onlyAdminsCanAddMembers || iAmAdmin
}

enum class CommunityRole { None, Member, Moderator, Owner }

/** Public communities can be joined at once; private ones need a moderator to approve each request. */
enum class CommunityPrivacy { Public, Private }

/** Labels shown to people. The server's category key is the lower-case label (see [communityCategoryKey]). */
val CommunityCategories = listOf("Academics", "Clubs", "Sports", "Arts", "Tech", "Career", "Social", "Wellbeing", "Other")
fun communityCategoryKey(label: String) = label.trim().lowercase()
fun communityCategoryLabel(key: String) = key.replaceFirstChar { it.uppercase() }

/** What the membership button should offer this viewer. Computed once by the server and shared by every surface. */
enum class MembershipState {
    Joined, Join, Request, Pending, Invited, Unavailable;

    companion object {
        fun parse(value: String?) = when (value) {
            "joined" -> Joined
            "join" -> Join
            "request" -> Request
            "pending" -> Pending
            "invited" -> Invited
            else -> Unavailable
        }
    }
}

/** The community a post was published in, as shown in the post header. */
data class PostCommunity(
    val id: String,
    val name: String,
    val isPrivate: Boolean,
    val memberCount: Int,
    val avatarUrl: String?,
    val membership: MembershipState,
)
const val MAX_COMMUNITIES_OWNED = 5

data class CommunityMember(val student: Student, val role: CommunityRole, val isMe: Boolean = false)

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
    val privacy: CommunityPrivacy = CommunityPrivacy.Public,
    /** You asked to join a private community and are waiting for approval. */
    val joinRequested: Boolean = false,
    val createdLabel: String = "",
    /** The server's membership state; null in the offline demo, where it is derived from [role] and [privacy]. */
    val membership: MembershipState? = null,
    val avatarUrl: String? = null,
    val coverUrl: String? = null,
    val canPost: Boolean = false,
    val archived: Boolean = false,
) {
    val effectiveMembership: MembershipState
        get() = membership ?: when {
            isMember -> MembershipState.Joined
            joinRequested -> MembershipState.Pending
            privacy == CommunityPrivacy.Private -> MembershipState.Request
            else -> MembershipState.Join
        }
    val isMember: Boolean get() = role != CommunityRole.None
    /** Owners and moderators. */
    val isStaff: Boolean get() = role == CommunityRole.Owner || role == CommunityRole.Moderator
}

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
