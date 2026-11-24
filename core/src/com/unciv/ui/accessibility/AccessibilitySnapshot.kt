package com.unciv.ui.accessibility

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.unciv.UncivGame
import com.unciv.ui.components.input.ActorAttachments
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.popups.Popup
import com.unciv.view.CivView

/**
 * One collected [AccessibilityNode] together with the live [Actor] it was created from.
 *
 * The actor reference must only be touched on the GL thread, e.g. to synthesize a tap
 * when the screen reader user activates the node.
 */
data class AccessibleEntry(val node: AccessibilityNode, val actor: Actor, val isPopup: Boolean = false)

/**
 * Walks a libGDX [Stage] and builds a flat list of screen-reader-focusable elements.
 *
 * Must be called on the GL thread. Popup content is listed first so TalkBack lands
 * inside open dialogs; remaining nodes are ordered top-to-bottom, left-to-right.
 * World-map tiles inside the current viewport are exposed as [AccessibilityRole.MapTile]
 * nodes with [TileDescription] text so the map itself can be explored tile by tile.
 */
object AccessibilitySnapshot {
    const val MAX_NODES = 800
    private const val MAX_TILES = 220
    private val markupTagRegex = Regex("\\[[^\\[\\]]*\\]")

    /** Removes Gdx color markup (`[...]`) from label text so screen readers read clean text. */
    fun stripMarkup(text: CharSequence): String = markupTagRegex.replace(text, "").trim()

    fun collect(stage: Stage): List<AccessibleEntry> {
        val collector = Collector(stage)
        for (actor in stage.actors) collector.visit(actor, 0)
        val popupEntries = collector.entries.filter { it.isPopup }
        val mainEntries = collector.entries.filter { !it.isPopup }
        val sortedMain = mainEntries.sortedWith(
            compareByDescending<Collector.Entry> { it.tilePriority }
                .thenByDescending { it.entry.node.bounds.y + it.entry.node.bounds.height }
                .thenBy { it.entry.node.bounds.x }
        )
        val combined = (popupEntries + sortedMain).take(MAX_NODES)
        return combined.mapIndexed { index, entry ->
            entry.entry.copy(node = entry.entry.node.copy(id = index))
        }
    }

    private class Collector(private val stage: Stage) {
        class Entry(val entry: AccessibleEntry, val isPopup: Boolean, val tilePriority: Int)
        val entries = ArrayList<Entry>(256)
        private val consumed = HashSet<Actor>(512)
        private val point = Vector2()
        private var tileCount = 0
        private val viewingCiv: CivView? = try {
            if (UncivGame.isCurrentInitialized()) {
                val ws = UncivGame.Current.worldScreen
                if (ws != null && ws.stage === stage) {
                    try { ws.selectedGameView.civView } catch (_: Throwable) { null }
                } else null
            } else null
        } catch (_: Throwable) { null }

        fun visit(actor: Actor, depth: Int) {
            if (!isEffectivelyVisible(actor) || actor in consumed || depth > 40) return
            if (actor is WorldTileGroup) {
                emitTile(actor)
                return // TileGroup internals (layers) are not scene-graph children; nothing to recurse
            }
            val inPopup = isInPopup(actor)
            when (actor) {
                is CheckBox -> {
                    emitCheckBox(actor, inPopup)
                    return
                }
                is TextButton -> {
                    emitButton(actor, actor.label.text, inPopup)
                    return
                }
                is Button -> {
                    val label = firstNonBlank(
                        collectDescendantText(actor),
                        AccessibilityLabels.inferLabel(actor),
                        childImageLabel(actor)
                    )
                    emitButton(actor, label, inPopup)
                    return
                }
                is Label -> {
                    emitText(actor, inPopup)
                    if (actor in consumed) return
                }
                is TextField -> {
                    emitTextField(actor, inPopup)
                    return
                }
                is SelectBox<*> -> {
                    emitDropDown(actor, inPopup)
                    return
                }
                is Slider -> {
                    emitSlider(actor, inPopup)
                    return
                }
                is ScrollPane -> {
                    emitScrollable(actor, inPopup)
                    // Fall through to children: scroll contents must remain reachable
                }
                is ImageButton -> {
                    val label = firstNonBlank(
                        AccessibilityLabels.inferLabel(actor),
                        childImageLabel(actor)
                    )
                    emitButton(actor, label, inPopup)
                    return
                }
                is Image -> {
                    emitMaybeIconButton(actor, inPopup)
                    if (actor in consumed) return
                }
                is Group -> Unit
                else -> {
                    if (emitLeaf(actor, inPopup)) return
                }
            }
            if (actor is Group) for (child in actor.children) visit(child, depth + 1)
        }

        private fun isEffectivelyVisible(actor: Actor): Boolean {
            var current: Actor? = actor
            var hops = 0
            while (current != null && hops < 40) {
                if (!current.isVisible) return false
                current = current.parent
                hops++
            }
            return true
        }

        private fun isInPopup(actor: Actor): Boolean {
            var current: Actor? = actor
            var hops = 0
            while (current != null && hops < 40) {
                if (current is Popup) return true
                current = current.parent
                hops++
            }
            return false
        }

        private fun stageBounds(actor: Actor): Rectangle? {
            if (actor.width <= 0f || actor.height <= 0f) return null
            point.set(0f, 0f)
            try {
                actor.localToStageCoordinates(point)
            } catch (_: Throwable) { return null }
            if (point.x.isNaN() || point.y.isNaN()) return null
            val width = kotlin.math.abs(actor.width * actor.scaleX)
            val height = kotlin.math.abs(actor.height * actor.scaleY)
            if (width <= 1f || height <= 1f) return null
            return Rectangle(point.x, point.y, width, height)
        }

        private fun isEnabled(actor: Actor): Boolean {
            var current: Actor? = actor
            var hops = 0
            while (current != null && hops < 12) {
                if (current.touchable == Touchable.disabled) return false
                current = current.parent
                hops++
            }
            return true
        }

        private fun hasClick(actor: Actor): Boolean {
            var current: Actor? = actor
            var hops = 0
            while (current != null && hops < 6) {
                if (current.listeners.any { it is ClickListener }) return true
                // Modern tap handling: onActivation/onClick attach an ActivationListener
                // (a gesture listener, not a ClickListener) via ActorAttachments
                if (ActorAttachments.getOrNull(current)?.activationActions?.isNotEmpty() == true) return true
                // Container-holder pattern: holder has the listener, button doesn't
                if (current is Group && current.listeners.size > 0) return true
                current = current.parent
                hops++
            }
            return actor.listeners.any { it is ClickListener }
        }

        /** Nearest human-readable label on [actor] itself or its ancestors.
         *  Icon images inside a labeled clickable holder (menu button, map toggles, ...)
         *  would otherwise be announced as a bare "Button". */
        private fun ancestorLabel(actor: Actor): String? {
            var current: Actor? = actor
            var hops = 0
            while (current != null && hops < 6) {
                AccessibilityLabels.inferLabel(current)?.let { return it }
                current = current.parent
                hops++
            }
            return null
        }

        /** Text of all descendant labels, used for buttons and consumed so labels aren't read twice. */
        private fun collectDescendantText(actor: Actor): StringBuilder {
            val text = StringBuilder()
            val stack = ArrayDeque<Actor>()
            stack.add(actor)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (current !== actor && current is Label) {
                    val labelText = stripMarkup(current.text)
                    if (labelText.isNotEmpty()) {
                        if (text.isNotEmpty()) text.append(", ")
                        text.append(labelText)
                    }
                    consumed.add(current)
                } else if (current !== actor && current is TextField) {
                    consumed.add(current)
                }
                if (current is Group) for (child in current.children) stack.add(child)
            }
            return text
        }

        private fun childImageLabel(actor: Actor): String? {
            if (actor !is Group) return null
            val stack = ArrayDeque<Actor>()
            stack.add(actor)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (current !== actor) {
                    AccessibilityLabels.inferLabel(current)?.let { return it }
                }
                if (current is Group) for (child in current.children) stack.add(child)
            }
            return null
        }

        private fun firstNonBlank(vararg candidates: Any?): String {
            for (c in candidates) {
                val s = when (c) {
                    is StringBuilder -> c.toString()
                    is String -> c
                    else -> null
                }?.trim()
                if (!s.isNullOrEmpty()) return s
            }
            return ""
        }

        private fun addNode(node: AccessibilityNode, actor: Actor, inPopup: Boolean, tilePriority: Int = 0) {
            entries.add(Entry(AccessibleEntry(node, actor, inPopup), inPopup, tilePriority))
        }

        private fun emitButton(actor: Actor, rawText: CharSequence, inPopup: Boolean) {
            val bounds = stageBounds(actor) ?: return
            collectDescendantText(actor)
            val text = stripMarkup(rawText).ifEmpty { ancestorLabel(actor) ?: "" }
            addNode(
                AccessibilityNode(
                    id = -1,
                    text = text.ifEmpty { "Button" },
                    hint = "Double tap to activate",
                    role = AccessibilityRole.Button,
                    bounds = bounds,
                    clickable = true,
                    enabled = isEnabled(actor)
                ),
                actor, inPopup
            )
        }

        private fun emitCheckBox(actor: CheckBox, inPopup: Boolean) {
            val bounds = stageBounds(actor) ?: return
            collectDescendantText(actor)
            addNode(
                AccessibilityNode(
                    id = -1,
                    text = stripMarkup(actor.label.text).ifEmpty { "Checkbox" },
                    role = AccessibilityRole.CheckBox,
                    bounds = bounds,
                    clickable = true,
                    enabled = isEnabled(actor),
                    checked = actor.isChecked
                ),
                actor, inPopup
            )
        }

        private fun emitText(actor: Label, inPopup: Boolean) {
            val clickable = hasClick(actor)
            val text = stripMarkup(actor.text)
            if (text.isEmpty() && !clickable) return
            val bounds = stageBounds(actor) ?: return
            if (clickable) consumed.add(actor)
            addNode(
                AccessibilityNode(
                    id = -1,
                    text = text.ifEmpty { "Button" },
                    role = if (clickable) AccessibilityRole.Button else AccessibilityRole.Text,
                    bounds = bounds,
                    clickable = clickable && isEnabled(actor),
                    enabled = isEnabled(actor)
                ),
                actor, inPopup
            )
        }

        private fun emitTextField(actor: TextField, inPopup: Boolean) {
            val bounds = stageBounds(actor) ?: return
            addNode(
                AccessibilityNode(
                    id = -1,
                    // User-entered text is not markup - never strip it
                    text = actor.text,
                    hint = actor.messageText?.let { stripMarkup(it) } ?: "",
                    role = AccessibilityRole.TextField,
                    bounds = bounds,
                    clickable = true,
                    enabled = isEnabled(actor),
                    editable = true,
                    password = actor.isPasswordMode
                ),
                actor, inPopup
            )
        }

        private fun emitDropDown(actor: SelectBox<*>, inPopup: Boolean) {
            val bounds = stageBounds(actor) ?: return
            addNode(
                AccessibilityNode(
                    id = -1,
                    text = actor.selected?.let { stripMarkup(it.toString()) } ?: "",
                    role = AccessibilityRole.DropDown,
                    bounds = bounds,
                    clickable = true,
                    enabled = isEnabled(actor)
                ),
                actor, inPopup
            )
        }

        private fun emitSlider(actor: Slider, inPopup: Boolean) {
            val bounds = stageBounds(actor) ?: return
            val range = actor.maxValue - actor.minValue
            val progress = if (range <= 0f) 0
                else ((actor.value - actor.minValue) / range * 100).toInt().coerceIn(0, 100)
            addNode(
                AccessibilityNode(
                    id = -1,
                    text = "$progress percent",
                    role = AccessibilityRole.Slider,
                    bounds = bounds,
                    enabled = isEnabled(actor),
                    progress = progress
                ),
                actor, inPopup
            )
        }

        private fun emitScrollable(actor: ScrollPane, inPopup: Boolean) {
            val bounds = stageBounds(actor) ?: return
            addNode(
                AccessibilityNode(
                    id = -1,
                    text = "",
                    role = AccessibilityRole.ScrollableContainer,
                    bounds = bounds,
                    enabled = isEnabled(actor),
                    scrollable = true
                ),
                actor, inPopup
            )
        }

        private fun emitMaybeIconButton(actor: Image, inPopup: Boolean) {
            if (!hasClick(actor)) return
            val label = ancestorLabel(actor) ?: ""
            val bounds = stageBounds(actor) ?: return
            consumed.add(actor)
            addNode(
                AccessibilityNode(
                    id = -1,
                    text = label.ifEmpty { "Button" },
                    hint = "Double tap to activate",
                    role = AccessibilityRole.Button,
                    bounds = bounds,
                    clickable = true,
                    enabled = isEnabled(actor)
                ),
                actor, inPopup
            )
        }

        /** Clickable non-widget leaves, e.g. icon images with a click listener. */
        private fun emitLeaf(actor: Actor, inPopup: Boolean): Boolean {
            if (!hasClick(actor)) return false
            // Plain containers whose children already cover the content shouldn't double up
            if (actor is Group && actor.children.size > 0) return false
            val bounds = stageBounds(actor) ?: return false
            val label = ancestorLabel(actor) ?: ""
            consumed.add(actor)
            addNode(
                AccessibilityNode(
                    id = -1,
                    text = label.ifEmpty { "Button" },
                    hint = "Double tap to activate",
                    role = AccessibilityRole.Button,
                    bounds = bounds,
                    clickable = true,
                    enabled = isEnabled(actor)
                ),
                actor, inPopup
            )
            return true
        }

        private fun emitTile(actor: WorldTileGroup) {
            if (tileCount >= MAX_TILES) return
            val bounds = stageBounds(actor) ?: return
            // Only tiles intersecting the current viewport are useful to TalkBack
            val sw = stage.width
            val sh = stage.height
            if (bounds.x > sw || bounds.x + bounds.width < 0 || bounds.y > sh || bounds.y + bounds.height < 0) return
            if (bounds.width < 8f || bounds.height < 8f) return
            val description = try {
                TileDescription.describe(actor.tileView, viewingCiv)
            } catch (_: Throwable) { "Map tile" }
            tileCount++
            addNode(
                AccessibilityNode(
                    id = -1,
                    text = description,
                    hint = "Double tap to select",
                    role = AccessibilityRole.MapTile,
                    bounds = bounds,
                    clickable = true,
                    enabled = true
                ),
                actor, inPopup = false, tilePriority = 0
            )
        }
    }
}
