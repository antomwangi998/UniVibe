package com.univibe.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.univibe.app.data.repository.MockData
import com.univibe.app.ui.theme.UniVibeTheme

@Composable
private fun PreviewHost(dark: Boolean, content: @Composable () -> Unit) {
    UniVibeTheme(darkTheme = dark) {
        Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable
private fun ComponentSamples() {
    UniVibeLockup()
    VibeButton("Continue", {})
    VibeButton("Learn more", {}, style = VibeButtonStyle.Secondary)
    VibeButton("Loading", {}, loading = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InterestChip("Tech", selected = true, onClick = {})
        InterestChip("Sports", onClick = {})
        InterestChip("Music")
    }
    VibeTextField("", {}, label = "Email")
    VibeTextField("bad", {}, label = "Email", isError = true, supportingText = "Enter a valid email")
    CampusPostCard(MockData.posts[1], {}, {})
}

@Preview(name = "Components light", showBackground = true)
@Composable
private fun ComponentsLight() = PreviewHost(false) { ComponentSamples() }

@Preview(name = "Components dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ComponentsDark() = PreviewHost(true) { ComponentSamples() }

@Preview(name = "Bottom bar light", showBackground = true)
@Composable
private fun BarLight() = PreviewHost(false) { SampleBar() }

@Preview(name = "Bottom bar dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BarDark() = PreviewHost(true) { SampleBar() }

@Composable
private fun SampleBar() {
    VibeBottomBar(
        items = listOf(
            VibeBarItem("home", "Home", VibeIcons.Home, VibeIcons.HomeSelected),
            VibeBarItem("discover", "Discover", VibeIcons.Discover, VibeIcons.DiscoverSelected),
            VibeBarItem("chats", "Chats", VibeIcons.Chats, VibeIcons.ChatsSelected),
            VibeBarItem("events", "Events", VibeIcons.Events, VibeIcons.EventsSelected),
            VibeBarItem("profile", "Profile", VibeIcons.Profile, VibeIcons.ProfileSelected),
        ),
        selectedRoute = "home",
        onSelect = {},
    )
}
