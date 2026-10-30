package com.univibe.app.features.premium

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.BillingItem
import com.univibe.app.data.model.PremiumPlan
import com.univibe.app.features.promotions.StatusChip
import com.univibe.app.features.promotions.Tone
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

private val FAQ = listOf(
    "Does it renew by itself?" to "No. Each purchase covers one period. Buy again before it ends to keep going; the days you have left are added on.",
    "When does Premium start?" to "After UniVibe confirms your M-Pesa payment. Tapping Subscribe or sending a receipt code does not start it.",
    "Do I lose anything on Free?" to "No. Messaging, posts, communities, events and your profile stay free.",
    "How do I cancel?" to "There is nothing to cancel: it will not charge you again. Your access simply ends on the date shown.",
    "Wrong payment or a problem?" to "Contact support with your order reference. Refunds are reviewed by staff.",
)

@Composable
fun PremiumScreen(onBack: () -> Unit, onCustomizeProfile: () -> Unit = {}, viewModel: PremiumViewModel = viewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load(quiet = true) }
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("UniVibe Premium", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        val overview = s.overview
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 3)
            s.error != null -> ErrorState(s.error!!, { viewModel.load() })
            overview == null -> Unit
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "status") { StatusCard(overview.status.active, overview.status.planName, overview.status.daysLeft) }
                if (overview.entitlements.any { it == "premium.profile_themes" || it == "premium.profile_frames" }) {
                    item(key = "customize") { VibeButton("Customise your profile", onCustomizeProfile, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary) }
                }
                s.notice?.let { n -> item(key = "notice") { Text(n, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }

                val checkout = s.checkout
                if (checkout != null) {
                    item(key = "pay") {
                        VibeCard(Modifier.fillMaxWidth()) {
                            Text("Pay ${checkout.order.amount}", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(Spacing.xs))
                            Text("Order ${checkout.order.reference}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.sm))
                            if (s.receiptSent) {
                                Text("We received your receipt and are checking it. Premium starts once the payment is confirmed. You do not need to do anything else.", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(Spacing.sm))
                                VibeButton("Done", viewModel::closeCheckout, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary)
                            } else {
                                val pay = checkout.pay
                                Text("1. Send ${checkout.order.amount} by M-Pesa to ${pay.method} ${pay.number}${if (pay.accountName.isNotBlank()) " (${pay.accountName})" else ""}. Use ${checkout.order.reference} as the reference where asked.", style = MaterialTheme.typography.bodyMedium)
                                if (pay.instructions.isNotBlank()) { Spacer(Modifier.height(Spacing.xs)); Text(pay.instructions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Spacer(Modifier.height(Spacing.sm))
                                Text("2. Enter the receipt code from the M-Pesa message. This only tells us you paid; Premium starts after we confirm it.", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(Spacing.sm))
                                VibeTextField(s.receipt, viewModel::setReceipt, Modifier.fillMaxWidth(), label = "Receipt code", placeholder = "For example QGH7ABC123", capitalize = false)
                                Spacer(Modifier.height(Spacing.sm))
                                VibeButton("I have paid", viewModel::submitReceipt, Modifier.fillMaxWidth(), loading = s.busy, enabled = s.receipt.isNotBlank())
                                Spacer(Modifier.height(Spacing.xs))
                                VibeButton("Not now", viewModel::closeCheckout, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary, enabled = !s.busy)
                            }
                        }
                    }
                } else if (overview.plans.isEmpty()) {
                    item(key = "none") {
                        EmptyState(VibeIcons.Sparkle, "Premium is not available yet", "Nothing is on sale right now. Everything free stays free, and we will tell you when Premium opens.")
                    }
                } else {
                    item(key = "plans-title") { SectionTitle("Choose a plan") }
                    items(overview.plans, key = { it.id }) { p -> PlanCard(p, p.id == s.selectedPlanId) { viewModel.select(p.id) } }
                    item(key = "subscribe") {
                        val chosen = overview.plans.firstOrNull { it.id == s.selectedPlanId }
                        Column {
                            VibeButton(if (chosen != null) "Subscribe for ${chosen.price}" else "Subscribe", viewModel::subscribe, Modifier.fillMaxWidth(), loading = s.busy, enabled = chosen != null && overview.payment.enabled)
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                if (overview.payment.enabled) "One purchase covers one period and does not renew by itself. Premium starts after your payment is confirmed."
                                else "Payments are not open yet, so you cannot subscribe right now.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val rows = comparisonRows(overview.plans)
                    if (rows.isNotEmpty()) {
                        item(key = "compare-title") { SectionTitle("Free and Premium") }
                        item(key = "compare") {
                            VibeCard(Modifier.fillMaxWidth()) {
                                Text("Free: messaging, posts, communities, events and your profile.", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(Spacing.sm))
                                Text("Premium adds:", style = MaterialTheme.typography.labelLarge)
                                rows.forEach { r ->
                                    Spacer(Modifier.height(Spacing.xs))
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text("\u2713  ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                        Column { Text(r.title, style = MaterialTheme.typography.bodyMedium); Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                    }
                }

                if (s.history.isNotEmpty()) {
                    item(key = "billing-title") { SectionTitle("Billing history") }
                    items(s.history, key = { "b-" + it.id }) { b -> BillingRow(b) { viewModel.resume(b) } }
                    if (s.historyNext != null) item(key = "more") { VibeButton("Show more", viewModel::loadMoreHistory, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary) }
                }

                item(key = "faq-title") { SectionTitle("Questions") }
                items(FAQ, key = { it.first }) { (q, a) ->
                    VibeCard(Modifier.fillMaxWidth()) {
                        Text(q, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(a, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.xs))

@Composable
private fun StatusCard(active: Boolean, planName: String, daysLeft: Int) {
    VibeCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(if (active) "You have Premium" else "You are on Free", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusChip(if (active) daysLeftLabel(daysLeft) else "Free", if (active) Tone.Good else Tone.Neutral)
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            if (active) "$planName. It will not renew by itself; it ends when the days run out."
            else "Everything social is free. Premium adds extras on top.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlanCard(p: PremiumPlan, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp), color = if (selected) colors.primaryContainer else colors.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(p.price, style = MaterialTheme.typography.titleMedium)
            }
            Text("for ${periodLabel(p.periodDays)}, no automatic renewal", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (p.description.isNotBlank()) { Spacer(Modifier.height(Spacing.xs)); Text(p.description, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun BillingRow(b: BillingItem, onResume: () -> Unit) {
    VibeCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.planName, style = MaterialTheme.typography.bodyMedium)
                Text("${b.amount}  -  ${b.reference}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(billingStatusLabel(b.status), when (b.status) { "paid" -> Tone.Good; "pending_payment" -> Tone.Warn; "refunded", "expired", "cancelled" -> Tone.Neutral; else -> Tone.Neutral })
        }
        if (b.status == "pending_payment") {
            Spacer(Modifier.height(Spacing.xs))
            VibeButton("Enter receipt code", onResume, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary)
        }
    }
}
