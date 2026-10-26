package com.univibe.app.features.promotions

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.Business
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
import com.univibe.app.ui.components.openSponsoredLink
import com.univibe.app.ui.theme.Spacing

private fun businessTone(status: String?): Tone = when (status) {
    "approved" -> Tone.Good
    "pending_review", "draft" -> Tone.Warn
    "rejected", "suspended" -> Tone.Bad
    else -> Tone.Neutral
}

private fun businessStatusLabel(status: String?): String = when (status) {
    "draft" -> "Draft"
    "pending_review" -> "In review"
    "approved" -> "Live"
    "rejected" -> "Needs changes"
    "suspended" -> "Suspended"
    else -> ""
}

// ------------------------------------------------------------------ my businesses
@Composable
fun MyBusinessesScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: MyBusinessesViewModel = viewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load(quiet = true) }
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("My businesses", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            s.error != null -> ErrorState(s.error!!, { viewModel.load() })
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
                item(key = "add") { VibeButton("Add a business", onAdd, Modifier.fillMaxWidth(), leadingIcon = VibeIcons.Add) }
                if (s.businesses.isEmpty()) item(key = "empty") {
                    EmptyState(VibeIcons.Badge, "No businesses yet", "Add a business to be listed in the campus directory. A listing is free. Promotions are optional extras.")
                }
                items(s.businesses, key = { it.id }) { b ->
                    VibeCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(Spacing.sm))
                            StatusChip(businessStatusLabel(b.status), businessTone(b.status))
                        }
                        Text(categoryLabel(b.category) + if (b.verified) "  -  Verified" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!b.reviewNote.isNullOrBlank() && (b.status == "rejected" || b.status == "suspended")) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(b.reviewNote, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            if (b.status != "suspended") VibeButton("Edit", { onEdit(b.id) }, style = VibeButtonStyle.Secondary, compact = true)
                            if (b.status == "draft" || b.status == "rejected") VibeButton("Send for review", { viewModel.submit(b.id) }, compact = true, loading = s.busyId == b.id)
                        }
                        if (b.status == "approved") Text("Editing a live listing sends it back for review.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ add or edit a business
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BusinessFormScreen(editId: String?, onBack: () -> Unit, onDone: () -> Unit) {
    val vm: BusinessFormViewModel = keyedViewModel("business-form-${editId ?: "new"}") { BusinessFormViewModel(editId) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.savedId) { if (s.savedId != null) onDone() }
    Column(Modifier.fillMaxSize()) {
        VibeTopBar(if (editId == null) "Add a business" else "Edit business", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        if (s.loading) { LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 3); return@Column }
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { VibeTextField(s.name, { v -> vm.edit { it.copy(name = v.take(80)) } }, label = "Business name") }
            item { Text("Category", style = MaterialTheme.typography.labelLarge) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    BUSINESS_CATEGORIES.forEach { (code, label) ->
                        FilterChip(selected = s.category == code, onClick = { vm.edit { it.copy(category = code) } }, label = { Text(label) })
                    }
                }
            }
            item {
                VibeTextField(
                    s.description, { v -> vm.edit { it.copy(description = v.take(1000)) } }, label = "About the business",
                    singleLine = false, minLines = 3, maxLines = 8, supportingText = "${s.description.length}/1000",
                )
            }
            item {
                VibeTextField(
                    s.offerings, { v -> vm.edit { it.copy(offerings = v.take(1000)) } }, label = "Products or services",
                    singleLine = false, minLines = 2, maxLines = 6,
                )
            }
            item { VibeTextField(s.location, { v -> vm.edit { it.copy(location = v.take(160)) } }, label = "Where to find you (optional)") }
            item {
                VibeTextField(
                    s.phone, { v -> vm.edit { it.copy(phone = v.take(20)) } }, label = "Phone (optional)", capitalize = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
            item {
                VibeTextField(
                    s.email, { v -> vm.edit { it.copy(email = v.take(254)) } }, label = "Email (optional)", capitalize = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
            }
            item {
                VibeTextField(
                    s.website, { v -> vm.edit { it.copy(website = v.take(500)) } }, label = "Website (optional)", placeholder = "https://", capitalize = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            item {
                Text(
                    "Students will see this information once the listing is approved. A listing is free.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            s.error?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            item { VibeButton("Save", vm::save, Modifier.fillMaxWidth(), loading = s.saving) }
        }
    }
}

// ------------------------------------------------------------------ directory
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BusinessDirectoryScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: DirectoryViewModel = viewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Campus businesses", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            s.notice?.let { msg ->
                item(key = "notice") {
                    VibeCard(Modifier.fillMaxWidth()) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::noticeShown) { Text("Dismiss") }
                    }
                }
            }
            // Paid placement comes first and is labelled; everything below it is ordinary, unpaid listing.
            s.sponsored?.let { ad ->
                item(key = "sponsored") {
                    val opensBusiness = ad.targetType == "business" && ad.targetId != null
                    SponsoredCardView(
                        card = ad, onShown = viewModel::sponsoredShown,
                        onOpen = if (opensBusiness) ({ viewModel.sponsoredClicked(); onOpen(ad.targetId!!) }) else null,
                        onCta = null, onReport = viewModel::reportSponsored,
                    )
                }
            }
            item(key = "chips") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(selected = s.category == null, onClick = { viewModel.setCategory(null) }, label = { Text("All") })
                    BUSINESS_CATEGORIES.forEach { (code, label) ->
                        FilterChip(selected = s.category == code, onClick = { viewModel.setCategory(code) }, label = { Text(label) })
                    }
                }
            }
            when {
                s.loading -> item(key = "loading") { LoadingSkeleton(Modifier.padding(top = Spacing.sm), itemCount = 3) }
                s.error != null -> item(key = "error") { ErrorState(s.error!!, { viewModel.load() }) }
                s.items.isEmpty() -> item(key = "empty") {
                    EmptyState(VibeIcons.Badge, "Nothing here yet", "Businesses run by students on your campus will appear here once they are approved.")
                }
                else -> {
                    items(s.items, key = { it.id }) { b -> BusinessRow(b) { onOpen(b.id) } }
                    if (s.next != null) item(key = "more") {
                        VibeButton("Show more", viewModel::loadMore, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary, loading = s.loadingMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun BusinessRow(b: Business, onClick: () -> Unit) {
    VibeCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (b.verified) StatusChip("Verified", Tone.Good)
        }
        Text(
            categoryLabel(b.category) + if (b.locationText.isNotBlank()) "  -  ${b.locationText}" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (b.description.isNotBlank()) Text(b.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}

// ------------------------------------------------------------------ one business
@Composable
fun BusinessDetailScreen(businessId: String, onBack: () -> Unit) {
    val vm: BusinessDetailViewModel = keyedViewModel("business-$businessId") { BusinessDetailViewModel(businessId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Business", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        val b = s.business
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            b == null -> ErrorState(s.error ?: "We couldn't load this business.", { vm.load() })
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item {
                    Column {
                        Text(b.name, style = MaterialTheme.typography.headlineSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(categoryLabel(b.category))
                            if (b.verified) StatusChip("Verified", Tone.Good)
                        }
                    }
                }
                if (b.description.isNotBlank()) item { Text(b.description, style = MaterialTheme.typography.bodyLarge) }
                if (b.offerings.isNotBlank()) item {
                    VibeCard(Modifier.fillMaxWidth()) {
                        Text("Products and services", style = MaterialTheme.typography.titleSmall)
                        Text(b.offerings, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (b.locationText.isNotBlank()) item {
                    VibeCard(Modifier.fillMaxWidth()) {
                        Text("Where to find them", style = MaterialTheme.typography.titleSmall)
                        Text(b.locationText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (b.contactPhone.isNotBlank()) VibeButton("Call", {
                            val number = b.contactPhone.filter { it.isDigit() || it == '+' }
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, Modifier.weight(1f), style = VibeButtonStyle.Secondary, compact = true)
                        if (b.contactEmail.isNotBlank()) VibeButton("Email", {
                            runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${b.contactEmail}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, Modifier.weight(1f), style = VibeButtonStyle.Secondary, compact = true)
                        if (b.websiteUrl.startsWith("https://")) VibeButton("Website", { openSponsoredLink(context, b.websiteUrl) }, Modifier.weight(1f), style = VibeButtonStyle.Secondary, compact = true)
                    }
                }
            }
        }
    }
}
