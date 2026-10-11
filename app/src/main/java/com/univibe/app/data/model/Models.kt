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
)

enum class FeedFilter { Campus, Following }

data class Post(
    val id: String,
    val author: Student,
    val timeAgo: String,
    val text: String,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean = false,
    val mediaAccent: Int? = null,
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
)

data class CampusEvent(
    val id: String,
    val title: String,
    val month: String,
    val day: String,
    val timeLabel: String,
    val location: String,
    val going: Int,
    val category: String,
    val accent: Int,
    val rsvped: Boolean = false,
)

enum class EventsTab { Upcoming, MyEvents }

data class MyProfile(
    val student: Student,
    val bio: String,
    val posts: Int,
    val followers: Int,
    val following: Int,
)
