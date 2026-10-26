package com.univibe.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.repository.SponsoredCard
import com.univibe.app.data.repository.SponsoredRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a tapped sponsored card goes. Provided once by the app shell so no list screen needs extra callbacks. */
class SponsoredTargets(
    val onEvent: (String) -> Unit = {},
    val onPost: (String) -> Unit = {},
    val onBusiness: (String) -> Unit = {},
)

val LocalSponsoredTargets = staticCompositionLocalOf { SponsoredTargets() }

/** The server says a card may be reused for a minute; asking sooner would only repeat the same answer. */
private const val STALE_AFTER_MS = 60_000L

class SponsoredSlotViewModel(
    private val placement: String,
    private val repository: SponsoredRepository = AppContainer.sponsoredRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _card = MutableStateFlow<SponsoredCard?>(null)
    val card: StateFlow<SponsoredCard?> = _card.asStateFlow()
    private val reported = HashSet<String>()
    private var loadedAt = 0L

    init { refreshIfStale() }

    fun refreshIfStale() {
        val now = clock()
        if (loadedAt != 0L && now - loadedAt < STALE_AFTER_MS) return
        loadedAt = now
        viewModelScope.launch {
            // No card, or any failure, simply means no ad. A list never waits for or depends on this.
            val next = runCatching { repository.card(placement) }.getOrNull()
            _card.update { next }
        }
    }

    fun shown() {
        val c = _card.value ?: return
        if (!reported.add(c.impressionToken)) return
        viewModelScope.launch { runCatching { repository.impression(c) } }
    }

    fun clicked() {
        val c = _card.value ?: return
        viewModelScope.launch { runCatching { repository.click(c) } }
    }

    fun report(reason: String) {
        val c = _card.value ?: return
        _card.update { null }
        viewModelScope.launch { runCatching { repository.report(c, reason) } }
    }
}

/** One labelled sponsored card for [placement], or nothing at all when the server has none. Drop it into any list. */
@Composable
fun SponsoredSlot(placement: String, modifier: Modifier = Modifier) {
    val vm: SponsoredSlotViewModel = keyedViewModel("sponsored-$placement") { SponsoredSlotViewModel(placement) }
    val ad by vm.card.collectAsStateWithLifecycle()
    val targets = LocalSponsoredTargets.current
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshIfStale() }
    val card = ad ?: return
    val id = card.targetId
    val open: (() -> Unit)? = if (id == null) null else when (card.targetType) {
        "event" -> ({ vm.clicked(); targets.onEvent(id) })
        "post" -> ({ vm.clicked(); targets.onPost(id) })
        "business" -> ({ vm.clicked(); targets.onBusiness(id) })
        else -> null
    }
    val url = card.ctaUrl
    SponsoredCardView(
        card = card, onShown = vm::shown, onOpen = open,
        onCta = if (url != null) ({ vm.clicked(); openSponsoredLink(context, url) }) else null,
        onReport = vm::report, modifier = modifier,
    )
}
