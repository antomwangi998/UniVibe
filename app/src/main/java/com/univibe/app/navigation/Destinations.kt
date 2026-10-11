package com.univibe.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.univibe.app.ui.components.VibeBarItem

object Routes {
    const val SETTINGS = "settings"
}

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Discover("discover", "Discover", Icons.Outlined.Explore, Icons.Filled.Explore),
    Chats("chats", "Chats", Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble),
    Events("events", "Events", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Profile("profile", "Profile", Icons.Outlined.Person, Icons.Filled.Person);

    fun toBarItem() = VibeBarItem(route, label, icon, selectedIcon)
}
