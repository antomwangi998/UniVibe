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
)
