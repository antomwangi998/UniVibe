package com.univibe.app.data.repository

import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.model.Post
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
        listOf("Entrepreneurship", "Photography", "Hiking"), verified = true, accent = 2)
    val kevin = Student("s4", "Kevin Mutua", "@kevin.mutua", 22, "Mechanical Eng.", "4th Year", UNI,
        listOf("Robotics", "Football", "Gaming"), verified = false, online = true, accent = 3)
    val amina = Student("s5", "Amina Hassan", "@amina.h", 19, "Law", "1st Year", UNI,
        listOf("Debate", "Poetry", "Campus Events"), verified = true, accent = 4)
    val tech = Student("s6", "Tech Club", "@techclub", 0, "Student community", "", UNI,
        listOf("Technology"), verified = true, accent = 5)

    val posts = listOf(
        Post("p1", tech, "2h", "Great session today on AI & innovation! The future is exciting.", 128, 26, mediaAccent = 1, fromFollowing = true),
        Post("p2", grace, "4h", "Sunset at the lake is always a vibe.", 342, 41, mediaAccent = 2),
        Post("p3", brian, "6h", "Anyone up for a 5-a-side game on Saturday morning? We need two more players.", 54, 18, fromFollowing = true),
        Post("p4", amina, "1d", "Debate club trials open next week. No experience needed, just bring opinions.", 89, 12),
        Post("p5", kevin, "1d", "Our robotics team just placed second at the regional showcase. Proud of everyone.", 210, 33, mediaAccent = 4),
    )

    val chats = listOf(
        Chat("c1", "Grace Wanjiku", "See you at the library?", "9:41 AM", unread = 2, online = true),
        Chat("c2", "Study Buddies", "Kevin: Don't forget the...", "Yesterday", unread = 5, isGroup = true),
        Chat("c3", "Brian Otieno", "Thanks!", "Yesterday"),
        Chat("c4", "Design Club", "New event this Friday", "Mon", isGroup = true),
        Chat("c5", "Kevin Mutua", "Let's catch up later", "Sun", online = true),
        Chat("c6", "Events Team", "Meeting at 4pm", "Sun", isGroup = true),
    )

    val events = listOf(
        CampusEvent("e1", "Campus Night", "MAY", "18", "Fri, 7:00 PM", "Student Center", 126, "Music", 0),
        CampusEvent("e2", "Inter-Faculty Football", "MAY", "24", "Sat, 2:00 PM", "Main Field", 32, "Sports", 5),
        CampusEvent("e3", "UI/UX Workshop", "MAY", "30", "Thu, 10:00 AM", "Tech Lab 3", 18, "Academics", 1),
        CampusEvent("e4", "Startup Pitch Evening", "JUN", "05", "Wed, 5:30 PM", "Innovation Hub", 64, "Entrepreneurship", 2),
    )

    val me = MyProfile(aisha, "Lover of good music, weekend adventures and great conversations.", 128, 1200, 980)
}

class MockFeedRepository : FeedRepository {
    private val liked = mutableSetOf<String>()
    override suspend fun getPosts(filter: FeedFilter): List<Post> {
        delay(450)
        val base = if (filter == FeedFilter.Following) MockData.posts.filter { it.fromFollowing } else MockData.posts
        return base.map { it.copy(likedByMe = it.id in liked) }
    }
    override suspend fun getStories(): List<Student> = listOf(MockData.brian, MockData.grace, MockData.tech, MockData.kevin, MockData.amina)
    override suspend fun setLiked(postId: String, liked: Boolean) {
        if (liked) this.liked += postId else this.liked -= postId
    }
}

class MockDiscoverRepository : DiscoverRepository {
    override suspend fun getCandidates(): List<Student> {
        delay(350)
        return listOf(MockData.brian, MockData.grace, MockData.kevin, MockData.amina)
    }
    override suspend fun recordDecision(studentId: String, liked: Boolean) = Unit
}

class MockChatRepository : ChatRepository {
    override suspend fun getChats(): List<Chat> {
        delay(300)
        return MockData.chats
    }
}

class MockEventRepository : EventRepository {
    private val going = mutableSetOf<String>()
    override suspend fun getEvents(): List<CampusEvent> {
        delay(350)
        return MockData.events.map { it.copy(rsvped = it.id in going) }
    }
    override suspend fun setRsvp(eventId: String, going: Boolean) {
        if (going) this.going += eventId else this.going -= eventId
    }
}

class MockProfileRepository : ProfileRepository {
    override suspend fun getMyProfile(): MyProfile = MockData.me
}
