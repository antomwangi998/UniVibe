package com.univibe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The verified-student mark: the scalloped seal with a check, the same one the Home posts carry.
 * Only show it when the server reported the student as verified.
 *
 * [ringColor] is for the rare spot where the seal sits on top of a photo: when it is set, the check shows that colour
 * through the seal's cut-out instead of the photo. Leave it unset on plain surfaces.
 */
@Composable
fun VerifiedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    ringColor: Color = Color.Unspecified,
    /** Matches the owner's Premium theme or accent; unspecified keeps the standard brand colour. */
    tint: Color = Color.Unspecified,
) {
    Box(modifier.size(size).semantics { contentDescription = "Verified student" }, contentAlignment = Alignment.Center) {
        if (ringColor != Color.Unspecified) Box(Modifier.size(size * 0.7f).background(ringColor, CircleShape))
        Icon(Icons.Filled.Verified, contentDescription = null, tint = if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.primary, modifier = Modifier.size(size))
    }
}

/**
 * A student's name followed by the verified seal when [verified]. The name shrinks (ellipsis) before the seal does, so the
 * seal is never pushed out of view. Use this wherever a student's name is shown.
 */
@Composable
fun VerifiedName(
    name: String,
    verified: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    badgeSize: Dp = 16.dp,
    /** The person's Premium look, when the server sent one; the seal then takes its accent. */
    look: com.univibe.app.data.model.ProfileLook? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        Text(
            name, style = style, color = color, fontWeight = fontWeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (verified) {
            Spacer(Modifier.width(4.dp))
            VerifiedBadge(size = badgeSize, tint = com.univibe.app.features.profile.badgeTint(look))
        }
    }
}
