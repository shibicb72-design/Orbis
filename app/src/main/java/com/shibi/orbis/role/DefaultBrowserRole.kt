package com.shibi.orbis.role

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

/** Whether Orbis holds the system browser role, and what Settings can do about it. */
enum class BrowserRoleStatus {
    /** Orbis is the default browser. */
    HELD,

    /** Orbis can be made the default browser; the system dialog asks the user. */
    AVAILABLE,

    /** The system offers no browser role to request (or the role service is missing). */
    UNAVAILABLE,
}

/** A role that is not available cannot be requested, and is reported as unavailable even if it were held. */
fun browserRoleStatus(available: Boolean, held: Boolean): BrowserRoleStatus =
    when {
        !available -> BrowserRoleStatus.UNAVAILABLE
        held -> BrowserRoleStatus.HELD
        else -> BrowserRoleStatus.AVAILABLE
    }

/** The RoleManager side of the default browser entry. The decision itself is [browserRoleStatus]. */
object DefaultBrowserRole {
    fun status(context: Context): BrowserRoleStatus {
        val roles = context.getSystemService(RoleManager::class.java)
            ?: return BrowserRoleStatus.UNAVAILABLE
        return browserRoleStatus(
            available = roles.isRoleAvailable(RoleManager.ROLE_BROWSER),
            held = roles.isRoleHeld(RoleManager.ROLE_BROWSER),
        )
    }

    /** The system dialog that asks the user to make Orbis the default browser; null when there is nothing to ask. */
    fun requestIntent(context: Context): Intent? {
        if (status(context) != BrowserRoleStatus.AVAILABLE) return null
        return context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
    }
}
