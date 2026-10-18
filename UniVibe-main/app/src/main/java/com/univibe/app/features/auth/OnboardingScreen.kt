package com.univibe.app.features.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.features.profile.AllInterests
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.theme.Spacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    user: AuthUser,
    viewModel: OnboardingViewModel = viewModel(key = rememberSaveable { "onboarding-${java.util.UUID.randomUUID()}" }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by animateFloatAsState((state.step + 1) / ONBOARDING_STEPS.toFloat(), label = "onboardingProgress")
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPhotoPicked(true)
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.step > 0) {
                IconButton(onClick = viewModel::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            } else Spacer(Modifier.width(48.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            if (state.step > 0) {
                TextButton(onClick = viewModel::skip) { Text("Skip") }
            } else Spacer(Modifier.width(64.dp))
        }
        Text(
            "Step ${state.step + 1} of $ONBOARDING_STEPS",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedContent(
            targetState = state.step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                if (targetState > initialState) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                else (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            },
            label = "onboardingStep",
        ) { step ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                when (step) {
                    0 -> {
                        Heading("Welcome, ${user.fullName.substringBefore(' ')}!")
                        Text("Let's set up your campus profile. It takes less than a minute.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm + 4.dp)) {
                                StatusRow(Icons.Outlined.School, "University", user.universityName, done = true)
                                StatusRow(Icons.Outlined.CheckCircle, "Email", if (user.emailVerified) "Verified" else "Not verified", user.emailVerified)
                                StatusRow(
                                    if (user.studentVerified) Icons.Outlined.CheckCircle else Icons.Outlined.HourglassEmpty,
                                    "Student status", if (user.studentVerified) "Verified student" else "Not verified yet", user.studentVerified,
                                )
                            }
                        }
                        if (!user.studentVerified) {
                            MessageBanner(
                                "We couldn't match your email to ${user.universityName}, so you're not marked as a verified student. " +
                                    "You can use UniVibe, but some features may stay limited until verification succeeds.",
                                BannerKind.Info,
                            )
                        }
                    }
                    1 -> {
                        Heading("What do you study?")
                        Text("Your course or faculty helps you find classmates. You can skip this.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        VibeTextField(state.course, viewModel::onCourse, label = "Course or faculty", placeholder = "e.g. Computer Science")
                    }
                    2 -> {
                        Heading("Pick your interests")
                        Text("We'll use these to suggest people, communities and events.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.animateContentSize()) {
                            AllInterests.forEach { name ->
                                InterestChip(name, selected = name in state.interests, onClick = { viewModel.toggleInterest(name) })
                            }
                        }
                    }
                    else -> {
                        Heading("Show your vibe")
                        Text("Add a photo and a short bio. Both are optional and can be changed later.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            StudentAvatar(user.fullName, size = 72.dp, imagePath = user.avatarUrl)
                            VibeButton(
                                if (state.hasPhoto) "Photo selected" else "Choose photo",
                                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                style = VibeButtonStyle.Secondary, compact = true, leadingIcon = Icons.Outlined.PhotoCamera,
                            )
                        }
                        VibeTextField(
                            value = state.bio, onValueChange = viewModel::onBio, label = "Short bio", singleLine = false, minLines = 3,
                            isError = state.bio.length > MAX_BIO, supportingText = "${state.bio.length}/$MAX_BIO",
                        )
                        state.error?.let { MessageBanner(it, BannerKind.Error) }
                    }
                }
            }
        }

        VibeButton(
            text = if (state.isLast) "Finish" else "Continue",
            onClick = viewModel::next,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canContinue,
            loading = state.submitting,
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = Spacing.md)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
