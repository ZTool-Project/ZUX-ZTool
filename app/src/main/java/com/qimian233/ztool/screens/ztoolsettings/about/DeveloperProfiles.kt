package com.qimian233.ztool.screens.ztoolsettings.about

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.qimian233.ztool.R

data class DeveloperSocialLink(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val url: String,
    val packageName: String? = null
)

data class DeveloperProfile(
    @StringRes val nameRes: Int,
    @StringRes val roleRes: Int,
    @DrawableRes val avatarRes: Int,
    val highlightKey: String,
    val socialLinks: List<DeveloperSocialLink>
)

val DEVELOPER_PROFILES: List<DeveloperProfile> = listOf(
    DeveloperProfile(
        nameRes = R.string.about_dev_qimian233,
        roleRes = R.string.page_settings_about_qimian233_summary,
        avatarRes = R.drawable.about_avatar_qimian233,
        highlightKey = "about_dev_qimian233",
        socialLinks = listOf(
            DeveloperSocialLink(
                labelRes = R.string.page_settings_about_social_coolapk_label,
                iconRes = R.drawable.ic_coolapk_logo,
                url = "http://www.coolapk.com/u/10099756",
                packageName = "com.coolapk.market"
            )
        )
    ),
    DeveloperProfile(
        nameRes = R.string.about_dev_wasd_destroy,
        roleRes = R.string.page_settings_about_wasd_destroy_summary,
        avatarRes = R.drawable.about_avatar_wasd_destroy,
        highlightKey = "about_dev_wasd_destroy",
        socialLinks = listOf(
            DeveloperSocialLink(
                labelRes = R.string.page_settings_about_social_coolapk_label,
                iconRes = R.drawable.ic_coolapk_logo,
                url = "http://www.coolapk.com/u/18634835",
                packageName = "com.coolapk.market"
            )
        )
    ),
    DeveloperProfile(
        nameRes = R.string.about_dev_uuuddddl,
        roleRes = R.string.page_settings_about_uuuddddl,
        avatarRes = R.drawable.about_avatar_uuuddddl,
        highlightKey = "about_dev_uuuddddl",
        socialLinks = listOf(
            DeveloperSocialLink(
                labelRes = R.string.page_settings_about_social_github_label,
                iconRes = R.drawable.ic_github_logo,
                url = "https://github.com/uuuddddl"
            )
        )
    )
)
