package com.univibe.app.features.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.BuildConfig
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.navigation.AppRoutes
import com.univibe.app.navigation.SettingsRoutes
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing

/**
 * Campus & community and Legal & About.
 *
 * Campus & community only lists what UniVibe really has: your verified campus (read-only, because the server sets it from
 * your university email), the feed Home opens on, communities, and the safety pages. There is nothing here for alumni,
 * course communities or calendar integration, because none of those exist yet.
 */

private fun FeedFilter.title() = when (this) { FeedFilter.Campus -> "Campus"; FeedFilter.Following -> "Following" }

@Composable
fun CampusScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Campus & community", onBack) {
        item {
            SettingsGroup {
                SettingsNavRow(
                    VibeIcons.Groups, "Campus identity", { onNavigate(SettingsRoutes.CAMPUS_IDENTITY) },
                    subtitle = user?.let { u ->
                        if (u.studentVerified) "${u.universityName}, verified student" else u.universityName
                    },
                )
                RowDivider()
                SettingsNavRow(VibeIcons.Home, "Default feed", { onNavigate(SettingsRoutes.DEFAULT_FEED) }, subtitle = settings.feedFilter.title())
                RowDivider()
                SettingsNavRow(
                    VibeIcons.Document, "Course and year", { onNavigate(AppRoutes.EDIT_PROFILE) },
                    subtitle = user?.let { listOfNotNull(it.course.ifBlank { null }, it.yearOfStudy.ifBlank { null }).joinToString(" · ").ifEmpty { "Not set" } },
                )
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(VibeIcons.Groups, "Communities", { onNavigate(AppRoutes.COMMUNITIES) }, subtitle = "Find, join and manage your communities")
                RowDivider()
                SettingsNavRow(VibeIcons.Bell, "Community notifications", { onNavigate(SettingsRoutes.NOTIFICATIONS_ACTIVITY) })
                RowDivider()
                SettingsNavRow(VibeIcons.Events, "Event notices", { onNavigate(SettingsRoutes.NOTIFICATIONS_EVENTS) })
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(VibeIcons.Block, "Blocked students", { onNavigate(SettingsRoutes.BLOCKED) })
                RowDivider()
                SettingsNavRow(VibeIcons.Document, "Community guidelines", { onNavigate(SettingsRoutes.GUIDELINES) })
                RowDivider()
                SettingsNavRow(VibeIcons.Document, "Account status", { onNavigate(SettingsRoutes.STATUS) }, subtitle = "Restrictions and notices on your account")
            }
        }
        item { SettingsNote("Who can find you in Discover and who can message you are under Privacy & safety.") }
    }
}

@Composable
fun CampusIdentityScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    SettingsPage("Campus identity", onBack) {
        val u = user
        if (u == null) {
            item { SettingsNote("You're signed out.") }
            return@SettingsPage
        }
        item {
            SettingsGroup {
                ValueLine("University", u.universityName)
                RowDivider()
                ValueLine("University email", if (u.emailVerified) "Verified" else "Not verified")
                RowDivider()
                ValueLine("Student status", if (u.studentVerified) "Verified student" else "Not verified yet")
            }
        }
        item {
            SettingsNote(
                "Your campus comes from the university email you signed up with and can't be changed in the app. " +
                    "A student is only shown as verified when the server matched their email to the university.",
            )
        }
    }
}

@Composable
fun DefaultFeedScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Default feed", onBack) {
        item {
            SettingsGroup {
                SettingsRadioRow("Campus", "Posts from everyone on campus", s.feedFilter == FeedFilter.Campus) {
                    viewModel.update { it.copy(feedFilter = FeedFilter.Campus) }
                }
                SettingsRadioRow("Following", "Posts from the people you follow", s.feedFilter == FeedFilter.Following) {
                    viewModel.update { it.copy(feedFilter = FeedFilter.Following) }
                }
            }
        }
        item { SettingsNote("Home opens on this feed. If you switch feeds on Home, UniVibe remembers that choice here too. It is saved on this phone.") }
    }
}

@Composable
fun LegalScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    SettingsPage("Legal & About", onBack) {
        item {
            SettingsGroup {
                SettingsNavRow(VibeIcons.Info, "About UniVibe", { onNavigate(SettingsRoutes.ABOUT) }, subtitle = "Version ${BuildConfig.VERSION_NAME}")
                RowDivider()
                SettingsNavRow(VibeIcons.Document, "Terms of Service", { onNavigate(SettingsRoutes.TERMS) })
                RowDivider()
                SettingsNavRow(VibeIcons.Document, "Privacy Policy", { onNavigate(SettingsRoutes.PRIVACY_POLICY) })
                RowDivider()
                SettingsNavRow(VibeIcons.Document, "Community guidelines", { onNavigate(SettingsRoutes.GUIDELINES) })
            }
        }
        item { SettingsNote("Open-source licences are listed on the About screen.") }
    }
}

@Composable
private fun ValueLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
