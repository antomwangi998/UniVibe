package com.univibe.app.guide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.univibe.app.app.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where the on-screen controls the guide points at are, in window coordinates. Controls report themselves with [guideTarget]. */
object GuideTargets {
    internal val bounds = mutableStateMapOf<String, Rect>()
    fun boundsOf(id: String?): Rect? = id?.let { bounds[it] }
}

/** Marks a control as something a tutorial can point at. Costs nothing when no tour is running. */
fun Modifier.guideTarget(id: String): Modifier = composed {
    DisposableEffect(id) { onDispose { GuideTargets.bounds.remove(id) } }
    onGloballyPositioned { if (it.isAttached) GuideTargets.bounds[id] = it.boundsInWindow() }
}

/**
 * Runs one tour at a time. It never submits anything or taps anything for the person; it only highlights, explains and
 * (when asked) opens a screen. Every state can be left: [exit] keeps the place, [skip] marks it skipped, [finish] completes it.
 */
class GuideController(
    private val store: GuideProgressStore,
    private val scope: CoroutineScope,
    val userId: String,
) {
    var tutorial by mutableStateOf<Tutorial?>(null)
        private set
    var stepIndex by mutableIntStateOf(0)
        private set

    /** Set by the app shell: opens a tab or screen. */
    var navigate: (String) -> Unit = {}

    val step: GuideStep? get() = tutorial?.steps?.getOrNull(stepIndex)
    val isActive: Boolean get() = step != null
    val isFirst: Boolean get() = stepIndex == 0
    val isLast: Boolean get() = tutorial?.let { stepIndex >= it.steps.lastIndex } ?: true

    fun start(t: Tutorial, from: Int = 0) {
        tutorial = t
        stepIndex = from.coerceIn(0, t.steps.lastIndex)
        navigate(t.entryRoute)
        if (from == 0) runCatching { t.onStart() }
        step?.route?.let(navigate)
        save(TourProgress.InProgress(stepIndex))
    }

    /** After the screen was recreated (rotation): shows the same step again without navigating. */
    fun restore(t: Tutorial, at: Int) {
        tutorial = t
        stepIndex = at.coerceIn(0, t.steps.lastIndex)
    }

    fun next() {
        val t = tutorial ?: return
        if (isLast) { finish(); return }
        stepIndex += 1
        t.steps[stepIndex].route?.let(navigate)
        save(TourProgress.InProgress(stepIndex))
    }

    fun back() {
        val t = tutorial ?: return
        if (stepIndex == 0) return
        stepIndex -= 1
        t.steps[stepIndex].route?.let(navigate)
        save(TourProgress.InProgress(stepIndex))
    }

    /** An [StepKind.Action] step was done by the person. Ignored for any other step, so a late callback does nothing. */
    fun actionDone(stepId: String) {
        if (step?.id == stepId && step?.kind == StepKind.Action) next()
    }

    /** Closes the tour but keeps the place, so Help can offer "Continue". */
    fun exit() {
        val t = tutorial ?: return
        save(TourProgress.InProgress(stepIndex), t)
        clear()
    }

    fun skip() {
        val t = tutorial ?: return
        save(TourProgress.Skipped, t)
        clear()
    }

    /** Only reaching the end counts as completing a tutorial; opening it never does. */
    fun finish() {
        val t = tutorial ?: return
        save(TourProgress.Completed, t)
        val closing = step?.route ?: t.entryRoute
        clear()
        navigate(closing)
    }

    private fun clear() { tutorial = null; stepIndex = 0 }

    private fun save(progress: TourProgress, t: Tutorial? = tutorial) {
        val id = t?.id ?: return
        scope.launch { runCatching { store.setProgress(userId, id, progress) } }
    }
}

@Composable
fun rememberGuideController(userId: String): GuideController {
    val scope = rememberCoroutineScope()
    val controller = remember(userId) { GuideController(AppContainer.guideStore, scope, userId) }
    // Survives rotation: the tour comes back on the same step instead of vanishing or staying half-dimmed.
    var savedTour by rememberSaveable { mutableStateOf("") }
    var savedStep by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(controller) {
        if (controller.tutorial == null && savedTour.isNotEmpty()) TutorialRegistry.get(savedTour)?.let { controller.restore(it, savedStep) }
        snapshotFlow { controller.tutorial?.id.orEmpty() to controller.stepIndex }.collect { (id, i) -> savedTour = id; savedStep = i }
    }
    return controller
}
