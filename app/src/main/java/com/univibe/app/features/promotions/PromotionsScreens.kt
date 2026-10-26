package com.univibe.app.features.promotions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.CampaignAnalytics
import com.univibe.app.data.model.CampaignDetail
import com.univibe.app.data.model.CampaignItem
import com.univibe.app.data.model.OrderInfo
import com.univibe.app.data.model.PaymentInfo
import com.univibe.app.data.model.PromoPackage
import com.univibe.app.data.repository.SponsoredCard
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.SponsoredCardView
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

// ------------------------------------------------------------------ shared pieces
enum class Tone { Neutral, Good, Warn, Bad }

@Composable
fun StatusChip(text: String, tone: Tone = Tone.Neutral) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.Good -> colors.primaryContainer to colors.onPrimaryContainer
        Tone.Warn -> colors.tertiaryContainer to colors.onTertiaryContainer
        Tone.Bad -> colors.errorContainer to colors.onErrorContainer
        Tone.Neutral -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(text, Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

fun campaignTone(status: String): Tone = when (status) {
    "active", "scheduled", "approved", "completed", "refunded" -> Tone.Good
    "pending_payment", "pending_review", "paused", "refund_pending", "draft" -> Tone.Warn
    "rejected", "cancelled" -> Tone.Bad
    else -> Tone.Neutral
}

private fun kindTitle(serverKind: String) = PROMO_KINDS.firstOrNull { it.serverKind == serverKind }?.title ?: serverKind.replace('_', ' ')

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SectionTitle(text: String) =
    Text(text, Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.titleMedium)

/** A card the person picks by tapping; the radio button says so to screen readers too. */
@Composable
private fun ChoiceCard(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    VibeCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) { content() }
        }
    }
}

// ------------------------------------------------------------------ hub
@Composable
fun PromotionsScreen(
    onBack: () -> Unit,
    onNewPromotion: () -> Unit,
    onOpenCampaign: (String) -> Unit,
    onMyBusinesses: () -> Unit,
    viewModel: PromotionsHubViewModel = viewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load(quiet = true) }
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Promotions", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 3)
            s.error != null -> ErrorState(s.error!!, { viewModel.load() })
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                s.summary?.let { sum ->
                    item(key = "summary") {
                        VibeCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Stat(sum.active.toString(), "Running", Modifier.weight(1f))
                                Stat(sum.waiting.toString(), "Waiting", Modifier.weight(1f))
                                Stat(sum.total.toString(), "All time", Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(Spacing.md))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Stat(countLabel(sum.impressions), "Views", Modifier.weight(1f))
                                Stat(countLabel(sum.clicks), "Taps", Modifier.weight(1f))
                                Stat(ctrLabel(sum.ctr), "Tap rate", Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            Muted("Paid ${sum.paid}  -  Refunded ${sum.refunded}")
                        }
                    }
                }
                item(key = "actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        VibeButton("New promotion", onNewPromotion, Modifier.weight(1f), leadingIcon = VibeIcons.Add)
                        VibeButton("My businesses", onMyBusinesses, Modifier.weight(1f), style = VibeButtonStyle.Secondary)
                    }
                }
                item(key = "title") { SectionTitle("Your promotions") }
                if (s.campaigns.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            VibeIcons.Sparkle, "No promotions yet",
                            "Promote an event or a business to reach more students. You pay only for the package you choose.",
                        )
                    }
                }
                items(s.campaigns, key = { it.id }) { c -> CampaignCard(c) { onOpenCampaign(c.id) } }
                if (s.next != null) item(key = "more") {
                    VibeButton("Show more", viewModel::loadMore, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary, loading = s.loadingMore)
                }
            }
        }
    }
}

@Composable
private fun CampaignCard(c: CampaignItem, onClick: () -> Unit) {
    VibeCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.headline, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(Spacing.sm))
            StatusChip(statusLabel(c.status), campaignTone(c.status))
        }
        Muted("${kindTitle(c.kind)}  -  ${dateTimeLabel(c.startsAtMillis)} to ${dateTimeLabel(c.endsAtMillis)}")
        if (c.status != "draft") Muted("${countLabel(c.impressions)} views, ${countLabel(c.clicks)} taps" + (c.amount?.let { "  -  $it" } ?: ""))
    }
}

// ------------------------------------------------------------------ new promotion
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewPromotionScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    onAddBusiness: () -> Unit,
    viewModel: NewPromotionViewModel = viewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val kind = s.kind
    LaunchedEffect(s.createdId) { s.createdId?.let(onCreated) }
    Column(Modifier.fillMaxSize()) {
        VibeTopBar(
            "New promotion", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", { if (kind == null) onBack() else viewModel.back() }) },
        )
        if (kind == null) {
            LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item { Text("What would you like to promote?", style = MaterialTheme.typography.titleMedium) }
                items(PROMO_KINDS, key = { it.serverKind }) { k ->
                    VibeCard(Modifier.fillMaxWidth(), onClick = { viewModel.chooseKind(k) }) {
                        Text(k.title, style = MaterialTheme.typography.titleSmall)
                        Muted(k.subtitle)
                    }
                }
            }
            return@Column
        }
        when {
            s.loadingChoices -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 3)
            s.choicesError != null -> ErrorState(s.choicesError!!, { viewModel.chooseKind(kind) })
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item { Text(kind.title, style = MaterialTheme.typography.titleLarge) }

                // 1. What
                if (kind.needsEvent) {
                    item { SectionTitle("Which event?") }
                    if (s.events.isEmpty()) item {
                        EmptyState(VibeIcons.Events, "No upcoming events", "Only your own published, upcoming events can be promoted. Create one first.")
                    }
                    items(s.events, key = { "e-" + it.id }) { e ->
                        ChoiceCard(s.eventId == e.id, { viewModel.chooseEvent(e.id) }) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall)
                            Muted("${e.day} ${e.month}  -  ${e.timeLabel}  -  ${e.location}")
                        }
                    }
                } else {
                    item { SectionTitle("Which business?") }
                    if (s.businesses.isEmpty()) item {
                        EmptyState(
                            VibeIcons.Badge, "No approved business yet",
                            "Add your business and send it for review. Once it is approved you can promote it.",
                            actionLabel = "Add a business", onAction = onAddBusiness,
                        )
                    }
                    items(s.businesses, key = { "b-" + it.id }) { b ->
                        ChoiceCard(s.businessId == b.id, { viewModel.chooseBusiness(b.id) }) {
                            Text(b.name, style = MaterialTheme.typography.titleSmall)
                            Muted(categoryLabel(b.category) + if (b.locationText.isNotBlank()) "  -  ${b.locationText}" else "")
                        }
                    }
                }

                // 2. Package
                item { SectionTitle("Choose a package") }
                if (s.packages.isEmpty()) item { Muted("No packages are available yet. Please check again later.") }
                items(s.packages, key = { "p-" + it.id }) { p ->
                    ChoiceCard(s.packageId == p.id, { viewModel.choosePackage(p) }) {
                        Row {
                            Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(if (p.priceMinor == 0L) "Free" else p.price, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Muted("Up to ${p.durationDays} day${if (p.durationDays == 1) "" else "s"}  -  " + p.placements.joinToString(", ") { placementLabel(it) })
                        if (p.description.isNotBlank()) Muted(p.description)
                        p.impressionCap?.let { Muted("Up to ${countLabel(it.toLong())} views") }
                    }
                }

                val pkg = s.selectedPackage
                if (pkg != null) {
                    // 3. Where
                    item { SectionTitle("Where it shows") }
                    items(pkg.placements, key = { "pl-$it" }) { code ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = code in s.placements, onCheckedChange = { viewModel.togglePlacement(code) })
                            Text(placementLabel(code), style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    // 4. The ad
                    item { SectionTitle("Your ad") }
                    item {
                        VibeTextField(s.headline, { v -> viewModel.edit { it.copy(headline = v.take(80)) } }, label = "Headline", supportingText = "${s.headline.length}/80")
                    }
                    item {
                        VibeTextField(
                            s.body, { v -> viewModel.edit { it.copy(body = v.take(300)) } }, label = "Description (optional)",
                            singleLine = false, minLines = 2, maxLines = 5, supportingText = "${s.body.length}/300",
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            VibeTextField(s.ctaLabel, { v -> viewModel.edit { it.copy(ctaLabel = v.take(24)) } }, Modifier.weight(1f), label = "Button (optional)", placeholder = "Sign up")
                        }
                    }
                    item {
                        VibeTextField(
                            s.ctaUrl, { v -> viewModel.edit { it.copy(ctaUrl = v.take(500)) } }, label = "Button link (optional)", placeholder = "https://",
                            capitalize = false, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }

                    // 5. When
                    item { SectionTitle("When") }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            StartOption.entries.forEach { o ->
                                FilterChip(selected = s.start == o, onClick = { viewModel.edit { it.copy(start = o) } }, label = { Text(o.label) })
                            }
                        }
                    }
                    item { Muted("How long") }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            lengthOptions(pkg.durationDays).forEach { d ->
                                FilterChip(selected = s.days == d, onClick = { viewModel.edit { it.copy(days = d) } }, label = { Text(if (d == 1) "1 day" else "$d days") })
                            }
                        }
                    }
                    s.selectedEvent?.let { e -> item { Muted("It will stop when ${e.title} ends, if that comes first.") } }

                    // 6. Preview
                    item { SectionTitle("Preview") }
                    item {
                        val cta = s.ctaLabel.trim().takeIf { it.length >= 2 && s.ctaUrl.trim().startsWith("https://") }
                        SponsoredCardView(
                            card = SponsoredCard(
                                campaignId = "preview", advertiser = s.selectedBusiness?.name ?: s.selectedEvent?.hostName?.ifBlank { null } ?: "You",
                                advertiserVerified = s.selectedBusiness?.verified ?: false, headline = s.headline.ifBlank { "Your headline" }, body = s.body,
                                ctaLabel = cta, ctaUrl = if (cta != null) s.ctaUrl.trim() else null, targetType = null, targetId = null,
                                eventLine = s.selectedEvent?.location, impressionToken = "",
                            ),
                            onShown = {}, onOpen = null, onCta = if (cta != null) ({}) else null, onReport = {},
                        )
                    }
                    item { Muted("Every promotion is checked before it runs. You can pause or cancel it any time.") }
                    s.error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
                    item {
                        VibeButton(
                            if (pkg.priceMinor == 0L) "Send for review" else "Continue to payment (${pkg.price})",
                            viewModel::submit, Modifier.fillMaxWidth(), loading = s.submitting,
                        )
                    }
                } else s.error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            }
        }
    }
}

// ------------------------------------------------------------------ one promotion
@Composable
fun CampaignScreen(campaignId: String, onBack: () -> Unit, viewModel: CampaignDetailViewModel = keyedViewModel("campaign-$campaignId") { CampaignDetailViewModel(campaignId) }) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load(quiet = true) }
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Promotion", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        val d = s.detail
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 3)
            d == null -> ErrorState(s.error ?: "We couldn't load this promotion.", { viewModel.load() })
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                s.notice?.let { msg ->
                    item(key = "notice") {
                        VibeCard(Modifier.fillMaxWidth()) {
                            Text(msg, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = viewModel::noticeShown) { Text("Dismiss") }
                        }
                    }
                }
                item(key = "head") {
                    VibeCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.headline, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(Spacing.sm))
                            StatusChip(statusLabel(d.status), campaignTone(d.status))
                        }
                        Muted("${kindTitle(d.kind)}  -  ${dateTimeLabel(d.startsAtMillis)} to ${dateTimeLabel(d.endsAtMillis)}")
                        Muted(d.placements.joinToString(", ") { placementLabel(it) })
                        Spacer(Modifier.height(Spacing.sm))
                        Text(statusExplanation(d.status, d.payment?.status), style = MaterialTheme.typography.bodyMedium)
                        if (d.reviewNote.isNotBlank()) {
                            Spacer(Modifier.height(Spacing.sm))
                            Text("Why it was not approved", style = MaterialTheme.typography.labelLarge)
                            Text(d.reviewNote, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (d.status == "draft") item(key = "continue") {
                    VibeButton("Continue to payment", viewModel::continueToPayment, Modifier.fillMaxWidth(), loading = s.busy)
                }
                if (d.status == "pending_payment" && d.order != null) item(key = "pay") {
                    PaymentCard(d, d.order, s.pay, s.receipt, s.busy, viewModel::setReceipt) { viewModel.submitReceipt(d.order) }
                } else if (d.order != null && d.status != "draft") item(key = "order") { OrderCard(d.order) }
                if (d.status != "draft") s.analytics?.let { a -> item(key = "performance") { PerformanceCard(a) } }
                if (d.history.isNotEmpty()) item(key = "history") {
                    VibeCard(Modifier.fillMaxWidth()) {
                        Text("History", style = MaterialTheme.typography.titleSmall)
                        d.history.asReversed().forEach { h ->
                            Spacer(Modifier.height(Spacing.xs))
                            Text("${statusLabel(h.status)}  -  ${whoLabel(h.by)}", style = MaterialTheme.typography.bodyMedium)
                            Muted(dateTimeLabel(h.atMillis) + if (h.note.isNotBlank() && h.by != "admin") "  -  ${h.note}" else "")
                        }
                    }
                }
                item(key = "controls") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if ("paused" in d.actions) VibeButton("Pause", { confirm = "pause" }, Modifier.weight(1f), style = VibeButtonStyle.Secondary, enabled = !s.busy)
                        if (d.status == "paused" && ("active" in d.actions || "scheduled" in d.actions)) VibeButton("Resume", { viewModel.control("resume") }, Modifier.weight(1f), enabled = !s.busy)
                        if ("cancelled" in d.actions) VibeButton("Cancel", { confirm = "cancel" }, Modifier.weight(1f), style = VibeButtonStyle.Secondary, enabled = !s.busy)
                    }
                }
            }
        }
        when (confirm) {
            "pause" -> ConfirmDialog("Pause this promotion?", "It stops showing right away. You can resume it later.", "Pause", { confirm = null; viewModel.control("pause") }, { confirm = null })
            "cancel" -> ConfirmDialog(
                "Cancel this promotion?",
                "It will stop and cannot be restarted." + if (s.detail?.status in setOf("pending_review", "approved", "scheduled")) " Because it has not run yet, your payment will go to refund review." else "",
                "Cancel promotion", { confirm = null; viewModel.control("cancel") }, { confirm = null }, dismissLabel = "Keep it", destructive = true,
            )
        }
    }
}

private fun methodLabel(method: String) = if (method == "paybill") "Paybill" else "Till number"

@Composable
private fun PaymentCard(
    d: CampaignDetail, order: OrderInfo, pay: PaymentInfo?, receipt: String, busy: Boolean,
    onReceipt: (String) -> Unit, onSubmit: () -> Unit,
) {
    val checking = d.payment?.status == "awaiting_verification"
    VibeCard(Modifier.fillMaxWidth()) {
        Text("Payment", style = MaterialTheme.typography.titleSmall)
        Text(order.amount, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Muted("Order ${order.reference}  -  pay before ${dateTimeLabel(order.expiresAtMillis)}")
        Spacer(Modifier.height(Spacing.sm))
        when {
            checking -> Text("We received your receipt and are checking it. You do not need to do anything else.", style = MaterialTheme.typography.bodyMedium)
            pay == null -> Text("We couldn't load the payment details. Pull back to this screen to try again.", style = MaterialTheme.typography.bodyMedium)
            !pay.enabled -> Text("Payments are not open yet. Please try again later.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text("1. Pay ${order.amount} by M-Pesa to:", style = MaterialTheme.typography.bodyMedium)
                Text("${methodLabel(pay.method)}  ${pay.number}", style = MaterialTheme.typography.titleMedium)
                if (pay.accountName.isNotBlank()) Muted("The name shown should be ${pay.accountName}")
                if (pay.instructions.isNotBlank()) Muted(pay.instructions)
                Spacer(Modifier.height(Spacing.sm))
                Text("2. Enter the receipt code from the M-Pesa message:", style = MaterialTheme.typography.bodyMedium)
                val rejected = d.payment?.takeIf { it.status == "failed" }?.failureReason
                if (rejected != null) Text("Last attempt not accepted: $rejected", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                VibeTextField(receipt, onReceipt, label = "Receipt code", placeholder = "For example QGH7ABC123", capitalize = false)
                Spacer(Modifier.height(Spacing.sm))
                VibeButton("I have paid", onSubmit, Modifier.fillMaxWidth(), loading = busy, enabled = receipt.isNotBlank())
                Muted("Your promotion only starts after we confirm the payment and approve the ad.")
            }
        }
    }
}

@Composable
private fun OrderCard(order: OrderInfo) {
    VibeCard(Modifier.fillMaxWidth()) {
        Text("Order", style = MaterialTheme.typography.titleSmall)
        Text("${order.amount}  -  ${order.reference}", style = MaterialTheme.typography.bodyMedium)
        Muted("Payment: " + when (order.status) {
            "paid" -> "confirmed"
            "refunded" -> "refunded"
            "pending_payment" -> "waiting"
            "expired" -> "expired"
            else -> order.status.replace('_', ' ')
        })
    }
}

@Composable
private fun PerformanceCard(a: CampaignAnalytics) {
    var defs by remember { mutableStateOf(false) }
    VibeCard(Modifier.fillMaxWidth()) {
        Text("Performance", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth()) {
            Stat(countLabel(a.impressions), "Views", Modifier.weight(1f))
            Stat(countLabel(a.clicks), "Taps", Modifier.weight(1f))
            Stat(ctrLabel(a.ctr), "Tap rate", Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.sm))
        Muted("${countLabel(a.reach)} different students saw it" + if (a.reachExact) "" else " (approximate for older dates)")
        a.rsvpAfterClick?.let { Muted("$it student${if (it == 1) "" else "s"} RSVPed to the event after tapping the ad") }
        if (a.daily.any { it.impressions > 0 }) {
            Spacer(Modifier.height(Spacing.sm))
            val top = a.daily.maxOf { it.impressions }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                a.daily.takeLast(30).forEach { p ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight((p.impressions.toFloat() / top).coerceIn(0.04f, 1f))
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                    )
                }
            }
            Muted("Views per day")
        }
        Spacer(Modifier.height(Spacing.sm))
        if (a.running) Text("Showing to students now.", style = MaterialTheme.typography.bodyMedium)
        else Text(
            "Not showing right now" + when {
                a.reasonsNotRunning.isNotEmpty() -> ": " + a.reasonsNotRunning.joinToString(" ")
                a.heldForReview -> ": held for review after student reports."
                else -> "."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        a.percentOfCapUsed?.let { Muted("$it% of the package's views used") }
        TextButton(onClick = { defs = !defs }) { Text(if (defs) "Hide what these mean" else "What these mean") }
        if (defs) a.definitions.forEach { (k, v) -> Muted("${k.replaceFirstChar { it.uppercase() }}: $v") }
    }
}
