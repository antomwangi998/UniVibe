package com.univibe.app.guide

import com.univibe.app.navigation.TabReselect
import com.univibe.app.navigation.TopLevelDestination
import com.univibe.app.ui.components.VibeIcons

const val HOME_TOUR_ID = "home_feed"
const val STORIES_NOTES_TOUR_ID = "stories_notes"
const val DISCOVER_TOUR_ID = "discover"

/** Every tour the app ships. Called once; a new module adds its own `TutorialRegistry.register(...)` here or from its own code. */
fun registerGuideTours() {
    registerNavigationTour()
    registerHomeTour()
    registerStoriesNotesTour()
    registerDiscoverTour()
}

/**
 * Home feed. Points at controls that are on the feed today. Nothing is posted, liked or reported by the tour: the
 * composer is only highlighted, and the post steps point at a real post without touching it.
 */
fun registerHomeTour() {
    val home = TopLevelDestination.Home.route
    TutorialRegistry.register(
        Tutorial(
            id = HOME_TOUR_ID,
            title = "Your Home feed",
            description = "Posting, choosing a feed, and reacting to posts.",
            entryRoute = home,
            onStart = { TabReselect.fire(home) }, // scroll to the top so the composer and filter are on screen
            steps = listOf(
                GuideStep("composer", "home.composer", "Share something", "Write here to post to your campus feed. You choose when to press Post, and your unsent text is kept if you leave.", VibeIcons.Edit),
                GuideStep("attach", "home.composerActions", "Photos, videos, events and polls", "Add a photo or video, announce an event, or turn your post into a poll. Nothing is attached until you pick it.", VibeIcons.Photo),
                GuideStep("filter", "home.filter", "Choose your feed", "Switch between For you and Following. The campus pill next to it sets which universities you see.", VibeIcons.Filter),
                GuideStep("actions", "post.actions", "React to a post", "Comment, repost, like, save and share from the row under each post. The number next to Views counts how many people saw it.", VibeIcons.Comment),
                GuideStep("more", "post.more", "More options", "Open this to report a post, or to edit or delete your own. Tapping a name or photo opens that person's profile, where you can follow them.", VibeIcons.More),
                GuideStep(
                    "done", null, "That's the Home feed",
                    "If your feed looks empty, follow students and communities from Discover and it will fill up. Posts load as you scroll, and you can pull down to refresh.",
                    VibeIcons.Check, kind = StepKind.Completion, ctaLabel = "Got it",
                ),
            ),
        ),
    )
}

/** Stories and Notes live in Chats (the camera in the top bar and the row under the search field). */
fun registerStoriesNotesTour() {
    val chats = TopLevelDestination.Chats.route
    TutorialRegistry.register(
        Tutorial(
            id = STORIES_NOTES_TOUR_ID,
            title = "Stories and notes",
            description = "Share a story or a short note, and choose who sees it.",
            entryRoute = chats,
            steps = listOf(
                GuideStep("camera", "chats.camera", "Add to your story", "Take or choose a photo, or write a text story. Before you share, you choose who can see it: Campus or just your Followers.", VibeIcons.Camera, shape = GuideShape.Circle),
                GuideStep("note", "chats.myNote", "Your note", "Tap your photo to write a short note. You pick who can see it (Campus or Followers), it disappears on its own after the time shown while you write, and editing it starts that time again. If you have a story running, this also lets you view or manage it.", VibeIcons.Edit, shape = GuideShape.Circle),
                GuideStep("row", "chats.notes", "Other people's notes and stories", "A coloured ring around someone's photo means they have a story: tap the photo to watch it. The bubble above a photo is their note: tap it to read it.", VibeIcons.Feed),
                GuideStep(
                    "done", null, "You're set",
                    "Open \"Manage your stories\" from your photo any time to review or remove what you've shared. Only the people you chose can see each story or note.",
                    VibeIcons.Check, kind = StepKind.Completion, ctaLabel = "Got it",
                ),
            ),
        ),
    )
}

fun registerDiscoverTour() {
    val discover = TopLevelDestination.Discover.route
    TutorialRegistry.register(
        Tutorial(
            id = DISCOVER_TOUR_ID,
            title = "Discover",
            description = "Search, and browse people, communities, events and more.",
            entryRoute = discover,
            steps = listOf(
                GuideStep("search", "discover.search", "Search", "Look up students and posts. Type a name or a word and the results appear as you go.", VibeIcons.Search, shape = GuideShape.Circle),
                GuideStep("categories", "discover.categories", "Browse by category", "Swipe sideways to see them all: For You, Trending, People, Communities, Photos, Videos, Events and Topics. Each one remembers where you were.", VibeIcons.Feed),
                GuideStep(
                    "done", null, "Find your people",
                    "Under People you can follow students and open their profiles. Under Communities you can read and join groups, and Events shows what's coming up. If a category is empty, try another one or check back later.",
                    VibeIcons.Check, kind = StepKind.Completion, ctaLabel = "Got it",
                ),
            ),
        ),
    )
}
