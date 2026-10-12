package com.univibe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.univibe.app.data.model.Student
import com.univibe.app.ui.theme.Spacing

/** Full-bleed photo-style card with name, course and shared interests over a scrim. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileDiscoveryCard(student: Student, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(accentGradient(student.accent)))
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "View ${student.name}'s full profile", onClick = onClick)
                else Modifier,
            ),
    ) {
        if (onClick != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.md),
                shape = CircleShape, color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White,
            ) {
                Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("View profile", style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp).padding(start = 2.dp))
                }
            }
        }
        Text(
            text = student.name.split(" ").take(2).joinToString("") { it.first().uppercase() },
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
        if (student.verified) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(Spacing.md),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Verified student", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                .padding(Spacing.lg)
                .padding(top = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${student.name.substringBefore(' ')}, ${student.age}", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                if (student.online) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
            Text("${student.course} · ${student.year}", color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.bodyMedium)
            Text(student.university, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(Spacing.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                student.interests.forEach { interest ->
                    val isShared = interest in student.shared
                    Surface(shape = CircleShape, color = Color.White.copy(alpha = if (isShared) 0.38f else 0.2f), contentColor = Color.White) {
                        Text(interest, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}
