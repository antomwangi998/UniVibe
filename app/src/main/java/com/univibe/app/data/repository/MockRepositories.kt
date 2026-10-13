package com.univibe.app.data.repository

import com.univibe.app.data.model.AgendaItem
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.Comment
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.FollowEntry
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.MessageStatus
import com.univibe.app.data.model.ProfileUpdate
import com.univibe.app.data.model.ReportItem
import com.univibe.app.core.formatClock
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.profile.InMemoryProfileLocalStore
import com.univibe.app.data.profile.ProfileLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.GroupSummary
import com.univibe.app.data.model.AppNotification
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.model.NotificationTarget
import com.univibe.app.data.model.NotificationType
import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.model.Student
import com.univibe.app.data.model.StudentProfile
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

    private fun info(
        id: String, host: String, accent: Int, description: String, capacity: Int?,
        lat: Double?, lng: Double?, vararg agenda: Pair<String, String>,
    ) = EventDetails(
        event = events.first { it.id == id },
        description = description, host = host, hostAccent = accent, capacity = capacity,
        latitude = lat, longitude = lng,
        agenda = agenda.map { AgendaItem(it.first, it.second) },
    )

    /**
     * Mock-only descriptive content. The coordinates are PLACEHOLDERS near central Nairobi, not real
     * venue positions: a real backend must return the true venue location with each event.
     */
    val eventInfo: Map<String, EventDetails> by lazy {
        listOf(
            info("e1", "Student Council", 0, "An open-doors evening of music, food stalls and games to kick off the term. Bring a friend and your student ID.", 150,
                -1.2796, 36.8170, "7:00 PM" to "Doors open", "8:00 PM" to "Live DJ set", "10:30 PM" to "Wrap-up"),
            info("e2", "Sports Union", 5, "Faculties go head to head in a friendly knockout. Come and cheer, or sign your faculty team up at the sports office.", null,
                -1.2803, 36.8160, "2:00 PM" to "Kick-off", "3:45 PM" to "Halftime games", "5:00 PM" to "Final and awards"),
            info("e3", "Design Club", 1, "A hands-on introduction to user research and prototyping. Laptops recommended; no experience needed.", 40,
                -1.2789, 36.8174, "10:00 AM" to "Intro to UX", "11:00 AM" to "Prototyping lab", "12:30 PM" to "Show and tell"),
            info("e4", "Entrepreneurship Society", 2, "Founders from around the region talk about starting out, failing fast and what they would do differently.", 200,
                -1.2792, 36.8162, "3:00 PM" to "Keynote", "4:00 PM" to "Panel and questions"),
            info("e5", "Student Council", 3, "The welcome party for new students.", null, -1.2799, 36.8165),
            info("e6", "Careers Office", 4, "Meet employers and book interview slots for the coming semester.", null, -1.2810, 36.8180),
        ).associateBy { it.event.id }
    }

    class Extra(val bio: String, val posts: Int, val followers: Int, val following: Int, val communityIds: List<String>)

    /** Mock-only profile content for other students; a real backend returns this with the profile. */
    val studentExtras: Map<String, Extra> = mapOf(
        "s2" to Extra("Third-year CS student. Building campus apps and playing five-a-side on Fridays.", 42, 310, 280, listOf("tech", "football", "music")),
        "s3" to Extra("Business and photography. Chasing sunsets and good business ideas.", 87, 940, 410, listOf("photo", "startup")),
        "s4" to Extra("Robotics team lead. If it has motors, I want to take it apart.", 23, 205, 190, listOf("tech", "football")),
        "s5" to Extra("Law, first year. Poetry on weekends and always up for a debate.", 15, 120, 260, listOf("debate", "music")),
        "s6" to Extra("Talks, hack nights and projects for anyone curious about technology.", 64, 2400, 12, listOf("tech")),
    )

    val allStudents = listOf(aisha, brian, grace, kevin, amina, tech)
    const val ME_ID = "s1"

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


/** Shared in-memory state so every mock repository sees the same posts, chats, comments, etc. */
class MockStore {
    val posts = MockData.posts.toMutableList()
    val liked = mutableSetOf<String>()
    val saved = mutableSetOf<String>()
    val following = mutableSetOf("s2", "s3", "s6")

    val chats = MockData.chats.toMutableList()
    val archived = mutableSetOf<String>()
    val mutedChats = mutableSetOf<String>()
    private val messageFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val typingFlows = mutableMapOf<String, MutableStateFlow<Boolean>>()

    private val commentLists = mutableMapOf<String, MutableList<Comment>>()
    private val likedComments = mutableSetOf<String>()

    val communities = mutableListOf(
        Community("tech", "Tech Club", "Talks, hack nights and projects for anyone curious about technology.", 842, "Academic", 5,
            CommunityRole.Moderator, rules = listOf("Be respectful", "No spam or ads", "Credit your sources"), moderators = listOf("Aisha Njoki", "Brian Otieno")),
        Community("photo", "Photography Club", "Photo walks, editing tips and monthly exhibitions.", 612, "Arts", 2,
            CommunityRole.Member, rules = listOf("Post your own work", "Ask before photographing people"), moderators = listOf("Grace Wanjiku")),
        Community("debate", "Debate Club", "Weekly debates and public-speaking practice.", 290, "Academic", 4,
            CommunityRole.Member, rules = listOf("Argue the idea, not the person", "Stay on topic"), moderators = listOf("Amina Hassan")),
        Community("music", "Music Society", "Open mics, band meet-ups and campus gigs.", 560, "Arts", 0,
            rules = listOf("Keep it friendly", "Share events in the Events tab"), moderators = listOf("Kevin Mutua")),
        Community("startup", "Entrepreneurship Hub", "Pitch nights and founder stories from campus and beyond.", 445, "Business", 3,
            rules = listOf("No pyramid schemes", "Give honest feedback"), moderators = listOf("Grace Wanjiku")),
        Community("football", "Football Club", "Training sessions and inter-faculty matches.", 380, "Sports", 1,
            rules = listOf("Respect the referee", "Everyone plays"), moderators = listOf("Brian Otieno")),
    )
    val reports = mutableMapOf(
        "tech" to mutableListOf(
            ReportItem("r1", "Selling cheap laptops, message me for prices!!!", "Unknown Seller", "Spam"),
            ReportItem("r2", "That talk was a waste of everyone's time.", "Anonymous", "Harassment or bullying"),
        ),
    )

    fun commentsFor(postId: String): MutableList<Comment> = commentLists.getOrPut(postId) {
        when (postId) {
            "p1" -> mutableListOf(
                Comment("cm1", "p1", MockData.brian, "Loved the demo section. Will there be slides?", "1h", 6),
                Comment("cm2", "p1", MockData.grace, "Wish I had come! Next one for sure.", "45m", 3),
            )
            "p2" -> mutableListOf(Comment("cm3", "p2", MockData.amina, "Stunning colours. Which lens?", "3h", 12))
            "p3" -> mutableListOf(Comment("cm4", "p3", MockData.kevin, "Count me in. What time?", "5h", 1))
            else -> mutableListOf()
        }
    }

    fun isCommentLiked(id: String) = id in likedComments
    fun setCommentLiked(id: String, liked: Boolean) { if (liked) likedComments += id else likedComments -= id }

    fun adjustCommentCount(postId: String, delta: Int) {
        val i = posts.indexOfFirst { it.id == postId }
        if (i >= 0) posts[i] = posts[i].copy(commentCount = (posts[i].commentCount + delta).coerceAtLeast(0))
    }

    fun messageFlow(chatId: String): MutableStateFlow<List<Message>> = messageFlows.getOrPut(chatId) {
        val chat = chats.firstOrNull { it.id == chatId }
        val now = System.currentTimeMillis()
        MutableStateFlow(
            if (chat == null || chat.lastMessage.isBlank()) emptyList() else listOf(
                Message("${chatId}-1", chatId, "other", chat.title.substringBefore(' '), "Hey! Are we still meeting?", now - 3_600_000, false),
                Message("${chatId}-2", chatId, MockData.ME_ID, "You", "Yes, 3pm works for me.", now - 3_500_000, true, MessageStatus.Seen),
                Message("${chatId}-3", chatId, "other", chat.title.substringBefore(' '), chat.lastMessage, now - 1_800_000, false),
            ),
        )
    }

    fun typingFlow(chatId: String): MutableStateFlow<Boolean> = typingFlows.getOrPut(chatId) { MutableStateFlow(false) }

    fun appendMessage(message: Message) {
        messageFlow(message.chatId).update { it + message }
        val i = chats.indexOfFirst { it.id == message.chatId }
        if (i >= 0) {
            val preview = if (message.imagePath != null && message.text.isBlank()) "Photo" else message.text
            chats[i] = chats[i].copy(
                lastMessage = if (message.isMine) "You: $preview" else preview,
                timeLabel = formatClock(message.timeMillis),
                unread = if (message.isMine) 0 else chats[i].unread,
            )
        }
    }
}

class MockFeedRepository(private val store: MockStore = MockStore()) : FeedRepository {
    override suspend fun getCurrentUser(): Student = MockData.aisha

    override suspend fun getPosts(filter: FeedFilter): List<Post> {
        delay(450)
        val base = if (filter == FeedFilter.Following) store.posts.filter { it.fromFollowing } else store.posts.toList()
        return base.map { it.copy(likedByMe = it.id in store.liked, savedByMe = it.id in store.saved) }
    }

    override suspend fun getStories(): List<Student> =
        listOf(MockData.brian, MockData.grace, MockData.tech, MockData.kevin, MockData.amina)

    override suspend fun setLiked(postId: String, liked: Boolean) {
        if (liked) store.liked += postId else store.liked -= postId
    }

    override suspend fun setSaved(postId: String, saved: Boolean) {
        if (saved) store.saved += postId else store.saved -= postId
    }

    override suspend fun getSavedPosts(): List<Post> =
        store.posts.filter { it.id in store.saved }.map { it.copy(likedByMe = it.id in store.liked, savedByMe = true) }

    override suspend fun reportPost(postId: String, reason: String) = Unit // mock: nothing is sent

    override suspend fun publishPost(post: NewPost): Post {
        delay(400)
        // Mirrors the checks a real server must make.
        val text = post.text.trim()
        require(text.isNotEmpty() || post.imagePaths.isNotEmpty()) { "Empty post" }
        require(text.length <= 280) { "Post too long" }
        require(post.imagePaths.size <= 4) { "Too many photos" }
        val created = Post(
            "p${System.nanoTime()}", MockData.aisha, "now", text, 0, 0,
            imagePath = post.imagePaths.firstOrNull(), imagePaths = post.imagePaths, audience = post.audience,
        )
        store.posts.add(0, created)
        return created
    }

    override suspend fun createPost(text: String, hasMedia: Boolean, imagePath: String?): Post {
        delay(250)
        val post = Post(
            "p${System.nanoTime()}", MockData.aisha, "now", text.trim(), 0, 0,
            mediaAccent = if (hasMedia && imagePath == null) 0 else null, imagePath = imagePath,
        )
        store.posts.add(0, post)
        return post
    }
}

/** Mock-only notifications. A real backend creates these from server-side events and checks blocks itself. */
class MockNotificationRepository : NotificationRepository {
    private val items = mutableListOf(
        AppNotification("n1", NotificationType.Like, "Brian Otieno", "liked your post", "2m", false, NotificationTarget.Post("p1"), MockData.brian),
        AppNotification("n2", NotificationType.Comment, "Grace Wanjiku", "commented on your post", "15m", false, NotificationTarget.Post("p2"), MockData.grace),
        AppNotification("n3", NotificationType.Follow, "Kevin Mutua", "started following you", "1h", false, NotificationTarget.Student("s4"), MockData.kevin),
        AppNotification("n4", NotificationType.Event, "Campus Night", "starts tomorrow at 7:00 PM", "3h", false, NotificationTarget.Event("e1")),
        AppNotification("n5", NotificationType.Connection, "Amina Hassan", "also wants to connect with you", "5h", true, NotificationTarget.Student("s5"), MockData.amina),
        AppNotification("n6", NotificationType.Community, "Tech Club", "invited you to join", "8h", true, NotificationTarget.Community("tech")),
        AppNotification("n7", NotificationType.Message, "Grace Wanjiku", "sent you a message", "1d", true, NotificationTarget.Chat("c1"), MockData.grace),
        AppNotification("n8", NotificationType.System, "Welcome to UniVibe", "Add a photo and your interests so classmates can find you.", "2d", true),
    )
    private val _unread = MutableStateFlow(items.count { !it.read })
    override val unreadCount: StateFlow<Int> = _unread.asStateFlow()

    override suspend fun getNotifications(): List<AppNotification> { delay(300); return items.toList() }

    override suspend fun markRead(id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(read = true)
        _unread.value = items.count { !it.read }
    }

    override suspend fun markAllRead() {
        items.replaceAll { it.copy(read = true) }
        _unread.value = 0
    }
}

class MockDiscoverRepository : DiscoverRepository {
    /** Students you already passed on or connected with, kept for this app session only. */
    private val decided = mutableSetOf<String>()

    override suspend fun getCandidates(): List<Student> {
        delay(350)
        val me = MockData.aisha
        return listOf(MockData.amina, MockData.brian, MockData.grace, MockData.kevin).filter { it.id !in decided }.map {
            it.copy(
                shared = it.interests.filter { i -> i in me.interests },
                sameCourse = it.course == me.course,
            )
        }
    }
    override suspend fun recordDecision(studentId: String, liked: Boolean) { decided += studentId }
}

class MockChatRepository(private val store: MockStore = MockStore()) : ChatRepository {
    override suspend fun getChats(): List<Chat> {
        delay(300)
        return store.chats.filter { it.id !in store.archived }.map { it.copy(muted = it.id in store.mutedChats) }
    }
    override suspend fun getChat(chatId: String): Chat? =
        store.chats.firstOrNull { it.id == chatId }?.let { it.copy(muted = it.id in store.mutedChats) }
    override suspend fun setMuted(chatId: String, muted: Boolean) {
        if (muted) store.mutedChats += chatId else store.mutedChats -= chatId
    }
    override suspend fun archive(chatId: String) { store.archived += chatId }
}

class MockMessageRepository(
    private val store: MockStore = MockStore(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val autoReply: Boolean = true,
) : MessageRepository {
    private val replies = listOf("Sounds good!", "Haha, same here.", "Let me check and get back to you.", "See you there!", "Thanks for letting me know.")

    override fun messages(chatId: String): Flow<List<Message>> = store.messageFlow(chatId)
    override fun typing(chatId: String): Flow<Boolean> = store.typingFlow(chatId)

    override suspend fun send(chatId: String, text: String, imagePath: String?) {
        val clean = text.trim()
        if (clean.isEmpty() && imagePath == null) return
        store.appendMessage(Message("m${System.nanoTime()}", chatId, MockData.ME_ID, "You", clean, System.currentTimeMillis(), true, MessageStatus.Sent, imagePath))
        if (!autoReply) return
        // MOCK: a fake reply so the conversation feels alive. A real backend replaces all of this.
        scope.launch {
            kotlinx.coroutines.delay(900)
            store.typingFlow(chatId).value = true
            kotlinx.coroutines.delay(1800)
            store.typingFlow(chatId).value = false
            store.messageFlow(chatId).update { list -> list.map { if (it.isMine) it.copy(status = MessageStatus.Seen) else it } }
            val chat = store.chats.firstOrNull { it.id == chatId }
            val sender = if (chat?.isGroup == true) "Kevin" else chat?.title?.substringBefore(' ') ?: "Student"
            store.appendMessage(Message("m${System.nanoTime()}", chatId, "other", sender, replies[(clean.length) % replies.size], System.currentTimeMillis(), false))
        }
    }

    override suspend fun deleteMessage(chatId: String, messageId: String) {
        store.messageFlow(chatId).update { list -> list.filterNot { it.id == messageId && it.isMine } }
    }

    override suspend fun markRead(chatId: String) {
        val i = store.chats.indexOfFirst { it.id == chatId }
        if (i >= 0) store.chats[i] = store.chats[i].copy(unread = 0)
    }

    override suspend fun openChatWith(student: Student): String {
        store.chats.firstOrNull { !it.isGroup && it.title == student.name }?.let { store.archived -= it.id; return it.id }
        val id = "c_${student.id}"
        if (store.chats.none { it.id == id }) store.chats.add(0, Chat(id, student.name, "", "", online = student.online))
        store.archived -= id
        return id
    }
}

class MockCommentRepository(private val store: MockStore = MockStore()) : CommentRepository {
    override suspend fun getComments(postId: String): List<Comment> {
        delay(300)
        return store.commentsFor(postId).map { it.copy(likedByMe = store.isCommentLiked(it.id)) }
    }

    override suspend fun addComment(postId: String, text: String): Comment {
        delay(200)
        val comment = Comment("cm${System.nanoTime()}", postId, MockData.aisha, text.trim(), "now", 0, isMine = true)
        store.commentsFor(postId).add(comment)
        store.adjustCommentCount(postId, +1)
        return comment
    }

    override suspend fun setLiked(commentId: String, liked: Boolean) = store.setCommentLiked(commentId, liked)

    override suspend fun deleteComment(commentId: String) {
        store.posts.forEach { post ->
            val list = store.commentsFor(post.id)
            if (list.removeAll { it.id == commentId && it.isMine }) store.adjustCommentCount(post.id, -1)
        }
    }

    override suspend fun reportComment(commentId: String, reason: String) = Unit // mock
}

class MockCommunityRepository(private val store: MockStore = MockStore()) : CommunityRepository {
    override suspend fun getCommunities(): List<Community> { delay(300); return store.communities.toList() }
    override suspend fun getCommunity(id: String): Community? = store.communities.firstOrNull { it.id == id }

    override suspend fun setMembership(id: String, join: Boolean) {
        val i = store.communities.indexOfFirst { it.id == id }
        if (i < 0) return
        val c = store.communities[i]
        if (join && c.role == CommunityRole.None) store.communities[i] = c.copy(role = CommunityRole.Member, members = c.members + 1)
        if (!join && c.role != CommunityRole.None) store.communities[i] = c.copy(role = CommunityRole.None, members = c.members - 1, muted = false)
    }

    override suspend fun setMuted(id: String, muted: Boolean) {
        val i = store.communities.indexOfFirst { it.id == id }
        if (i >= 0) store.communities[i] = store.communities[i].copy(muted = muted)
    }

    override suspend fun getReports(id: String): List<ReportItem> = store.reports[id].orEmpty().toList()

    override suspend fun resolveReport(communityId: String, reportId: String, removePost: Boolean) {
        store.reports[communityId]?.removeAll { it.id == reportId }
    }
}

class MockEventRepository : EventRepository {
    private val states = mutableMapOf<String, RsvpState>()
    /** Counts include your own RSVP so lists and details always agree after a reload. */
    private fun withState(e: CampusEvent): CampusEvent {
        val state = states[e.id] ?: RsvpState.None
        return e.copy(
            rsvp = state,
            going = e.going + if (state == RsvpState.Going) 1 else 0,
            interested = e.interested + if (state == RsvpState.Interested) 1 else 0,
        )
    }

    override suspend fun getEvents(): List<CampusEvent> {
        delay(350)
        return MockData.events.map(::withState)
    }

    override suspend fun getEvent(id: String): EventDetails? {
        delay(250)
        val base = MockData.eventInfo[id] ?: return null
        return base.copy(event = withState(base.event))
    }

    override suspend fun setRsvp(eventId: String, state: RsvpState) { states[eventId] = state }
}

class MockProfileRepository(
    private val store: MockStore = MockStore(),
    private val local: ProfileLocalStore = InMemoryProfileLocalStore(),
) : ProfileRepository {
    override suspend fun getMyProfile(): MyProfile {
        val base = MockData.me
        val e = local.edits.first()
        val student = base.student.copy(
            name = e.name ?: base.student.name,
            course = e.course ?: base.student.course,
            year = e.year ?: base.student.year,
            interests = e.interests ?: base.student.interests,
        )
        return base.copy(
            student = student, bio = e.bio ?: base.bio, avatarPath = e.avatarPath, coverPath = e.coverPath, photos = e.photos, discoverPhotoPath = e.discoverPhotoPath,
            posts = base.posts + store.posts.count { it.author.id == MockData.ME_ID && it.id.startsWith("p") && it.timeAgo == "now" },
            savedPosts = store.saved.size,
            following = store.following.size,
        )
    }

    override suspend fun updateProfile(update: ProfileUpdate) {
        val old = local.edits.first()
        local.update {
            it.copy(
                name = update.name.trim(), bio = update.bio.trim(), course = update.course.trim(), year = update.year,
                interests = update.interests, avatarPath = update.avatarPath, coverPath = update.coverPath,
            )
        }
        if (old.avatarPath != update.avatarPath) PhotoStore.delete(old.avatarPath)
        if (old.coverPath != update.coverPath) PhotoStore.delete(old.coverPath)
    }

    override suspend fun addPhoto(path: String) { local.update { it.copy(photos = listOf(path) + it.photos) } }

    override suspend fun setDiscoverPhoto(path: String?) {
        val old = local.edits.first().discoverPhotoPath
        local.update { it.copy(discoverPhotoPath = path) }
        if (old != path) PhotoStore.delete(old)
    }

    override suspend fun removePhoto(path: String) {
        local.update { it.copy(photos = it.photos - path) }
        PhotoStore.delete(path)
    }

    override suspend fun getStudent(id: String): Student? = MockData.allStudents.firstOrNull { it.id == id }

    override suspend fun getStudentProfile(id: String): StudentProfile? {
        if (id == MockData.ME_ID) {
            val me = MockData.me
            return StudentProfile(me.student, me.bio, me.posts, me.followers, me.following, listOf("tech", "photo", "debate"))
        }
        val student = MockData.allStudents.firstOrNull { it.id == id } ?: return null
        val extra = MockData.studentExtras[id]
        val me = MockData.aisha
        // The data layer computes what you have in common; the UI never guesses it.
        val withCommon = student.copy(shared = student.interests.filter { it in me.interests }, sameCourse = student.course == me.course)
        return StudentProfile(
            withCommon, extra?.bio.orEmpty(), extra?.posts ?: 0, extra?.followers ?: 0, extra?.following ?: 0,
            extra?.communityIds.orEmpty(),
        )
    }

    override suspend fun getFollowers(): List<FollowEntry> =
        listOf(MockData.kevin, MockData.amina, MockData.grace, MockData.brian).map { FollowEntry(it, it.id in store.following) }

    override suspend fun getFollowing(): List<FollowEntry> =
        MockData.allStudents.filter { it.id != MockData.ME_ID && it.id in store.following }.map { FollowEntry(it, true) }

    override suspend fun setFollowing(studentId: String, following: Boolean) {
        if (following) store.following += studentId else store.following -= studentId
    }
}
