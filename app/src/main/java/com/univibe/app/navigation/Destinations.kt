package com.univibe.app.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.univibe.app.ui.components.VibeBarItem
import com.univibe.app.ui.components.VibeIcons

object Routes {
    const val SETTINGS = "settings"
}

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home("home", "Home", VibeIcons.Home, VibeIcons.HomeSelected),
    Discover("discover", "Discover", VibeIcons.Discover, VibeIcons.DiscoverSelected),
    Chats("chats", "Chats", VibeIcons.Chats, VibeIcons.ChatsSelected),
    Events("events", "Events", VibeIcons.Events, VibeIcons.EventsSelected),
    Profile("profile", "Profile", VibeIcons.Profile, VibeIcons.ProfileSelected);

    fun toBarItem() = VibeBarItem(route, label, icon, selectedIcon)
}
