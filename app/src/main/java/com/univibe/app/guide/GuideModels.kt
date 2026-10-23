package com.univibe.app.guide

import androidx.compose.ui.graphics.vector.ImageVector

enum class GuideShape { RoundedRect, Circle }

/**
 * What a step asks of the person.
 * - [Info]: read it, press Next.
 * - [TapTarget]: tapping the highlighted control continues (the tap is consumed by the guide, so it never triggers the control).
 * - [Action]: the person has to do something real; the screen reports it with [GuideController.actionDone].
 * - [Completion]: the closing card, usually with a button that returns to a start screen.
 */
enum class StepKind { Info, TapTarget, Action, Completion }

data class GuideStep(
    val id: String,
    /** Id used with [guideTarget]. Null (or a target that is not on screen) shows the card in the middle instead. */
    val targetId: String?,
    val title: String,
    val body: String,
    val icon: ImageVector? = null,
    val shape: GuideShape = GuideShape.RoundedRect,
    val kind: StepKind = StepKind.Info,
    /** Opened (as a tab or screen) before the step is shown. */
    val route: String? = null,
    /** Label of the main button, when "Next" is not right. */
    val ctaLabel: String? = null,
)

data class Tutorial(
    val id: String,
    val title: String,
    val description: String,
    /** Where the tour has to start (a top-level tab route or screen route). */
    val entryRoute: String,
    val steps: List<GuideStep>,
    /** A tutorial can hide itself, for example when the feature behind it is off for this person. */
    val isAvailable: () -> Boolean = { true },
    /** Runs after the entry screen is opened, e.g. to scroll a list back to its top so the first controls are on screen. */
    val onStart: () -> Unit = {},
)

sealed interface TourProgress {
    data object NotStarted : TourProgress
    data class InProgress(val step: Int) : TourProgress
    data object Completed : TourProgress
    data object Skipped : TourProgress

    fun encode(): String = when (this) {
        NotStarted -> ""
        is InProgress -> "p:$step"
        Completed -> "done"
        Skipped -> "skip"
    }

    companion object {
        fun decode(raw: String?): TourProgress = when {
            raw.isNullOrEmpty() -> NotStarted
            raw == "done" -> Completed
            raw == "skip" -> Skipped
            raw.startsWith("p:") -> raw.drop(2).toIntOrNull()?.takeIf { it >= 0 }?.let(::InProgress) ?: NotStarted
            else -> NotStarted
        }
    }
}

/**
 * Where tutorials are registered. A module adds its own with [register] (for example from its own initialiser);
 * nothing in the engine has to change. Registering the same id again replaces it.
 */
object TutorialRegistry {
    private val items = LinkedHashMap<String, Tutorial>()

    @Synchronized fun register(tutorial: Tutorial) {
        require(tutorial.steps.isNotEmpty()) { "A tutorial needs at least one step." }
        require(tutorial.steps.map { it.id }.toSet().size == tutorial.steps.size) { "Step ids must be unique within a tutorial." }
        items[tutorial.id] = tutorial
    }

    @Synchronized fun all(): List<Tutorial> = items.values.filter { it.isAvailable() }
    @Synchronized fun get(id: String): Tutorial? = items[id]
}
