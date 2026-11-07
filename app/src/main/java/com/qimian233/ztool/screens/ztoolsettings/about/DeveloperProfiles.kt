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
    @StringRes val descriptionRes: Int,
    @DrawableRes val avatarRes: Int,
    val highlightKey: String,
    val socialLinks: List<DeveloperSocialLink>
)

val DEVELOPER_PROFILES: List<DeveloperProfile> = listOf(
    DeveloperProfile(
        nameRes = R.string.about_dev_qimian233,
        roleRes = R.string.page_settings_about_qimian233_summary,
        descriptionRes = R.string.page_settings_about_qimian233_summary,
        avatarRes = R.drawable.about_avatar_qimian233,
        highlightKey = "about_dev_qimian233",
        socialLinks = listOf(
            DeveloperSocialLink(
                labelRes = R.string.page_settings_about_social_github_label,
                iconRes = R.drawable.ic_github_logo,
                url = "https://github.com/qwqawa64"
            ),
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
        descriptionRes = R.string.page_settings_about_wasd_destroy_description,
        avatarRes = R.drawable.about_avatar_wasd_destroy,
        highlightKey = "about_dev_wasd_destroy",
        socialLinks = listOf(
            DeveloperSocialLink(
                labelRes = R.string.page_settings_about_social_github_label,
                iconRes = R.drawable.ic_github_logo,
                url = "https://github.com/WASDDestroy"
            ),
            DeveloperSocialLink(
                labelRes = R.string.page_settings_about_social_coolapk_label,
                iconRes = R.drawable.ic_coolapk_logo,
                url = "http://www.coolapk.com/u/18634835",
                packageName = "com.coolapk.market"
            )
        )
    ),
    DeveloperProfile(
        nameRes = R.string.about_dev_lingji2233,
        roleRes = R.string.page_settings_about_lingji2233_summary,
        descriptionRes = R.string.page_settings_about_lingji2233_summary,
        avatarRes = R.drawable.about_avatar_lingji2233,
        highlightKey = "about_dev_lingji2233",
        socialLinks = listOf(
            DeveloperSocialLink(
                labelRes = R.string.page_settings_about_social_github_label,
                iconRes = R.drawable.ic_github_logo,
                url = "https://github.com/lingji2233"
            ),
            DeveloperSocialLink(
                labelRes = R.string.page_settings_about_social_coolapk_label,
                iconRes = R.drawable.ic_coolapk_logo,
                url = "https://www.coolapk.com/u/22688909",
                packageName = "com.coolapk.market"
            )
        )
    ),
    DeveloperProfile(
        nameRes = R.string.about_dev_uuuddddl,
        roleRes = R.string.page_settings_about_uuuddddl_summary,
        descriptionRes = R.string.page_settings_about_uuuddddl_summary,
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
