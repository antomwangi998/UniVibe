package com.univibe.app.features.communities

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.CommunityCategories
import com.univibe.app.data.model.CommunityPrivacy
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.theme.Spacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommunityFormScreen(editId: String?, onBack: () -> Unit, onDone: (communityId: String) -> Unit) {
    val viewModel = keyedViewModel("community-form-${editId ?: "new"}") { CommunityFormViewModel(editId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    LaunchedEffect(s.doneId) { s.doneId?.let(onDone) }

    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar(
            if (s.editing) "Edit community" else "New community", compactTitle = true,
            navigationIcon = { VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
        )
        when {
            s.notFound -> ErrorState("You can only edit communities you own.", onBack, title = "Not available")
            s.loading -> Box(Modifier.weight(1f))
            else -> {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    VibeTextField(
                        s.name, viewModel::setName, label = "Community name", enabled = !s.editing,
                        isError = s.name.isNotEmpty() && !s.nameOk,
                        supportingText = if (s.editing) "The name can't be changed." else "3 to 40 characters",
                    )
                    VibeTextField(
                        s.description, viewModel::setDescription, label = "What is it about?", singleLine = false, minLines = 3,
                        isError = s.description.isNotEmpty() && !s.descriptionOk,
                        supportingText = "${s.description.trim().length}/300 (at least 10)",
                    )
                    Text("Category", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        CommunityCategories.forEach { c -> InterestChip(c, selected = s.category == c, onClick = { viewModel.setCategory(c) }) }
                    }
                    Text("Colour", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        (0..5).forEach { i ->
                            val picked = s.accent == i
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(accentGradient(i)))
                                    .clickable(role = Role.RadioButton, onClickLabel = "Colour ${i + 1}") { viewModel.setAccent(i) },
                                contentAlignment = Alignment.Center,
                            ) { if (picked) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = Color.White) }
                        }
                    }
                    Text("Who can join?", style = MaterialTheme.typography.titleSmall)
                    PrivacyOption(Icons.Outlined.Public, "Public", "Anyone at your university can join and see posts.", s.privacy == CommunityPrivacy.Public) { viewModel.setPrivacy(CommunityPrivacy.Public) }
                    PrivacyOption(Icons.Outlined.Lock, "Private", "People ask to join and a moderator approves them. Only members see posts and members.", s.privacy == CommunityPrivacy.Private) { viewModel.setPrivacy(CommunityPrivacy.Private) }
                    Text("Rules (optional)", style = MaterialTheme.typography.titleSmall)
                    s.rules.forEachIndexed { i, rule ->
                        Row(verticalAlignment = Alignment.Top) {
                            VibeTextField(
                                rule, { viewModel.setRule(i, it) }, modifier = Modifier.weight(1f), placeholder = "Rule ${i + 1}",
                                isError = rule.length > MAX_RULE_LENGTH, supportingText = if (rule.length > MAX_RULE_LENGTH - 20) "${rule.length}/$MAX_RULE_LENGTH" else null,
                            )
                            IconButton(onClick = { viewModel.removeRule(i) }) { Icon(Icons.Outlined.Close, contentDescription = "Remove rule ${i + 1}") }
                        }
                    }
                    if (s.rules.size < MAX_RULES) {
                        TextButton(onClick = viewModel::addRule) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add a rule")
                        }
                    }
                    Text(
                        "You'll be the owner. You can make moderators, approve requests and remove members. Reports about the community go to moderators and to UniVibe.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(Spacing.sm))
                }
                VibeButton(
                    if (s.editing) "Save changes" else "Create community", viewModel::submit,
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md), enabled = s.canSubmit, loading = s.submitting,
                )
            }
        }
    }
}

@Composable
private fun PrivacyOption(icon: ImageVector, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.primaryContainer.copy(alpha = 0.4f) else colors.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.primary)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = colors.primary)
        }
    }
}
