package com.univibe.app.data.repository

import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.GroupSummary
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.model.Student
import kotlinx.coroutines.delay

/*
 * MOCK DATA ONLY. Nothing here talks to a server. All people, posts and events are
 * invented sample content so every screen can be developed without a backend.
 */
object MockData {
    private const val UNI = "Lakeside University"

    val aisha = Student("s1", "Aisha Njoki", "@aishanjoki", 21, "Computer Science", "2nd Year", UNI,
        listOf("Music", "Travel", "Reading", "Movies"), verified = true, online = true, accent = 0)
    val brian = Student("s2", "Brian Otieno", "@brian.o", 21, "Computer Science", "3rd Year", UNI,
        listOf("Music", "Football", "Travel", "Tech"), verified = true, online = true, accent = 1)
    val grace = Student("s3", "Grace Wanjiku", "@gracew", 20, "Business", "2nd Year", UNI,
        listOf("Entrepreneurship", "Photography", "Hiking", "Travel"), verified = true, accent = 2)
    val kevin = Student("s4", "Kevin Mutua", "@kevin.mutua", 22, "Mechanical Eng.", "4th Year", UNI,
        listOf("Robotics", "Football", "Gaming"), verified = false, online = true, accent = 3)
    val amina = Student("s5", "Amina Hassan", "@amina.h", 19, "Law", "1st Year", UNI,
        listOf("Debate", "Poetry", "Movies", "Campus Events"), verified = true, online = true, accent = 4, isNew = true)
    val tech = Student("s6", "Tech Club", "@techclub", 0, "Student community", "", UNI,
        listOf("Technology"), verified = true, accent = 5)

    val posts = listOf(
        Post("p1", tech, "2h", "Great session today on AI & innovation! The future is exciting.", 128, 26, 12, mediaAccent = 1, fromFollowing = true),
        Post("p2", grace, "4h", "Sunset at the lake is always a vibe.", 342, 41, 5, mediaAccent = 2, mediaPortrait = true),
        Post("p3", brian, "6h", "Anyone up for a 5-a-side game on Saturday morning? We need two more players.", 54, 18, 2, fromFollowing = true),
        Post("p4", amina, "1d", "Debate club trials open next week. No experience needed, just bring opinions.", 89, 12, 7),
        Post("p5", kevin, "1d", "Our robotics team just placed second at the regional showcase. Proud of everyone.", 210, 33, 9, mediaAccent = 4),
    )

    val chats = listOf(
        Chat("c1", "Grace Wanjiku", "See you at the library?", "9:41 AM", unread = 2, online = true, pinned = true),
        Chat("c2", "Study Buddies", "Kevin: Don't forget the...", "8:30 AM", unread = 5, isGroup = true, pinned = true),
        Chat("c3", "Brian Otieno", "Thanks!", "Yesterday"),
        Chat("c4", "Design Club", "New event this Friday", "Mon", isGroup = true),
        Chat("c5", "Kevin Mutua", "Let's catch up later", "Sun", online = true),
        Chat("c6", "Events Team", "Meeting at 4pm", "Sun", isGroup = true),
        Chat("c7", "Tech Creators", "Alex: Check this out", "Sat", isGroup = true),
    )

    val events = listOf(
        CampusEvent("e1", "Campus Night", "MAY", "18", "Fri, 18 May · 7:00 PM", "Student Center", 126, 48, "Party", 0),
        CampusEvent("e2", "Inter-Faculty Football", "MAY", "24", "Sat, 24 May · 2:00 PM", "Main Field", 32, 20, "Sports", 5),
        CampusEvent("e3", "UI/UX Workshop", "MAY", "30", "Thu, 30 May · 10:00 AM", "Tech Lab 3", 18, 31, "Workshop", 1),
        CampusEvent("e4", "Entrepreneurship Talk", "JUN", "05", "Thu, 5 Jun · 3:00 PM", "Auditorium", 41, 25, "Academic", 2),
        CampusEvent("e5", "Freshers Welcome", "APR", "12", "Fri, 12 Apr · 6:00 PM", "Main Hall", 210, 64, "Party", 3, isPast = true),
        CampusEvent("e6", "Career Fair", "APR", "02", "Tue, 2 Apr · 9:00 AM", "Sports Complex", 150, 80, "Academic", 4, isPast = true),
    )

    val me = MyProfile(
        student = aisha,
        bio = "Lover of good music, weekend adventures and great conversations.",
        posts = 128, followers = 1200, following = 980, savedPosts = 12,
        groups = listOf(
            GroupSummary("Tech Club", 842, 5),
            GroupSummary("Photography Club", 612, 2),
            GroupSummary("Debate Club", 290, 4),
        ),
    )
}

class MockFeedRepository : FeedRepository {
    private val posts = MockData.posts.toMutableList()
    private val liked = mutableSetOf<String>()
    private val saved = mutableSetOf<String>()

    override suspend fun getCurrentUser(): Student = MockData.aisha

    override suspend fun getPosts(filter: FeedFilter): List<Post> {
        delay(450)
        val base = if (filter == FeedFilter.Following) posts.filter { it.fromFollowing } else posts.toList()
        return base.map { it.copy(likedByMe = it.id in liked, savedByMe = it.id in saved) }
    }

    override suspend fun getStories(): List<Student> =
        listOf(MockData.brian, MockData.grace, MockData.tech, MockData.kevin, MockData.amina)

    override suspend fun setLiked(postId: String, liked: Boolean) {
        if (liked) this.liked += postId else this.liked -= postId
    }

    override suspend fun setSaved(postId: String, saved: Boolean) {
        if (saved) this.saved += postId else this.saved -= postId
    }

    override suspend fun createPost(text: String): Post {
        delay(250)
        val post = Post("p${System.nanoTime()}", MockData.aisha, "now", text.trim(), 0, 0)
        posts.add(0, post)
        return post
    }
}

class MockDiscoverRepository : DiscoverRepository {
    override suspend fun getCandidates(): List<Student> {
        delay(350)
        val me = MockData.aisha
        return listOf(MockData.amina, MockData.brian, MockData.grace, MockData.kevin).map {
            it.copy(
                shared = it.interests.filter { i -> i in me.interests },
                sameCourse = it.course == me.course,
            )
        }
    }
    override suspend fun recordDecision(studentId: String, liked: Boolean) = Unit
}

class MockChatRepository : ChatRepository {
    private val muted = mutableSetOf<String>()
    private val archived = mutableSetOf<String>()

    override suspend fun getChats(): List<Chat> {
        delay(300)
        return MockData.chats.filter { it.id !in archived }.map { it.copy(muted = it.id in muted) }
    }
    override suspend fun setMuted(chatId: String, muted: Boolean) {
        if (muted) this.muted += chatId else this.muted -= chatId
    }
    override suspend fun archive(chatId: String) { archived += chatId }
}

class MockEventRepository : EventRepository {
    private val states = mutableMapOf<String, RsvpState>()
    override suspend fun getEvents(): List<CampusEvent> {
        delay(350)
        return MockData.events.map { it.copy(rsvp = states[it.id] ?: RsvpState.None) }
    }
    override suspend fun setRsvp(eventId: String, state: RsvpState) { states[eventId] = state }
}

class MockProfileRepository : ProfileRepository {
    override suspend fun getMyProfile(): MyProfile = MockData.me
}
