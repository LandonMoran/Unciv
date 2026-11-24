package com.unciv.ui.accessibility

import com.unciv.UncivGame
import com.unciv.models.UncivSound

/**
 * Explicit TalkBack announcements for game events that a flat UI snapshot
 * cannot convey on its own: turn changes, tile selection, combat results, ...
 *
 * Game code calls [announce] on the GL thread; platform bridges subscribe via
 * [announcementListeners] and speak the message (plus an optional earcon).
 * Listeners are also pinged through [UncivGame.notifyAccessibilityContentChanged]
 * so the snapshot refreshes alongside the announcement.
 */
object AccessibilityAnnouncer {
    data class Announcement(val message: String, val earcon: UncivSound = UncivSound.Silent)

    val announcementListeners = mutableListOf<(Announcement) -> Unit>()

    fun announce(message: String, earcon: UncivSound = UncivSound.Silent) {
        if (message.isBlank()) return
        val announcement = Announcement(message, earcon)
        for (listener in announcementListeners.toList()) {
            try {
                listener(announcement)
            } catch (_: Throwable) {}
        }
        try {
            if (UncivGame.isCurrentInitialized()) UncivGame.Current.notifyAccessibilityContentChanged()
        } catch (_: Throwable) {}
    }
}
