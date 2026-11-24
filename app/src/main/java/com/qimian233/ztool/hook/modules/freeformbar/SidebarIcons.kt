package com.qimian233.ztool.hook.modules.freeformbar

import android.content.Context
import android.graphics.drawable.Drawable
import com.qimian233.ztool.hook.base.ModuleResources

/** Resolves this module's own drawables inside the host process. */
internal object SidebarIcons {

    const val EDIT = "ztool_ic_edit"
    const val DONE = "ztool_ic_done"

    /** Loads [resourceName] tinted to [tint], so a white glyph also works on a light panel. */
    fun get(hostContext: Context, resourceName: String, tint: Int): Drawable? =
        ModuleResources.getDrawable(hostContext, resourceName, tint)
}
