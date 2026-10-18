package com.univibe.app.features.settings

import com.univibe.app.ui.components.VibeIcons
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.univibe.app.BuildConfig
import com.univibe.app.features.auth.LegalDoc
import com.univibe.app.features.auth.LegalSheet
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.theme.Spacing

/** PLACEHOLDER address: replace with a real, monitored support mailbox before launch. */
private const val SUPPORT_EMAIL = "support@univibe.example"

@Composable
fun HelpScreen(onBack: () -> Unit, onFaq: () -> Unit, onReport: () -> Unit, onGuidelines: () -> Unit, onAbout: () -> Unit) {
    SettingsPage("Help & support", onBack) {
        item {
            SettingsGroup {
                SettingsNavRow(Icons.Outlined.QuestionAnswer, "Frequently asked questions", onFaq)
                RowDivider()
                SettingsNavRow(Icons.Outlined.BugReport, "Report a problem", onReport, subtitle = "Opens your email app")
                RowDivider()
                SettingsNavRow(Icons.Outlined.Gavel, "Community guidelines", onGuidelines)
                RowDivider()
                SettingsNavRow(VibeIcons.Info, "About UniVibe", onAbout)
            }
        }
    }
}

private val Faqs = listOf(
    "Who can join UniVibe?" to "UniVibe is for students of one university. You sign up with an email address and become a verified student once the server confirms your university email.",
    "Why am I not marked as a verified student?" to "Verification only succeeds when your email matches your university. If it doesn't, you can still use the app, but some features may stay limited.",
    "Who can see my profile?" to "Only signed-in students. In Privacy & safety you can switch off Discover visibility, online status and read receipts, and choose who can message you.",
    "How do I block or report someone?" to "Tap ⋯ on one of their posts, then choose Report or Block. Blocked students are hidden from your feed and listed under Privacy & safety ▸ Blocked students.",
    "Does UniVibe track my location?" to "No. UniVibe never collects your precise location.",
    "I forgot my password." to "On the log-in screen choose Forgot password, enter your email and follow the code we send you.",
)

@Composable
fun FaqScreen(onBack: () -> Unit) {
    SettingsPage("FAQ", onBack) {
        item {
            SettingsGroup {
                Faqs.forEachIndexed { index, (q, a) ->
                    if (index > 0) RowDivider()
                    var open by rememberSaveable(q) { mutableStateOf(false) }
                    Column(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }
                            .padding(horizontal = Spacing.md, vertical = Spacing.md).animateContentSize(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Icon(
                                if (open) VibeIcons.ChevronUp else VibeIcons.ChevronDown,
                                contentDescription = if (open) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AnimatedVisibility(open) {
                            Text(a, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportProblemScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var category by rememberSaveable { mutableStateOf("Bug") }
    var message by rememberSaveable { mutableStateOf("") }
    val categories = listOf("Bug", "Safety concern", "Account", "Other")

    SettingsPage("Report a problem", onBack) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("What kind of problem is it?", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    categories.forEach { InterestChip(it, selected = category == it, onClick = { category = it }) }
                }
                VibeTextField(
                    value = message, onValueChange = { message = it.take(500) }, label = "Describe what happened",
                    singleLine = false, minLines = 5, supportingText = "${message.length}/500",
                )
                VibeButton(
                    "Write email", modifier = Modifier.fillMaxWidth(), enabled = message.isNotBlank(),
                    onClick = {
                        val body = buildString {
                            appendLine(message.trim()); appendLine()
                            appendLine("---")
                            appendLine("UniVibe ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                            appendLine("Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}")
                        }
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                            putExtra(Intent.EXTRA_SUBJECT, "[UniVibe] $category")
                            putExtra(Intent.EXTRA_TEXT, body)
                        }
                        try { context.startActivity(intent) } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "No email app found.", Toast.LENGTH_LONG).show()
                        }
                    },
                )
                SettingsNote("This opens your email app with the details filled in. Nothing is sent until you press send. Only the app version and phone model are added, never your password or messages.")
            }
        }
    }
}

@Composable
fun GuidelinesScreen(onBack: () -> Unit) {
    SettingsPage("Community guidelines", onBack) {
        item {
            SettingsGroup {
                listOf(
                    "Be kind" to "Treat every student with respect. No harassment, bullying or hate.",
                    "Be honest" to "Use your real identity. Don't impersonate others or spread false information.",
                    "Keep it safe" to "No threats, no sexual content involving anyone under 18, and nothing illegal.",
                    "Respect privacy" to "Don't share other people's private details or photos without consent.",
                    "No spam" to "Don't flood chats or posts with ads or repeated messages.",
                    "Consequences" to "Content that breaks these rules may be removed and accounts may be limited or banned.",
                ).forEachIndexed { index, (title, body) ->
                    if (index > 0) RowDivider()
                    Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
            }
        }
        item { SettingsNote("Draft guidelines. Have them reviewed by your university before launch.") }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    var legal by remember { mutableStateOf<LegalDoc?>(null) }
    SettingsPage("About", onBack) {
        item {
            SettingsGroup {
                AboutLine("Version", BuildConfig.VERSION_NAME)
                RowDivider()
                AboutLine("Build", BuildConfig.BUILD_TYPE)
                RowDivider()
                AboutLine("Backend", if (BuildConfig.API_BASE_URL.isBlank()) "None (sample data)" else "Configured")
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(Icons.Outlined.Description, "Terms of Service", { legal = LegalDoc.Terms })
                RowDivider()
                SettingsNavRow(Icons.Outlined.Policy, "Privacy Policy", { legal = LegalDoc.Privacy })
                RowDivider()
                SettingsNavRow(Icons.Outlined.Forum, "Open-source licences", { legal = null }, subtitle = "Poppins font, SIL Open Font License 1.1", showChevron = false)
            }
        }
        item { SettingsNote("UniVibe. Connect. Share. Belong.") }
    }
    legal?.let { LegalSheet(it) { legal = null } }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
