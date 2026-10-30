package com.univibe.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.Student
import com.univibe.app.ui.theme.Spacing

/**
 * "Suggested for you": a short sideways row of students to follow, dropped into the feed now and then.
 * Why each person is suggested is always something real the data layer worked out (shared interests, same course).
 * Follow and dismiss are remembered by the server (the same as Discover's connect and pass).
 */
@Composable
fun SuggestedPeopleCarousel(
    people: List<Student>,
    onOpenStudent: (String) -> Unit,
    onFollow: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (people.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text("Suggested for you", style = MaterialTheme.typography.titleSmall)
            Text(
                "  ·  See all", style = MaterialTheme.typography.titleSmall, color = colors.primary,
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = "See more people in Discover", onClick = onSeeAll),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(people, key = { it.id }) { student ->
                Surface(
                    shape = RoundedCornerShape(20.dp), color = colors.surface,
                    border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.width(164.dp),
                ) {
                    Box {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.clickable(role = Role.Button, onClickLabel = "View ${student.name}'s profile") { onOpenStudent(student.id) }) {
                                StudentAvatar(student.name, size = 84.dp, accent = student.accent, imagePath = student.avatarUrl, look = student.look)
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            VerifiedName(student.name, student.verified, style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                            Text(
                                reasonFor(student), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                modifier = Modifier.height(36.dp),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            VibeButton("Follow", { onFollow(student.id) }, Modifier.fillMaxWidth(), compact = true)
                        }
                        VibeIconButton(
                            VibeIcons.Close, "Dismiss suggestion for ${student.name}", { onDismiss(student.id) },
                            modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun reasonFor(s: Student): String = when {
    s.shared.isNotEmpty() -> "Also into ${s.shared.first()}"
    s.sameCourse -> "Same course as you"
    s.course.isNotBlank() -> s.course
    else -> "On your campus"
}
