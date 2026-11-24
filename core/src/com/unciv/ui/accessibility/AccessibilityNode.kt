package com.unciv.ui.accessibility

import com.badlogic.gdx.math.Rectangle

/** Roles of UI elements exposed to platform screen readers (e.g. Android TalkBack). */
enum class AccessibilityRole {
    /** Activates an action when tapped, e.g. menu entries, dialog buttons, icon buttons */
    Button,
    /** Static readable text */
    Text,
    /** Single-line editable text */
    TextField,
    /** Toggleable checkbox */
    CheckBox,
    /** Drop-down list, opens a list of choices when activated */
    DropDown,
    /** Slider adjustable in steps */
    Slider,
    /** Scrollable container, e.g. long option lists */
    ScrollableContainer,
    /** A world-map tile. Activating it selects the tile (same as tapping it). */
    MapTile
}

/**
 * Platform-independent description of one UI element that a screen reader can focus.
 *
 * Bounds are in stage coordinates (origin bottom-left). The platform bridge
 * (e.g. [com.unciv.app.accessibility.UncivAccessibilityBridge] on Android) maps them
 * to screen pixels and publishes them as natively focusable elements.
 */
data class AccessibilityNode(
    val id: Int,
    val text: String,
    val hint: String = "",
    val role: AccessibilityRole,
    val bounds: Rectangle,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    /** Checked state for [AccessibilityRole.CheckBox], null for everything else */
    val checked: Boolean? = null,
    val editable: Boolean = false,
    val password: Boolean = false,
    /** 0-100 progress for [AccessibilityRole.Slider], null for everything else */
    val progress: Int? = null,
    val scrollable: Boolean = false
)
