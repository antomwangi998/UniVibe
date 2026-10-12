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
