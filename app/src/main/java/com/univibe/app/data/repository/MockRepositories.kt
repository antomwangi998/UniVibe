package com.univibe.app.data.repository

import com.univibe.app.data.model.AgendaItem
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.domain.auth.ProfileEdit
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EDIT_WINDOW_MILLIS
import com.univibe.app.data.model.EVENT_RETENTION_MILLIS
import com.univibe.app.data.model.EventCategoryOptions
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.NewEvent
import com.univibe.app.data.model.Venue
import com.univibe.app.core.eventDateParts
import com.univibe.app.data.model.GroupInfo
import com.univibe.app.data.model.GroupMember
import com.univibe.app.data.model.GroupRole
import com.univibe.app.data.model.Reaction
import com.univibe.app.data.model.ReplyRef
import com.univibe.app.data.model.Comment
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityMember
import com.univibe.app.data.model.CommunityPrivacy
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.CommunityCategories
import com.univibe.app.data.model.MAX_COMMUNITIES_OWNED
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
import com.univibe.app.data.model.PostAudience
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.model.Student
import com.univibe.app.data.model.StudentProfile
import com.univibe.app.data.model.Poll
import com.univibe.app.data.model.PollOption
import com.univibe.app.data.model.PostLimits
import kotlinx.coroutines.delay

/*
 * MOCK DATA ONLY. Nothing here talks to a server. All people, posts and events are
 * invented sample content so every screen can be developed without a backend.
 */
object MockData {
    private const val UNI = "Lakeside University"

    private val sampleAisha = Student("s1", "Aisha Njoki", "@aishanjoki", 21, "Computer Science", "2nd Year", UNI,
        listOf("Music", "Travel", "Reading", "Movies"), verified = true, online = true, accent = 0)

    @Volatile private var identity: Student? = null
    @Volatile private var identityBio: String? = null

    /** Makes the mock world revolve around the real signed-in student instead of the sample one. Null restores the sample. */
    fun applyAccount(user: AuthUser?) {
        if (user == null) {
            identity = null
            identityBio = null
            return
        }
        val handle = user.handle.ifBlank { user.fullName.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "student" } }
        identity = Student(
            ME_ID, user.fullName, "@$handle", 0, user.course, user.yearOfStudy, user.universityName,
            user.interests, verified = user.studentVerified, online = true, accent = 0,
        )
        identityBio = user.bio
    }

    /** The signed-in student (the real account once one is applied). */
    val aisha: Student get() = identity ?: sampleAisha
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
        Post("p3", brian, "6h", "Anyone up for a 5-a-side game on Saturday morning? We need two more players.", 54, 18, 2, fromFollowing = true,
            poll = Poll(listOf(PollOption("Saturday 8am", 9), PollOption("Saturday 10am", 14), PollOption("Sunday afternoon", 4)))),
        Post("p4", amina, "1d", "Debate club trials open next week. No experience needed, just bring opinions.", 89, 12, 7),
        Post("p5", kevin, "1d", "Our robotics team just placed second at the regional showcase. Proud of everyone.", 210, 33, 9, mediaAccent = 4),
    )

    val chats = listOf(
        Chat("c1", "Grace Wanjiku", "See you at the library?", "9:41 AM", unread = 2, online = true, pinned = true, studentId = "s3"),
        Chat("c2", "Study Buddies", "Kevin: Don't forget the...", "8:30 AM", unread = 5, isGroup = true, pinned = true),
        Chat("c3", "Brian Otieno", "Thanks!", "Yesterday", studentId = "s2"),
        Chat("c4", "Design Club", "New event this Friday", "Mon", isGroup = true),
        Chat("c5", "Kevin Mutua", "Let's catch up later", "Sun", online = true, studentId = "s4"),
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

    val allStudents: List<Student> get() = listOf(aisha, brian, grace, kevin, amina, tech)
    const val ME_ID = "s1"

    private val sampleMe = MyProfile(
        student = sampleAisha,
        bio = "Lover of good music, weekend adventures and great conversations.",
        posts = 128, followers = 1200, following = 980, savedPosts = 12,
        groups = listOf(
            GroupSummary("Tech Club", 842, 5),
            GroupSummary("Photography Club", 612, 2),
            GroupSummary("Debate Club", 290, 4),
        ),
    )

    /** A real account starts with nothing: no followers, no posts, no groups. Counts then come from the data layer. */
    val me: MyProfile
        get() {
            val account = identity ?: return sampleMe
            return MyProfile(
                student = account, bio = identityBio.orEmpty(),
                posts = 0, followers = 0, following = 0, savedPosts = 0, groups = emptyList(),
            )
        }
}


/** Shared in-memory state so every mock repository sees the same posts, chats, comments, etc. */
/** [demo] = true seeds the sample social graph (people you follow, clubs you belong to). A real account starts empty. */
class MockStore(demo: Boolean = true) {
    val posts = (MockData.posts + listOf(
        Post("pc1", MockData.brian, "3h", "Hack night is back this Friday. Bring a laptop and an idea.", 12, 3, audience = PostAudience.Community("tech", "Tech Club")),
        Post("pc2", MockData.grace, "1d", "Reminder: slides from the AI talk are in the shared folder.", 7, 1, audience = PostAudience.Community("tech", "Tech Club")),
        Post("pc3", MockData.grace, "2d", "Photo walk on Saturday morning, meet at the library steps.", 20, 5, audience = PostAudience.Community("photo", "Photography Club")),
    )).toMutableList()
    val liked = mutableSetOf<String>()
    val saved = mutableSetOf<String>()
    val following: MutableSet<String> = if (demo) mutableSetOf("s2", "s3", "s6") else mutableSetOf()

    val chats = MockData.chats.toMutableList()
    val archived = mutableSetOf<String>()
    val mutedChats = mutableSetOf<String>()
    private val messageFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val typingFlows = mutableMapOf<String, MutableStateFlow<Boolean>>()

    val groups = mutableMapOf<String, GroupInfo>()

    /** Events with real times, seeded relative to now so the Past tab and auto-expiry can be seen. */
    val events: MutableList<EventDetails> = run {
        val hour = 3_600_000L
        val base = System.currentTimeMillis().let { it - it % hour }
        // id to (start offset in hours, duration in hours)
        val offsets = mapOf("e1" to (50L to 4L), "e2" to (150L to 3L), "e3" to (270L to 3L), "e4" to (400L to 2L), "e5" to (-80L to 4L), "e6" to (-150L to 8L))
        MockData.eventInfo.values.map { d ->
            val (startH, durH) = offsets.getValue(d.event.id)
            val start = base + startH * hour
            d.copy(event = d.event.copy(startMillis = start, endMillis = start + durH * hour, hostName = d.host))
        }.toMutableList()
    }
    val rsvps = mutableMapOf<String, RsvpState>()

    private val communityMembers = mutableMapOf<String, MutableList<CommunityMember>>()
    val joinRequests = mutableMapOf<String, MutableList<Student>>(
        "tech" to mutableListOf(MockData.amina, MockData.kevin),
    )

    /** Mock-only member lists. A real backend stores memberships; counts shown in lists come from the server. */
    fun membersOf(id: String): MutableList<CommunityMember> = communityMembers.getOrPut(id) {
        val c = communities.first { it.id == id }
        val list = mutableListOf<CommunityMember>()
        c.moderators.forEach { name ->
            MockData.allStudents.firstOrNull { it.name == name && it.id != MockData.tech.id }?.let { s ->
                val isMe = s.id == MockData.aisha.id
                list += CommunityMember(s, if (isMe && c.role != CommunityRole.None) c.role else CommunityRole.Moderator, isMe)
            }
        }
        if (c.role != CommunityRole.None && list.none { it.isMe }) list += CommunityMember(MockData.aisha, c.role, true)
        MockData.allStudents.filter { s -> s.id != MockData.tech.id && list.none { it.student.id == s.id } && !(joinRequests[id].orEmpty().any { r -> r.id == s.id }) }
            .take(3).forEach { list += CommunityMember(it, CommunityRole.Member) }
        list
    }

    fun dropCommunity(id: String) {
        communities.removeAll { it.id == id }
        communityMembers.remove(id)
        joinRequests.remove(id)
        reports.remove(id)
        events.removeAll { it.event.hostCommunityId == id }
        posts.removeAll { (it.audience as? PostAudience.Community)?.id == id }
    }
    private val pinnedFlows = mutableMapOf<String, MutableStateFlow<String?>>()
    fun pinnedFlow(chatId: String): MutableStateFlow<String?> = pinnedFlows.getOrPut(chatId) { MutableStateFlow(null) }

    /** Mock-only seed. A real backend stores groups and enforces every rule itself. */
    fun group(chatId: String): GroupInfo? {
        groups[chatId]?.let { return it }
        val chat = chats.firstOrNull { it.id == chatId && it.isGroup } ?: return null
        val me = GroupMember(MockData.aisha, GroupRole.Member, isMe = true)
        fun m(s: Student, r: GroupRole = GroupRole.Member) = GroupMember(s, r)
        val info = when (chatId) {
            "c2" -> GroupInfo(chatId, chat.title, "Exam prep, shared notes and late-night snack runs.", null, "Kevin Mutua", "Created 3 Sep 2026",
                listOf(m(MockData.kevin, GroupRole.Owner), me.copy(role = GroupRole.Admin), m(MockData.brian), m(MockData.grace), m(MockData.amina)))
            "c4" -> GroupInfo(chatId, chat.title, "Critiques, events and good coffee for designers.", null, "Grace Wanjiku", "Created 12 Aug 2026",
                listOf(m(MockData.grace, GroupRole.Owner), m(MockData.amina, GroupRole.Admin), me, m(MockData.brian)),
                onlyAdminsCanAddMembers = false)
            "c6" -> GroupInfo(chatId, chat.title, "Planning team for campus events.", null, "Aisha Njoki", "Created 28 Jul 2026",
                listOf(me.copy(role = GroupRole.Owner), m(MockData.kevin, GroupRole.Admin), m(MockData.grace), m(MockData.brian), m(MockData.amina)))
            else -> GroupInfo(chatId, chat.title, "Announcements and links for the Tech Creators.", null, "Brian Otieno", "Created 1 Jul 2026",
                listOf(m(MockData.brian, GroupRole.Owner), m(MockData.kevin), me), onlyAdminsCanMessage = true)
        }
        groups[chatId] = info
        return info
    }

    fun renameChat(chatId: String, name: String) {
        val i = chats.indexOfFirst { it.id == chatId }
        if (i >= 0) chats[i] = chats[i].copy(title = name)
    }

    private val commentLists = mutableMapOf<String, MutableList<Comment>>()
    private val likedComments = mutableSetOf<String>()

    val communities = mutableListOf(
        Community("tech", "Tech Club", "Talks, hack nights and projects for anyone curious about technology.", 842, "Academic", 5,
            CommunityRole.Moderator, rules = listOf("Be respectful", "No spam or ads", "Credit your sources"), moderators = listOf("Aisha Njoki", "Brian Otieno"),
            privacy = CommunityPrivacy.Private, createdLabel = "Created 4 Feb 2026"),
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

    init {
        if (!demo) for (i in communities.indices) communities[i] = communities[i].copy(role = CommunityRole.None)
    }
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
                Comment("cm5", "p1", MockData.tech, "${MockData.brian.handle} Yes, slides go out tomorrow.", "30m", 2, parentId = "cm1", replyToHandle = MockData.brian.handle),
                Comment("cm6", "p1", MockData.brian, "${MockData.tech.handle} Thanks, appreciated!", "20m", 0, parentId = "cm1", replyToHandle = MockData.tech.handle),
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
                Message("${chatId}-1", chatId, firstSender(chat).first, firstSender(chat).second, "Hey! Are we still meeting?", now - 3_600_000, false),
                Message("${chatId}-2", chatId, MockData.ME_ID, "You", "Yes, 3pm works for me.", now - 3_500_000, true, MessageStatus.Seen),
                Message("${chatId}-3", chatId, firstSender(chat).first, firstSender(chat).second, chat.lastMessage, now - 1_800_000, false),
            ),
        )
    }

    /** Sender id and display name for seeded incoming messages: a real member in groups, the other person in 1:1s. */
    private fun firstSender(chat: Chat): Pair<String, String> {
        val other = group(chat.id)?.members?.firstOrNull { !it.isMe && it.role != GroupRole.Member }?.student
            ?: group(chat.id)?.members?.firstOrNull { !it.isMe }?.student
        return if (other != null) other.id to other.name else "other" to chat.title.substringBefore(' ')
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

    override suspend fun createPost(
        text: String,
        hasMedia: Boolean,
        imagePath: String?,
        videoPath: String?,
        pollOptions: List<String>,
    ): Post {
        delay(250)
        val clean = text.trim()
        val poll = if (pollOptions.isEmpty()) null else {
            require(!hasMedia && imagePath == null && videoPath == null) { "A poll can't have media" }
            Poll(validPollOptions(pollOptions).map { PollOption(it) })
        }
        require(clean.length <= PostLimits.MAX_TEXT) { "Post too long" }
        val post = Post(
            "p${System.nanoTime()}", MockData.aisha, "now", clean, 0, 0,
            mediaAccent = if (hasMedia && imagePath == null && videoPath == null) 0 else null,
            imagePath = imagePath, videoPath = videoPath, poll = poll,
        )
        store.posts.add(0, post)
        return post
    }

    private fun validPollOptions(raw: List<String>): List<String> {
        val options = raw.map { it.trim() }
        require(options.size in PostLimits.MIN_POLL_OPTIONS..PostLimits.MAX_POLL_OPTIONS) { "A poll needs 2 to 4 options" }
        require(options.all { it.isNotEmpty() && it.length <= PostLimits.MAX_POLL_OPTION_LENGTH }) { "Invalid poll option" }
        require(options.map { it.lowercase() }.toSet().size == options.size) { "Poll options must differ" }
        return options
    }

    private fun change(postId: String, transform: (Post) -> Post): Post {
        val i = store.posts.indexOfFirst { it.id == postId }
        require(i >= 0) { "Post not found" }
        val updated = transform(store.posts[i])
        store.posts[i] = updated
        return updated
    }

    private fun requireOwn(post: Post) = require(post.author.id == MockData.aisha.id) { "You can only change your own posts" }

    override suspend fun vote(postId: String, optionIndex: Int): Post {
        delay(150)
        return change(postId) { post ->
            val poll = requireNotNull(post.poll) { "This post has no poll" }
            require(poll.myVote == null) { "You already voted" }
            require(optionIndex in poll.options.indices) { "Unknown option" }
            val options = poll.options.mapIndexed { i, o -> if (i == optionIndex) o.copy(votes = o.votes + 1) else o }
            post.copy(poll = poll.copy(options = options, myVote = optionIndex))
        }
    }

    override suspend fun editPost(postId: String, text: String): Post {
        delay(250)
        val clean = text.trim()
        require(clean.length <= PostLimits.MAX_TEXT) { "Post too long" }
        return change(postId) { post ->
            requireOwn(post)
            val hasContent = post.imagePath != null || post.imagePaths.isNotEmpty() || post.videoPath != null ||
                post.mediaAccent != null || post.poll != null
            require(clean.isNotEmpty() || hasContent) { "Empty post" }
            post.copy(text = clean, edited = true)
        }
    }

    override suspend fun deletePost(postId: String) {
        delay(250)
        val post = store.posts.firstOrNull { it.id == postId } ?: return
        requireOwn(post)
        store.posts.remove(post)
        store.liked -= postId
        store.saved -= postId
    }

    override suspend fun recordShare(postId: String) {
        change(postId) { it.copy(shareCount = it.shareCount + 1) }
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

    override suspend fun send(chatId: String, text: String, imagePath: String?, replyTo: ReplyRef?) {
        val clean = text.trim()
        if (clean.isEmpty() && imagePath == null) return
        // Mirrors server rules: group posting permissions, and replies must point at a real message in this chat.
        store.group(chatId)?.let { check(it.canMessage) { "Only admins can send messages in this group." } }
        if (replyTo != null) require(store.messageFlow(chatId).value.any { it.id == replyTo.messageId }) { "Message not found" }
        store.appendMessage(Message("m${System.nanoTime()}", chatId, MockData.ME_ID, "You", clean, System.currentTimeMillis(), true, MessageStatus.Sent, imagePath, replyTo))
        if (!autoReply) return
        // MOCK: a fake reply so the conversation feels alive. A real backend replaces all of this.
        scope.launch {
            kotlinx.coroutines.delay(900)
            store.typingFlow(chatId).value = true
            kotlinx.coroutines.delay(1800)
            store.typingFlow(chatId).value = false
            store.messageFlow(chatId).update { list -> list.map { if (it.isMine) it.copy(status = MessageStatus.Seen) else it } }
            val chat = store.chats.firstOrNull { it.id == chatId }
            val member = store.group(chatId)?.members?.filter { !it.isMe }?.let { it.getOrNull(clean.length % it.size.coerceAtLeast(1)) }?.student
            val senderId = member?.id ?: "other"
            val sender = member?.name ?: chat?.title?.substringBefore(' ') ?: "Student"
            store.appendMessage(Message("m${System.nanoTime()}", chatId, senderId, sender, replies[(clean.length) % replies.size], System.currentTimeMillis(), false))
        }
    }

    override suspend fun deleteMessage(chatId: String, messageId: String) {
        store.messageFlow(chatId).update { list -> list.filterNot { it.id == messageId && it.isMine } }
    }

    override suspend fun react(chatId: String, messageId: String, reaction: Reaction?) {
        store.messageFlow(chatId).update { list ->
            list.map { m ->
                if (m.id != messageId) m else {
                    val counts = m.reactions.toMutableMap()
                    m.myReaction?.let { old -> val left = (counts[old] ?: 1) - 1; if (left <= 0) counts.remove(old) else counts[old] = left }
                    if (reaction != null) counts[reaction] = (counts[reaction] ?: 0) + 1
                    m.copy(reactions = counts, myReaction = reaction)
                }
            }
        }
    }

    override suspend fun editMessage(chatId: String, messageId: String, text: String) {
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= 1000) { "Invalid message" }
        val flow = store.messageFlow(chatId)
        val m = flow.value.firstOrNull { it.id == messageId } ?: throw IllegalArgumentException("Message not found")
        check(m.isMine) { "You can only edit your own messages." }
        check(System.currentTimeMillis() - m.timeMillis <= EDIT_WINDOW_MILLIS) { "Messages can only be edited for 15 minutes." }
        flow.update { list -> list.map { if (it.id == messageId) it.copy(text = clean, edited = true) else it } }
    }

    override suspend fun forward(fromChatId: String, messageId: String, toChatIds: List<String>) {
        val m = store.messageFlow(fromChatId).value.firstOrNull { it.id == messageId } ?: throw IllegalArgumentException("Message not found")
        val targets = toChatIds.distinct()
        require(targets.size in 1..5) { "Pick 1 to 5 chats" }
        // Check every target first so a refusal sends nothing.
        targets.forEach { id ->
            require(store.chats.any { it.id == id }) { "Chat not found" }
            store.group(id)?.let { check(it.canMessage) { "You can't post in ${it.name}." } }
        }
        targets.forEach { id ->
            store.appendMessage(Message("m${System.nanoTime()}", id, MockData.ME_ID, "You", m.text, System.currentTimeMillis(), true, MessageStatus.Sent, m.imagePath, forwarded = true))
        }
    }

    override fun pinnedMessageId(chatId: String): Flow<String?> = store.pinnedFlow(chatId)

    override suspend fun pin(chatId: String, messageId: String?) {
        store.group(chatId)?.let { check(it.iAmAdmin) { "Only admins can pin messages." } }
        if (messageId != null) require(store.messageFlow(chatId).value.any { it.id == messageId }) { "Message not found" }
        store.pinnedFlow(chatId).value = messageId
    }

    override suspend fun clearChat(chatId: String) {
        store.messageFlow(chatId).value = emptyList()
        val i = store.chats.indexOfFirst { it.id == chatId }
        if (i >= 0) store.chats[i] = store.chats[i].copy(lastMessage = "", unread = 0)
    }

    override suspend fun markRead(chatId: String) {
        val i = store.chats.indexOfFirst { it.id == chatId }
        if (i >= 0) store.chats[i] = store.chats[i].copy(unread = 0)
    }

    override suspend fun openChatWith(student: Student): String {
        store.chats.firstOrNull { !it.isGroup && it.title == student.name }?.let { store.archived -= it.id; return it.id }
        val id = "c_${student.id}"
        if (store.chats.none { it.id == id }) store.chats.add(0, Chat(id, student.name, "", "", online = student.online, studentId = student.id))
        store.archived -= id
        return id
    }
}

class MockCommentRepository(private val store: MockStore = MockStore()) : CommentRepository {
    override suspend fun getComments(postId: String): List<Comment> {
        delay(300)
        val postAuthor = store.posts.firstOrNull { it.id == postId }?.author?.id
        return store.commentsFor(postId).map { it.copy(likedByMe = store.isCommentLiked(it.id), isPostAuthor = it.author.id == postAuthor) }
    }

    override suspend fun addComment(postId: String, text: String, replyToId: String?): Comment {
        delay(200)
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= 280) { "Invalid comment" }
        val list = store.commentsFor(postId)
        val target = replyToId?.let { id -> list.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Comment not found") }
        val comment = Comment(
            "cm${System.nanoTime()}", postId, MockData.aisha, clean, "now", 0, isMine = true,
            parentId = target?.let { it.parentId ?: it.id }, replyToHandle = target?.author?.handle,
            isPostAuthor = store.posts.firstOrNull { it.id == postId }?.author?.id == MockData.aisha.id,
        )
        store.commentsFor(postId).add(comment)
        store.adjustCommentCount(postId, +1)
        return comment
    }

    override suspend fun setLiked(commentId: String, liked: Boolean) = store.setCommentLiked(commentId, liked)

    override suspend fun deleteComment(commentId: String) {
        store.posts.forEach { post ->
            val list = store.commentsFor(post.id)
            if (list.none { it.id == commentId && it.isMine }) return@forEach
            // Deleting a top-level comment removes its replies too.
            val removed = list.count { it.id == commentId || it.parentId == commentId }
            list.removeAll { it.id == commentId || it.parentId == commentId }
            store.adjustCommentCount(post.id, -removed)
        }
    }

    override suspend fun reportComment(commentId: String, reason: String) = Unit // mock
}

class MockCommunityRepository(private val store: MockStore = MockStore()) : CommunityRepository {
    private fun index(id: String): Int = store.communities.indexOfFirst { it.id == id }
    private fun need(id: String): Community = store.communities.getOrNull(index(id)) ?: throw IllegalArgumentException("Community not found")
    private fun requested(id: String) = store.joinRequests[id].orEmpty().any { it.id == MockData.aisha.id }
    private fun view(c: Community) = c.copy(joinRequested = requested(c.id))

    /** Keeps your role and the moderator names shown on cards in step with the member list. */
    private fun sync(id: String) {
        val i = index(id)
        if (i < 0) return
        val members = store.membersOf(id)
        val me = members.firstOrNull { it.isMe }
        store.communities[i] = store.communities[i].copy(
            role = me?.role ?: CommunityRole.None,
            moderators = members.filter { it.role == CommunityRole.Owner || it.role == CommunityRole.Moderator }.map { it.student.name },
        )
    }

    /** The headline member count is a server number (the mock lists only a few people), so adjust it by the change. */
    private fun bump(id: String, delta: Int) {
        val i = index(id)
        if (i >= 0) store.communities[i] = store.communities[i].copy(members = (store.communities[i].members + delta).coerceAtLeast(0))
    }

    override suspend fun getCommunities(): List<Community> { delay(300); return store.communities.map(::view) }
    override suspend fun getCommunity(id: String): Community? = store.communities.firstOrNull { it.id == id }?.let(::view)

    override suspend fun setMembership(id: String, join: Boolean) {
        val c = store.communities.firstOrNull { it.id == id } ?: return
        if (join) {
            if (c.isMember || requested(id)) return
            if (c.privacy == CommunityPrivacy.Public) {
                store.membersOf(id) += CommunityMember(MockData.aisha, CommunityRole.Member, isMe = true)
                bump(id, +1)
                sync(id)
            } else {
                store.joinRequests.getOrPut(id) { mutableListOf() } += MockData.aisha
            }
        } else {
            check(c.role != CommunityRole.Owner) { "Delete the community, or hand it over, before you leave." }
            if (store.membersOf(id).removeAll { it.isMe }) bump(id, -1)
            store.joinRequests[id]?.removeAll { it.id == MockData.aisha.id }
            sync(id)
            val i = index(id)
            store.communities[i] = store.communities[i].copy(muted = false)
        }
    }

    override suspend fun setMuted(id: String, muted: Boolean) {
        val i = index(id)
        if (i >= 0) store.communities[i] = store.communities[i].copy(muted = muted)
    }

    override suspend fun getReports(id: String): List<ReportItem> {
        check(need(id).isStaff) { "Only moderators can see reports." }
        return store.reports[id].orEmpty().toList()
    }

    override suspend fun resolveReport(communityId: String, reportId: String, removePost: Boolean) {
        check(need(communityId).isStaff) { "Only moderators can resolve reports." }
        store.reports[communityId]?.removeAll { it.id == reportId }
    }

    private fun validate(description: String, category: String, accent: Int, rules: List<String>): List<String> {
        require(description.trim().length in 10..300) { "Description must be 10 to 300 characters" }
        require(category in CommunityCategories) { "Pick a category" }
        require(accent in 0..5) { "Invalid colour" }
        val clean = rules.map { it.trim() }.filter { it.isNotEmpty() }
        require(clean.size <= 8) { "Up to 8 rules" }
        require(clean.all { it.length <= 120 }) { "Rules can be up to 120 characters" }
        return clean
    }

    override suspend fun createCommunity(name: String, description: String, category: String, accent: Int, rules: List<String>, privacy: CommunityPrivacy): String {
        delay(400)
        val n = name.trim()
        require(n.length in 3..40) { "Name must be 3 to 40 characters" }
        require(store.communities.none { it.name.equals(n, ignoreCase = true) }) { "A community with that name already exists." }
        val cleanRules = validate(description, category, accent, rules)
        check(store.communities.count { it.role == CommunityRole.Owner } < MAX_COMMUNITIES_OWNED) { "You can own up to $MAX_COMMUNITIES_OWNED communities." }
        val id = "c${System.nanoTime()}"
        store.communities.add(0, Community(
            id, n, description.trim(), 1, category, accent, CommunityRole.Owner, rules = cleanRules,
            moderators = listOf(MockData.aisha.name), privacy = privacy, createdLabel = "Created today",
        ))
        store.membersOf(id) // seeds me as owner
        sync(id)
        return id
    }

    override suspend fun updateCommunity(id: String, description: String, category: String, accent: Int, rules: List<String>, privacy: CommunityPrivacy) {
        val c = need(id)
        check(c.role == CommunityRole.Owner) { "Only the owner can edit this community." }
        val cleanRules = validate(description, category, accent, rules)
        if (privacy == CommunityPrivacy.Public && c.privacy == CommunityPrivacy.Private) {
            val waiting = store.joinRequests[id].orEmpty().toList()
            waiting.forEach { s -> if (store.membersOf(id).none { it.student.id == s.id }) store.membersOf(id) += CommunityMember(s, CommunityRole.Member) }
            store.joinRequests[id]?.clear()
            bump(id, waiting.size)
        }
        // Re-read: bump() above may have changed the stored member count, and a stale copy would undo it.
        val latest = store.communities[index(id)]
        store.communities[index(id)] = latest.copy(description = description.trim(), category = category, accent = accent, rules = cleanRules, privacy = privacy)
        sync(id)
    }

    override suspend fun deleteCommunity(id: String) {
        check(need(id).role == CommunityRole.Owner) { "Only the owner can delete this community." }
        store.dropCommunity(id)
    }

    override suspend fun getMembers(id: String): List<CommunityMember> {
        delay(200)
        val c = need(id)
        check(c.privacy == CommunityPrivacy.Public || c.isMember) { "Join to see the members." }
        return store.membersOf(id).sortedWith(compareBy<CommunityMember>({ -it.role.ordinal }, { !it.isMe }, { it.student.name }))
    }

    override suspend fun setMemberRole(id: String, studentId: String, role: CommunityRole) {
        check(need(id).role == CommunityRole.Owner) { "Only the owner can change moderators." }
        require(role == CommunityRole.Member || role == CommunityRole.Moderator) { "Invalid role" }
        val members = store.membersOf(id)
        val i = members.indexOfFirst { it.student.id == studentId }
        if (i < 0) return
        check(!members[i].isMe && members[i].role != CommunityRole.Owner) { "You can't change that role." }
        members[i] = members[i].copy(role = role)
        sync(id)
    }

    override suspend fun removeMember(id: String, studentId: String) {
        val c = need(id)
        check(c.isStaff) { "Only moderators can remove members." }
        val members = store.membersOf(id)
        val target = members.firstOrNull { it.student.id == studentId } ?: return
        check(!target.isMe) { "Use Leave to remove yourself." }
        check(target.role != CommunityRole.Owner) { "The owner can't be removed." }
        check(target.role != CommunityRole.Moderator || c.role == CommunityRole.Owner) { "Only the owner can remove a moderator." }
        members.remove(target)
        bump(id, -1)
        sync(id)
    }

    override suspend fun getJoinRequests(id: String): List<Student> {
        check(need(id).isStaff) { "Only moderators can see requests." }
        return store.joinRequests[id].orEmpty().toList()
    }

    override suspend fun resolveJoinRequest(id: String, studentId: String, approve: Boolean) {
        check(need(id).isStaff) { "Only moderators can answer requests." }
        val pending = store.joinRequests[id] ?: return
        val s = pending.firstOrNull { it.id == studentId } ?: return
        pending.remove(s)
        if (approve) { store.membersOf(id) += CommunityMember(s, CommunityRole.Member); bump(id, +1); sync(id) }
    }

    override suspend fun getPosts(id: String): List<Post> {
        delay(250)
        val c = need(id)
        if (c.privacy == CommunityPrivacy.Private && !c.isMember) return emptyList()
        return store.posts.filter { (it.audience as? PostAudience.Community)?.id == id }
    }
}

class MockEventRepository(private val store: MockStore = MockStore()) : EventRepository {
    private val now get() = System.currentTimeMillis()

    /** RSVP state, counts and the time-derived display fields. Counts include your own RSVP. */
    private fun present(e: CampusEvent): CampusEvent {
        val state = store.rsvps[e.id] ?: RsvpState.None
        val (month, day, label) = eventDateParts(e.startMillis)
        return e.copy(
            month = month, day = day, timeLabel = label, isPast = now >= e.endMillis, rsvp = state,
            going = e.going + if (state == RsvpState.Going) 1 else 0,
            interested = e.interested + if (state == RsvpState.Interested) 1 else 0,
        )
    }

    /** Auto-expiry: an event is deleted [EVENT_RETENTION_MILLIS] after it ends, with its RSVPs. A real server does this with a TTL job. */
    private fun purge() {
        val t = now
        store.events.removeAll { t > it.event.expiresAtMillis }
        store.rsvps.keys.retainAll(store.events.map { it.event.id }.toSet())
    }

    private fun find(id: String): EventDetails? { purge(); return store.events.firstOrNull { it.event.id == id } }
    private fun replace(d: EventDetails) { val i = store.events.indexOfFirst { it.event.id == d.event.id }; if (i >= 0) store.events[i] = d }

    override suspend fun getEvents(): List<CampusEvent> {
        delay(350)
        purge()
        return store.events.map { present(it.event) }.sortedBy { it.startMillis }
    }

    override suspend fun getEvent(id: String): EventDetails? {
        delay(250)
        return find(id)?.let { it.copy(event = present(it.event)) }
    }

    override suspend fun setRsvp(eventId: String, state: RsvpState) {
        val d = find(eventId) ?: throw IllegalArgumentException("Event not found")
        val e = present(d.event)
        check(!e.cancelled) { "This event was cancelled." }
        check(!e.isPast) { "This event has ended." }
        if (state == RsvpState.Going && e.rsvp != RsvpState.Going) {
            check(d.capacity == null || e.going < d.capacity) { "This event is full." }
        }
        store.rsvps[eventId] = state
    }

    private fun validate(draft: NewEvent, creating: Boolean, oldStart: Long = 0L) {
        val t = now
        require(draft.title.trim().length in 3..80) { "Title must be 3 to 80 characters" }
        require(draft.description.trim().length <= 1000) { "Description is too long" }
        require(draft.category in EventCategoryOptions) { "Pick a category" }
        require(draft.venue.trim().length in 2..80) { "Venue must be 2 to 80 characters" }
        require(draft.endMillis > draft.startMillis) { "The event must end after it starts" }
        require(draft.endMillis - draft.startMillis <= 48 * 3_600_000L) { "Events can last up to 48 hours" }
        if (creating || draft.startMillis != oldStart) require(draft.startMillis >= t + 5 * 60_000L) { "Start at least 5 minutes from now" }
        require(draft.startMillis <= t + 365L * 24 * 3_600_000L) { "Events can be up to a year ahead" }
        draft.capacity?.let { require(it in 1..5000) { "Places must be 1 to 5000" } }
        require(draft.accent in 0..5) { "Invalid colour" }
        require((draft.latitude == null) == (draft.longitude == null)) { "Invalid venue position" }
        draft.latitude?.let { require(it in -90.0..90.0) { "Invalid venue position" } }
        draft.longitude?.let { require(it in -180.0..180.0) { "Invalid venue position" } }
    }

    override suspend fun createEvent(draft: NewEvent): String {
        delay(400)
        purge()
        validate(draft, creating = true)
        val community = draft.hostCommunityId?.let { id ->
            store.communities.firstOrNull { it.id == id }?.also { check(it.isStaff) { "Only moderators can host events for ${it.name}." } }
                ?: throw IllegalArgumentException("Community not found")
        }
        check(store.events.count { it.event.mine && !it.event.cancelled && it.event.endMillis > now } < 5) { "You can have up to 5 upcoming events at a time." }
        val id = "ev${System.nanoTime()}"
        val host = community?.name ?: MockData.aisha.name
        val event = CampusEvent(
            id = id, title = draft.title.trim(), month = "", day = "", timeLabel = "", location = draft.venue.trim(),
            going = 0, interested = 0, category = draft.category, accent = draft.accent,
            startMillis = draft.startMillis, endMillis = draft.endMillis, hostName = host, hostCommunityId = community?.id, mine = true,
        )
        store.events.add(EventDetails(event, draft.description.trim(), host, community?.accent ?: draft.accent, emptyList(), draft.capacity, draft.latitude, draft.longitude))
        store.rsvps[id] = RsvpState.Going // hosts are going
        return id
    }

    override suspend fun updateEvent(id: String, draft: NewEvent) {
        delay(300)
        val d = find(id) ?: throw IllegalArgumentException("Event not found")
        val e = present(d.event)
        check(e.mine) { "Only the host can edit this event." }
        check(!e.isPast && !e.cancelled) { "This event can't be edited any more." }
        validate(draft, creating = false, oldStart = d.event.startMillis)
        draft.capacity?.let { require(it >= e.going) { "Places can't be fewer than the ${e.going} already going" } }
        replace(d.copy(
            event = d.event.copy(
                title = draft.title.trim(), location = draft.venue.trim(), category = draft.category, accent = draft.accent,
                startMillis = draft.startMillis, endMillis = draft.endMillis,
            ),
            description = draft.description.trim(), capacity = draft.capacity, latitude = draft.latitude, longitude = draft.longitude,
        ))
    }

    override suspend fun cancelEvent(id: String) {
        val d = find(id) ?: throw IllegalArgumentException("Event not found")
        check(d.event.mine) { "Only the host can cancel this event." }
        check(now < d.event.endMillis && !d.event.cancelled) { "This event can't be cancelled." }
        replace(d.copy(event = d.event.copy(cancelled = true)))
    }

    override suspend fun deleteEvent(id: String) {
        val d = find(id) ?: throw IllegalArgumentException("Event not found")
        check(d.event.mine) { "Only the host can delete this event." }
        check(d.event.going + d.event.interested == 0) { "People have RSVPed. Cancel the event so they're told." }
        store.events.removeAll { it.event.id == id }
        store.rsvps.remove(id)
    }

    override suspend fun getVenues(): List<Venue> = listOf(
        Venue("Student Center", -1.2796, 36.8170), Venue("Main Field", -1.2803, 36.8160), Venue("Tech Lab 3", -1.2789, 36.8174),
        Venue("Auditorium", -1.2792, 36.8162), Venue("Main Hall", -1.2799, 36.8165), Venue("Sports Complex", -1.2810, 36.8180),
        Venue("Library Lawn", -1.2786, 36.8168),
    )
}

class MockProfileRepository(
    private val store: MockStore = MockStore(),
    private val local: ProfileLocalStore = InMemoryProfileLocalStore(),
    /** The signed-in account, when there is one. Its details replace the sample student's. */
    private val account: () -> AuthUser? = { null },
    /** Saves profile edits to the real account; false means the server refused or could not be reached. */
    private val saveAccount: suspend (ProfileEdit) -> Boolean = { true },
) : ProfileRepository {
    override suspend fun getMyProfile(): MyProfile {
        val live = account()
        MockData.applyAccount(live)
        val base = MockData.me
        val e = local.edits.first()
        // With a real account the name, course, interests and bio come from it; only photos are still kept on this device.
        val student = if (live != null) base.student else base.student.copy(
            name = e.name ?: base.student.name,
            course = e.course ?: base.student.course,
            year = e.year ?: base.student.year,
            interests = e.interests ?: base.student.interests,
        )
        return base.copy(
            student = student, bio = if (live != null) base.bio else e.bio ?: base.bio, avatarPath = e.avatarPath, coverPath = e.coverPath, photos = e.photos, discoverPhotoPath = e.discoverPhotoPath,
            posts = base.posts + store.posts.count { it.author.id == MockData.ME_ID && it.id.startsWith("p") && it.timeAgo == "now" },
            savedPosts = store.saved.size,
            following = store.following.size,
        )
    }

    override suspend fun updateProfile(update: ProfileUpdate) {
        val old = local.edits.first()
        if (account() != null) {
            check(saveAccount(ProfileEdit(update.name, update.course, update.year, update.interests, update.bio))) { "Profile not saved" }
            MockData.applyAccount(account())
            local.update { it.copy(avatarPath = update.avatarPath, coverPath = update.coverPath) }
        } else {
            local.update {
                it.copy(
                    name = update.name.trim(), bio = update.bio.trim(), course = update.course.trim(), year = update.year,
                    interests = update.interests, avatarPath = update.avatarPath, coverPath = update.coverPath,
                )
            }
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

class MockGroupRepository(private val store: MockStore = MockStore()) : GroupRepository {
    private fun need(chatId: String): GroupInfo = store.group(chatId) ?: throw IllegalArgumentException("Unknown group")

    private fun save(info: GroupInfo) { store.groups[info.chatId] = info }

    override suspend fun getGroup(chatId: String): GroupInfo? {
        delay(250)
        val g = store.group(chatId) ?: return null
        if (g.members.none { it.isMe }) return null
        return g.copy(muted = chatId in store.mutedChats)
    }

    override suspend fun updateInfo(chatId: String, name: String, description: String) {
        val g = need(chatId)
        check(g.canEditInfo) { "Only admins can edit this group." }
        val n = name.trim()
        require(n.length in 1..40) { "Name must be 1 to 40 characters" }
        require(description.trim().length <= 200) { "Description is too long" }
        save(g.copy(name = n, description = description.trim()))
        store.renameChat(chatId, n)
    }

    override suspend fun setPhoto(chatId: String, path: String?) {
        val g = need(chatId)
        check(g.canEditInfo) { "Only admins can edit this group." }
        if (g.photoPath != path) PhotoStore.delete(g.photoPath)
        save(g.copy(photoPath = path))
    }

    override suspend fun getAddable(chatId: String): List<Student> {
        val g = need(chatId)
        check(g.canAddMembers) { "Only admins can add members." }
        // Community accounts (like Tech Club) are not people and can't join chats.
        return MockData.allStudents.filter { s -> s.id != MockData.tech.id && g.members.none { it.student.id == s.id } }
    }

    override suspend fun addMembers(chatId: String, studentIds: List<String>) {
        val g = need(chatId)
        check(g.canAddMembers) { "Only admins can add members." }
        val toAdd = studentIds.distinct().mapNotNull { id -> MockData.allStudents.firstOrNull { it.id == id && it.id != MockData.tech.id } }
            .filter { s -> g.members.none { it.student.id == s.id } }
        require(g.members.size + toAdd.size <= 256) { "Groups can have up to 256 members" }
        save(g.copy(members = g.members + toAdd.map { GroupMember(it, GroupRole.Member) }))
    }

    override suspend fun removeMember(chatId: String, studentId: String) {
        val g = need(chatId)
        check(g.iAmAdmin) { "Only admins can remove members." }
        val target = g.members.firstOrNull { it.student.id == studentId } ?: return
        check(!target.isMe) { "Use Leave group to remove yourself." }
        check(target.role != GroupRole.Owner) { "The owner can't be removed." }
        check(target.role != GroupRole.Admin || g.myRole == GroupRole.Owner) { "Only the owner can remove an admin." }
        save(g.copy(members = g.members - target))
    }

    override suspend fun setRole(chatId: String, studentId: String, role: GroupRole) {
        val g = need(chatId)
        check(g.myRole == GroupRole.Owner) { "Only the owner can change admins." }
        require(role != GroupRole.Owner) { "Ownership can't be assigned here." }
        val target = g.members.firstOrNull { it.student.id == studentId } ?: return
        check(!target.isMe && target.role != GroupRole.Owner) { "You can't change that role." }
        save(g.copy(members = g.members.map { if (it == target) it.copy(role = role) else it }))
    }

    override suspend fun setPermissions(chatId: String, onlyAdminsCanMessage: Boolean, onlyAdminsCanEditInfo: Boolean, onlyAdminsCanAddMembers: Boolean) {
        val g = need(chatId)
        check(g.iAmAdmin) { "Only admins can change these settings." }
        save(g.copy(onlyAdminsCanMessage = onlyAdminsCanMessage, onlyAdminsCanEditInfo = onlyAdminsCanEditInfo, onlyAdminsCanAddMembers = onlyAdminsCanAddMembers))
    }

    override suspend fun leave(chatId: String) {
        val g = need(chatId)
        var members = g.members.filterNot { it.isMe }
        if (g.myRole == GroupRole.Owner && members.isNotEmpty()) {
            // Ownership passes to the longest-standing admin, otherwise the first member.
            val heir = members.firstOrNull { it.role == GroupRole.Admin } ?: members.first()
            members = members.map { if (it == heir) it.copy(role = GroupRole.Owner) else it }
        }
        save(g.copy(members = members))
        store.archived += chatId
    }

    override suspend fun report(chatId: String, reason: String) = Unit // mock: nothing is sent

    override suspend fun getContacts(): List<Student> {
        delay(250)
        return MockData.allStudents.filter { it.id != MockData.tech.id && it.id != MockData.aisha.id }
    }

    override suspend fun createGroup(name: String, description: String, photoPath: String?, memberIds: List<String>): String {
        delay(400)
        val n = name.trim()
        require(n.length in 1..40) { "Name must be 1 to 40 characters" }
        require(description.trim().length <= 200) { "Description is too long" }
        val members = memberIds.distinct().mapNotNull { id ->
            MockData.allStudents.firstOrNull { it.id == id && it.id != MockData.tech.id && it.id != MockData.aisha.id }
        }
        require(members.isNotEmpty()) { "Add at least one person" }
        require(members.size <= 255) { "Groups can have up to 256 members" }
        val id = "g${System.nanoTime()}"
        store.chats.add(0, Chat(id, n, "", "", isGroup = true))
        store.groups[id] = GroupInfo(
            chatId = id, name = n, description = description.trim(), photoPath = photoPath,
            createdBy = "You", createdLabel = "Created today",
            members = listOf(GroupMember(MockData.aisha, GroupRole.Owner, isMe = true)) + members.map { GroupMember(it, GroupRole.Member) },
        )
        return id
    }
}
