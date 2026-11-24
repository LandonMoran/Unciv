package com.unciv.ui.accessibility

import com.badlogic.gdx.scenes.scene2d.Actor

/**
 * Best-effort human-readable labels for actors that have no text of their own,
 * notably icon-only buttons (Tech/Policy/Diplomacy, minimap toggles, zoom, ...).
 *
 * Tooltips are suppressed on touch devices, so the snapshot cannot rely on them.
 * Instead, screens set [Actor.setName] to a translated label; this helper reads
 * it back. Anything that looks like an internal id or texture path is ignored.
 *
 * Note: labels use [Actor.name] only - never [Actor.userObject], which is owned
 * by [com.unciv.ui.components.input.ActorAttachments] (activation actions) and
 * must not be overwritten.
 */
object AccessibilityLabels {
    /** Attach an explicit screen-reader label to an actor. */
    fun setLabel(actor: Actor, label: String) {
        if (label.isNotBlank()) {
            actor.name = label
        }
    }

    /** Returns a human-readable label carried by [actor], or null if none. */
    fun inferLabel(actor: Actor): String? {
        val name = actor.name
        if (name != null && isHuman(name)) return name.trim()
        return null
    }

    private fun isHuman(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return false
        if (t.contains('/') || t.contains('\\')) return false
        if (t.contains(".png") || t.contains(".atlas")) return false
        // Default libGDX actor names / ids
        if (t.startsWith("actor", ignoreCase = true)) return false
        if (t.all { it.isDigit() }) return false
        if (!t.any { it.isLetter() }) return false
        return true
    }
}
