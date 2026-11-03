package com.qimian233.ztool.screens.ztoolsettings.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qimian233.ztool.R
import com.qimian233.ztool.ui.components.ZToolDialog
import com.qimian233.ztool.ui.components.ZToolTextButton
import com.qimian233.ztool.ui.theme.LocalZToolColorScheme

@Composable
internal fun DeveloperProfileDialog(
    profile: DeveloperProfile,
    onDismissRequest: () -> Unit,
    onOpenSocialLink: (DeveloperSocialLink) -> Unit
) {
    ZToolDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            ZToolTextButton(
                text = stringResource(R.string.settings_close_button),
                onClick = onDismissRequest
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                DeveloperProfileHeader(profile = profile)
                Spacer(modifier = Modifier.height(12.dp))
                profile.socialLinks.forEachIndexed { index, link ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            color = LocalZToolColorScheme.current.outlineVariant
                        )
                    }
                    DeveloperSocialLinkRow(
                        link = link,
                        onClick = { onOpenSocialLink(link) }
                    )
                }
            }
        }
    )
}

@Composable
private fun DeveloperProfileHeader(profile: DeveloperProfile) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(profile.avatarRes),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(profile.nameRes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = LocalZToolColorScheme.current.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(profile.roleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalZToolColorScheme.current.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeveloperSocialLinkRow(
    link: DeveloperSocialLink,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(link.iconRes),
            contentDescription = null,
            tint = LocalZToolColorScheme.current.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(link.labelRes),
            style = MaterialTheme.typography.titleMedium,
            color = LocalZToolColorScheme.current.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = LocalZToolColorScheme.current.onSurfaceVariant
        )
    }
}
