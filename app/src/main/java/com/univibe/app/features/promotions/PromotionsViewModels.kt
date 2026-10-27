package com.univibe.app.features.promotions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.AdvertisingSummary
import com.univibe.app.data.model.Business
import com.univibe.app.data.model.BusinessDraft
import com.univibe.app.data.model.CampaignAnalytics
import com.univibe.app.data.model.CampaignDetail
import com.univibe.app.data.model.CampaignItem
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.NewCampaign
import com.univibe.app.data.model.OrderInfo
import com.univibe.app.data.model.PaymentInfo
import com.univibe.app.data.model.PromoPackage
import com.univibe.app.data.repository.EventRepository
import com.univibe.app.data.repository.PromotionsRepository
import com.univibe.app.data.repository.SponsoredCard
import com.univibe.app.data.repository.SponsoredRepository
import com.univibe.app.data.repository.promotionMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ hub
data class PromotionsHubState(
    val loading: Boolean = true,
    val error: String? = null,
    val summary: AdvertisingSummary? = null,
    val campaigns: List<CampaignItem> = emptyList(),
    val next: String? = null,
    val loadingMore: Boolean = false,
)

class PromotionsHubViewModel(
    private val repository: PromotionsRepository = AppContainer.promotionsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PromotionsHubState())
    val state: StateFlow<PromotionsHubState> = _state.asStateFlow()

    init { load() }

    /** [quiet] keeps what is on screen while it refreshes, so coming back to the screen does not flash. */
    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = null) }
            try {
                val summary = repository.summary()
                val page = repository.campaigns(null)
                _state.update { it.copy(loading = false, error = null, summary = summary, campaigns = page.items, next = page.next) }
            } catch (e: Exception) {
                _state.update { if (quiet && it.summary != null) it else it.copy(loading = false, error = e.promotionMessage()) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.next ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repository.campaigns(cursor)
                _state.update { it.copy(loadingMore = false, campaigns = it.campaigns + page.items, next = page.next) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }
}

// ------------------------------------------------------------------ my businesses
data class MyBusinessesState(
    val loading: Boolean = true,
    val error: String? = null,
    val businesses: List<Business> = emptyList(),
    val busyId: String? = null,
    val notice: String? = null,
)

class MyBusinessesViewModel(
    private val repository: PromotionsRepository = AppContainer.promotionsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MyBusinessesState())
    val state: StateFlow<MyBusinessesState> = _state.asStateFlow()

    init { load() }

    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = null) }
            try {
                _state.update { it.copy(loading = false, error = null, businesses = repository.myBusinesses()) }
            } catch (e: Exception) {
                _state.update { if (quiet && it.businesses.isNotEmpty()) it else it.copy(loading = false, error = e.promotionMessage()) }
            }
        }
    }

    fun submit(id: String) {
        if (_state.value.busyId != null) return
        _state.update { it.copy(busyId = id, notice = null) }
        viewModelScope.launch {
            try {
                repository.submitBusiness(id)
                _state.update { it.copy(busyId = null, notice = "Sent for review. We will tell you when it is decided.") }
                load(quiet = true)
            } catch (e: Exception) {
                _state.update { it.copy(busyId = null, notice = e.promotionMessage()) }
            }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }
}

// ------------------------------------------------------------------ business form
data class BusinessFormState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val name: String = "",
    val description: String = "",
    val category: String = "food",
    val location: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val offerings: String = "",
    val savedId: String? = null,
)

class BusinessFormViewModel(
    private val editId: String?,
    private val repository: PromotionsRepository = AppContainer.promotionsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BusinessFormState(loading = editId != null))
    val state: StateFlow<BusinessFormState> = _state.asStateFlow()

    init {
        if (editId != null) viewModelScope.launch {
            try {
                val b = repository.business(editId)
                _state.update {
                    it.copy(loading = false, name = b.name, description = b.description, category = b.category, location = b.locationText,
                        phone = b.contactPhone, email = b.contactEmail, website = b.websiteUrl, offerings = b.offerings)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.promotionMessage()) }
            }
        }
    }

    fun edit(change: (BusinessFormState) -> BusinessFormState) = _state.update { change(it).copy(error = null) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val problem = businessProblem(s.name, s.description, s.phone, s.email, s.website)
        if (problem != null) { _state.update { it.copy(error = problem) }; return }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val id = repository.saveBusiness(editId, BusinessDraft(s.name.trim(), s.description.trim(), s.category, s.location.trim(), s.phone.trim(), s.email.trim(), s.website.trim(), s.offerings.trim()))
                _state.update { it.copy(saving = false, savedId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.promotionMessage()) }
            }
        }
    }
}

// ------------------------------------------------------------------ business directory
data class DirectoryState(
    val loading: Boolean = true,
    val error: String? = null,
    val category: String? = null,
    val items: List<Business> = emptyList(),
    val next: String? = null,
    val loadingMore: Boolean = false,
    /** The one paid card for the directory, if any. Always labelled and kept apart from the ordinary listings. */
    val sponsored: SponsoredCard? = null,
    val notice: String? = null,
)

class DirectoryViewModel(
    private val repository: PromotionsRepository = AppContainer.promotionsRepository,
    private val sponsoredRepository: SponsoredRepository = AppContainer.sponsoredRepository,
) : ViewModel() {
    private val seen = HashSet<String>()

    private val _state = MutableStateFlow(DirectoryState())
    val state: StateFlow<DirectoryState> = _state.asStateFlow()

    init { load() }

    fun setCategory(category: String?) {
        _state.update { it.copy(category = category) }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val page = repository.directory(_state.value.category, null)
                _state.update { it.copy(loading = false, items = page.items, next = page.next) }
                // An ad is an extra: if it is missing or fails, the directory is unaffected.
                val ad = runCatching { sponsoredRepository.card("business_directory") }.getOrNull()
                _state.update { it.copy(sponsored = ad) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.promotionMessage()) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.next ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repository.directory(s.category, cursor)
                _state.update { it.copy(loadingMore = false, items = it.items + page.items, next = page.next) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }
    fun sponsoredShown() {
        val ad = _state.value.sponsored ?: return
        if (!seen.add(ad.impressionToken)) return
        viewModelScope.launch { runCatching { sponsoredRepository.impression(ad) } }
    }

    fun sponsoredClicked() {
        val ad = _state.value.sponsored ?: return
        viewModelScope.launch { runCatching { sponsoredRepository.click(ad) } }
    }

    fun reportSponsored(reason: String) {
        val ad = _state.value.sponsored ?: return
        _state.update { it.copy(sponsored = null) }
        viewModelScope.launch {
            val sent = runCatching { sponsoredRepository.report(ad, reason) }.isSuccess
            _state.update { it.copy(notice = if (sent) "Thanks. We'll review this ad." else "We couldn't send that report. Try again later.") }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }
}

data class BusinessDetailState(val loading: Boolean = true, val error: String? = null, val business: Business? = null)

class BusinessDetailViewModel(
    private val id: String,
    private val repository: PromotionsRepository = AppContainer.promotionsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BusinessDetailState())
    val state: StateFlow<BusinessDetailState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                _state.update { it.copy(loading = false, business = repository.business(id)) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.promotionMessage()) }
            }
        }
    }
}

// ------------------------------------------------------------------ new promotion
data class NewPromotionState(
    val kind: PromoKind? = null,
    val loadingChoices: Boolean = false,
    val choicesError: String? = null,
    val events: List<CampusEvent> = emptyList(),
    val businesses: List<Business> = emptyList(),
    val packages: List<PromoPackage> = emptyList(),
    val eventId: String? = null,
    val businessId: String? = null,
    val packageId: String? = null,
    val placements: Set<String> = emptySet(),
    val headline: String = "",
    val body: String = "",
    val ctaLabel: String = "",
    val ctaUrl: String = "",
    /** A photo or video picked for the card; uploaded when the promotion is created. */
    val mediaPath: String? = null,
    val mediaIsVideo: Boolean = false,
    val start: StartOption = StartOption.Soon,
    val days: Int = 7,
    val submitting: Boolean = false,
    val error: String? = null,
    /** Set when the draft exists, so the screen can move on to it. */
    val createdId: String? = null,
) {
    val selectedPackage: PromoPackage? get() = packages.firstOrNull { it.id == packageId }
    val selectedEvent: CampusEvent? get() = events.firstOrNull { it.id == eventId }
    val selectedBusiness: Business? get() = businesses.firstOrNull { it.id == businessId }
}

class NewPromotionViewModel(
    private val repository: PromotionsRepository = AppContainer.promotionsRepository,
    private val events: EventRepository = AppContainer.eventRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(NewPromotionState())
    val state: StateFlow<NewPromotionState> = _state.asStateFlow()

    fun chooseKind(kind: PromoKind) {
        _state.value = NewPromotionState(kind = kind, loadingChoices = true)
        viewModelScope.launch {
            try {
                val pkgs = repository.packages(kind.packageKind)
                val mine = if (kind.needsEvent) events.getEvents().filter { it.mine && !it.isPast && !it.cancelled && !it.hidden } else emptyList()
                val biz = if (kind.needsBusiness || !kind.needsEvent) repository.myBusinesses().filter { it.status == "approved" } else emptyList()
                _state.update { it.copy(loadingChoices = false, packages = pkgs, events = mine, businesses = biz) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingChoices = false, choicesError = e.promotionMessage()) }
            }
        }
    }

    fun back() { _state.value = NewPromotionState() }

    fun chooseEvent(id: String) = _state.update { s ->
        val title = s.events.firstOrNull { it.id == id }?.title.orEmpty()
        s.copy(eventId = id, headline = if (s.headline.isBlank()) title.take(80) else s.headline, error = null)
    }

    fun chooseBusiness(id: String) = _state.update { s ->
        val name = s.businesses.firstOrNull { it.id == id }?.name.orEmpty()
        s.copy(businessId = id, headline = if (s.headline.isBlank()) name.take(80) else s.headline, error = null)
    }

    fun choosePackage(p: PromoPackage) = _state.update {
        // Start with every place the package covers; the person can switch some off.
        it.copy(packageId = p.id, placements = p.placements.toSet(), days = lengthOptions(p.durationDays).last().coerceAtMost(7).coerceAtMost(p.durationDays), error = null)
    }

    fun togglePlacement(code: String) = _state.update { s ->
        val allowed = s.selectedPackage?.placements.orEmpty()
        if (code !in allowed) s else s.copy(placements = if (code in s.placements) s.placements - code else s.placements + code, error = null)
    }

    fun edit(change: (NewPromotionState) -> NewPromotionState) = _state.update { change(it).copy(error = null) }

    /** Creates the draft, then asks the server to price and open the order. The price is never sent from here. */
    fun submit() {
        val s = _state.value
        val kind = s.kind ?: return
        if (s.submitting) return
        val pkg = s.selectedPackage
        val problem = when {
            kind.needsEvent && s.eventId == null -> "Choose the event to promote."
            kind.needsBusiness && s.businessId == null -> "Choose the business to promote."
            pkg == null -> "Choose a package."
            s.placements.isEmpty() -> "Choose at least one place to show it."
            else -> campaignProblem(s.headline, s.body, s.ctaLabel, s.ctaUrl)
        }
        if (problem != null) { _state.update { it.copy(error = problem) }; return }
        val start = clock() + s.start.offsetMillis
        val end = endMillis(start, s.days.coerceAtMost(pkg!!.durationDays), s.selectedEvent?.endMillis?.takeIf { it > 0 })
        val schedule = scheduleProblem(start, end)
        if (schedule != null) { _state.update { it.copy(error = schedule) }; return }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val id = repository.createCampaign(
                    NewCampaign(
                        kind = kind.serverKind, packageId = pkg.id, eventId = s.eventId, businessId = s.businessId,
                        headline = s.headline.trim(), body = s.body.trim(), ctaLabel = s.ctaLabel.trim().ifEmpty { null }, ctaUrl = s.ctaUrl.trim().ifEmpty { null },
                        placements = pkg.placements.filter { it in s.placements }, startsAtMillis = start, endsAtMillis = end,
                        mediaPath = s.mediaPath, mediaIsVideo = s.mediaIsVideo,
                    ),
                )
                // Opening the order can fail (for example payments are not switched on yet). The draft is kept either way,
                // and the promotion screen offers "Continue to payment" again.
                runCatching { repository.checkout(id) }
                _state.update { it.copy(submitting = false, createdId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, error = e.promotionMessage()) }
            }
        }
    }
}

// ------------------------------------------------------------------ one campaign
data class CampaignDetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: CampaignDetail? = null,
    val analytics: CampaignAnalytics? = null,
    val pay: PaymentInfo? = null,
    val receipt: String = "",
    val busy: Boolean = false,
    val notice: String? = null,
)

class CampaignDetailViewModel(
    private val id: String,
    private val repository: PromotionsRepository = AppContainer.promotionsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CampaignDetailState())
    val state: StateFlow<CampaignDetailState> = _state.asStateFlow()

    init { load() }

    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = null) }
            try {
                val d = repository.campaign(id)
                // Figures and payment details are extras: if they fail the rest of the screen still works.
                val analytics = if (d.status == "draft") null else runCatching { repository.analytics(id) }.getOrNull()
                val pay = if (d.status == "pending_payment") runCatching { repository.paymentInfo() }.getOrNull() else null
                _state.update { it.copy(loading = false, error = null, detail = d, analytics = analytics, pay = pay) }
            } catch (e: Exception) {
                _state.update { if (quiet && it.detail != null) it else it.copy(loading = false, error = e.promotionMessage()) }
            }
        }
    }

    fun setReceipt(text: String) = _state.update { it.copy(receipt = text.take(30), notice = null) }
    fun noticeShown() = _state.update { it.copy(notice = null) }

    /** For a draft: asks the server to price it and open the order. */
    fun continueToPayment() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                repository.checkout(id)
                _state.update { it.copy(busy = false) }
                load(quiet = true)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, notice = e.promotionMessage()) }
            }
        }
    }

    /** "I paid." Only records the receipt for the team to check; nothing is marked paid by this. */
    fun submitReceipt(order: OrderInfo) {
        val s = _state.value
        if (s.busy) return
        val code = normalizeReceipt(s.receipt)
        if (code == null) { _state.update { it.copy(notice = "That does not look like an M-Pesa receipt code. It has 10 letters and numbers.") }; return }
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                repository.submitReceipt(order.id, code)
                _state.update { it.copy(busy = false, receipt = "", notice = "Thanks. We will check your payment and tell you when it is confirmed.") }
                load(quiet = true)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, notice = e.promotionMessage()) }
            }
        }
    }

    fun control(action: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                repository.control(id, action)
                _state.update { it.copy(busy = false) }
                load(quiet = true)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, notice = e.promotionMessage()) }
            }
        }
    }
}
