package com.unciv.app.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Buttons
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.UncivGame
import com.unciv.ui.accessibility.AccessibleEntry
import com.unciv.ui.accessibility.AccessibilityNode
import com.unciv.ui.accessibility.AccessibilityRole
import com.unciv.ui.accessibility.AccessibilitySnapshot
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * Exposes the libGDX stage to Android screen readers (TalkBack) as natively
 * focusable "virtual views" - otherwise TalkBack only sees one blank surface.
 *
 * How it works: the current [BaseScreen] stage is walked on the GL thread
 * ([AccessibilitySnapshot]), producing platform-independent [AccessibilityNode]s.
 * They are published here through [ExploreByTouchHelper], which handles hover,
 * accessibility focus and announcements. Activating a node synthesizes a tap on
 * the GL thread, so TalkBack "double-tap to activate" presses the actual button.
 *
 * The bridge stays idle unless the "Screen reader support" setting is on *and*
 * a touch-exploration service (e.g. TalkBack) is running, so there is no cost
 * for other players.
 *
 * Full game coverage: menus, dialogs, options, city screens, pickers, diplomacy,
 * and the world map itself - tiles are exposed with terrain/unit/city descriptions
 * and activate-to-select, so the game is playable with TalkBack alone (no keyboard).
 * Icon-only buttons carry explicit labels; game events (tile selection, unit/city
 * selection, turn changes) are announced with short earcons.
 */
class UncivAccessibilityBridge(
    private val gameView: View,
    private val game: UncivGame
) {
    private val helper = UncivExploreHelper(gameView)
    private var lastSnapshotMs = 0L
    private var snapshotPending = false
    private var lastScreenTitle = "Unciv"

    fun attach() {
        ViewCompat.setAccessibilityDelegate(gameView, helper)
        gameView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        game.screenChangeListeners.add { screen -> onScreenChanged(screen) }
        game.accessibilityContentListeners.add { onContentChanged() }
        com.unciv.ui.accessibility.AccessibilityAnnouncer.announcementListeners.add { announcement ->
            onExplicitAnnouncement(announcement.message, announcement.earcon)
        }
        onContentChanged()
    }

    private fun onExplicitAnnouncement(message: String, earcon: com.unciv.models.UncivSound) {
        gameView.post {
            if (!isActive()) return@post
            gameView.announceForAccessibility(message)
        }
        if (earcon != com.unciv.models.UncivSound.Silent) {
            Gdx.app.postRunnable {
                try {
                    com.unciv.ui.audio.SoundPlayer.play(earcon)
                } catch (_: Throwable) {}
            }
        }
        // Refresh the snapshot alongside the announcement so focus stays in sync
        requestSnapshot()
    }

    private fun isActive(): Boolean {
        if (!isEnabledInSettings()) return false
        val manager = gameView.context.getSystemService(AccessibilityManager::class.java)
            ?: return false
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    private fun isEnabledInSettings(): Boolean = try {
        game.settings.screenReaderSupport
    } catch (_: Throwable) {
        // Settings load on the GL thread during startup - assume enabled until known otherwise
        true
    }

    private fun onScreenChanged(screen: BaseScreen) {
        if (!isActive()) return
        lastScreenTitle = humanizeScreenName(screen)
        requestSnapshot(announce = lastScreenTitle)
    }

    private fun onContentChanged() {
        if (!isActive()) return
        requestSnapshot(announceFirstText = true)
    }

    private fun requestSnapshot(announce: String? = null, announceFirstText: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastSnapshotMs < MIN_SNAPSHOT_INTERVAL_MS) {
            if (announce != null) gameView.announceForAccessibility(announce)
            return
        }
        lastSnapshotMs = now
        // Coalesce bursts (turn start fires several content events): only one
        // collect() runs per throttle window; the latest stage state wins.
        if (snapshotPending) return
        snapshotPending = true
        Gdx.app.postRunnable {
            snapshotPending = false
            val stage = (game.screen as? BaseScreen)?.stage ?: return@postRunnable
            val entries = AccessibilitySnapshot.collect(stage)
            val stageWidth = stage.width
            val stageHeight = stage.height
            gameView.post {
                if (!isActive()) return@post
                helper.update(entries, stageWidth, stageHeight)
                if (announce != null) gameView.announceForAccessibility(announce)
                else if (announceFirstText) {
                    entries.firstOrNull { it.node.text.isNotBlank() }?.node?.text?.let {
                        gameView.announceForAccessibility(it)
                    }
                }
            }
        }
    }

    private inner class UncivExploreHelper(host: View) : ExploreByTouchHelper(host) {
        private var entries: List<AccessibleEntry> = emptyList()
        private val entryById = HashMap<Int, AccessibleEntry>()
        private val viewBounds = HashMap<Int, Rect>()
        private var hadPopup = false

        fun update(
            newEntries: List<AccessibleEntry>,
            stageWidth: Float,
            stageHeight: Float
        ) {
            val hasPopup = newEntries.any { it.isPopup }
            // A freshly opened dialog should grab TalkBack focus so the user
            // lands inside it instead of behind it. Only on closed -> open
            // transitions, never on refreshes while a dialog is already up.
            val focusPopupId = if (hasPopup && !hadPopup)
                newEntries.firstOrNull { it.isPopup }?.node?.id
            else null
            hadPopup = hasPopup
            entries = newEntries
            entryById.clear()
            for (e in newEntries) entryById[e.node.id] = e
            viewBounds.clear()
            val viewWidth = gameView.width
            val viewHeight = gameView.height
            if (viewWidth > 0 && viewHeight > 0 && stageWidth > 0 && stageHeight > 0) {
                val scaleX = viewWidth / stageWidth
                val scaleY = viewHeight / stageHeight
                for (entry in newEntries) {
                    val bounds = entry.node.bounds
                    viewBounds[entry.node.id] = Rect(
                        (bounds.x * scaleX).toInt(),
                        (viewHeight - (bounds.y + bounds.height) * scaleY).toInt(),
                        ((bounds.x + bounds.width) * scaleX).toInt(),
                        (viewHeight - bounds.y * scaleY).toInt()
                    )
                }
            }
            invalidateRoot()
            if (focusPopupId != null && focusPopupId != INVALID_ID) {
                // Let the new node list settle before moving focus into the dialog
                gameView.post { requestKeyboardFocusForVirtualView(focusPopupId) }
            }
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            // Pure cache read: TalkBack calls this on every hover move, so it
            // must never schedule GL-thread work. Fresh snapshots arrive via
            // screen/content/announcement events (throttled below).
            // Entries are ordered popups-first, then top-to-bottom,
            // smallest-first: forward iteration hits modal dialogs before the
            // map or menu behind them, and small controls before their tiles.
            for (entry in entries) {
                val id = entry.node.id
                if (viewBounds[id]?.contains(x.toInt(), y.toInt()) == true) return id
            }
            return INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            // Pure cache read, same as above: TalkBack enumerates often during
            // linear navigation; rebuilding here caused hover jank.
            for (entry in entries) virtualViewIds.add(entry.node.id)
        }

        private fun entryFor(id: Int) = entryById[id]

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val entry = entryFor(virtualViewId)
            if (entry == null) {
                node.className = "android.view.View"
                return
            }
            val item = entry.node
            node.className = classNameFor(item.role)
            if (item.text.isNotBlank()) node.text = item.text
            if (item.hint.isNotBlank()) node.hintText = item.hint
            node.isFocusable = true
            node.isEnabled = item.enabled
            viewBounds[item.id]?.let { node.setBoundsInParent(it) }
            when (item.role) {
                AccessibilityRole.Button, AccessibilityRole.DropDown, AccessibilityRole.MapTile -> {
                    node.isClickable = item.clickable && item.enabled
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                }
                AccessibilityRole.CheckBox -> {
                    node.isClickable = item.enabled
                    node.isCheckable = true
                    node.isChecked = item.checked == true
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                }
                AccessibilityRole.TextField -> {
                    node.isEditable = true
                    node.isClickable = item.enabled
                    node.isPassword = item.password
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_SET_TEXT)
                }
                AccessibilityRole.Slider -> {
                    node.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                        AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT,
                        0f, 100f, (item.progress ?: 0).toFloat()
                    )
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
                    node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_PROGRESS)
                }
                AccessibilityRole.ScrollableContainer -> {
                    node.isScrollable = item.scrollable
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
                }
                AccessibilityRole.Text -> Unit
            }
        }

        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            super.onPopulateNodeForHost(node)
            node.className = "android.view.View"
            node.contentDescription = "Unciv game, $lastScreenTitle"
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val entry = entryFor(virtualViewId) ?: return false
            val item = entry.node
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    if (!item.clickable || !item.enabled) return false
                    tapActor(entry.actor)
                    return true
                }
                AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> {
                    if (scrollBy(entry.actor, +1)) return true
                    return adjustSlider(entry.actor, +1)
                }
                AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> {
                    if (scrollBy(entry.actor, -1)) return true
                    return adjustSlider(entry.actor, -1)
                }
                AccessibilityNodeInfoCompat.ACTION_SET_TEXT -> {
                    val textField = entry.actor as? TextField ?: return false
                    val text = arguments?.let {
                        // Newer APIs use the CharSequence key; older TalkBack versions send the legacy key
                        it.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                            ?: it.getCharSequence("ACTION_ARGUMENT_SET_TEXT_VALUE")
                    }?.toString() ?: return false
                    Gdx.app.postRunnable {
                        if (textField.stage == null) return@postRunnable
                        textField.text = text
                        textField.fire(ChangeListener.ChangeEvent())
                        requestSnapshot()
                    }
                    return true
                }
                else -> {
                    if (action == AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_PROGRESS.id) {
                        val slider = entry.actor as? Slider ?: return false
                        val progress = arguments
                            ?.getFloat(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_PROGRESS_VALUE, -1f)
                            ?: return false
                        if (progress < 0f) return false
                        Gdx.app.postRunnable {
                            if (slider.stage == null) return@postRunnable
                            slider.value = slider.minValue + (slider.maxValue - slider.minValue) * progress / 100f
                            slider.fire(ChangeListener.ChangeEvent())
                            requestSnapshot()
                        }
                        return true
                    }
                    return false
                }
            }
        }

        private fun tapActor(actor: Actor) {
            Gdx.app.postRunnable {
                val stage = actor.stage ?: return@postRunnable
                if (stage != (game.screen as? BaseScreen)?.stage) {
                    // Screen changed since the snapshot - rebuild instead of tapping stale UI
                    requestSnapshot()
                    return@postRunnable
                }
                // Resolve the tap point against the live actor in case layout shifted,
                // then convert to screen coordinates for Stage.touchDown/touchUp
                val center = Vector2(actor.width / 2f, actor.height / 2f)
                actor.localToStageCoordinates(center)
                stage.stageToScreenCoordinates(center)
                stage.touchDown(center.x.toInt(), center.y.toInt(), 0, Buttons.LEFT)
                stage.touchUp(center.x.toInt(), center.y.toInt(), 0, Buttons.LEFT)
                requestSnapshot()
            }
        }

        private fun scrollBy(actor: Actor, direction: Int): Boolean {
            if (actor !is ScrollPane) return false
            Gdx.app.postRunnable {
                if (actor.stage == null) return@postRunnable
                actor.scrollY = (actor.scrollY + direction * actor.height * 0.8f).coerceIn(0f, actor.maxY)
                actor.updateVisualScroll()
                requestSnapshot()
            }
            return true
        }

        private fun adjustSlider(actor: Actor, direction: Int): Boolean {
            if (actor !is Slider) return false
            Gdx.app.postRunnable {
                if (actor.stage == null) return@postRunnable
                val step = (actor.maxValue - actor.minValue) / 20f
                actor.value = (actor.value + direction * step).coerceIn(actor.minValue, actor.maxValue)
                actor.fire(ChangeListener.ChangeEvent())
                requestSnapshot()
            }
            return true
        }
    }

    companion object {
        // Snapshots run a full stage walk + tile descriptions on the GL thread,
        // so throttle harder than the ~500ms hover cadence: hover/enumeration
        // now read the cache and never rebuild (see above).
        private const val MIN_SNAPSHOT_INTERVAL_MS = 1000L

        private fun classNameFor(role: AccessibilityRole): String = when (role) {
            AccessibilityRole.Button -> "android.widget.Button"
            AccessibilityRole.MapTile -> "android.widget.Button"
            AccessibilityRole.Text -> "android.widget.TextView"
            AccessibilityRole.TextField -> "android.widget.EditText"
            AccessibilityRole.CheckBox -> "android.widget.CheckBox"
            AccessibilityRole.DropDown -> "android.widget.Spinner"
            AccessibilityRole.Slider -> "android.widget.SeekBar"
            AccessibilityRole.ScrollableContainer -> "android.widget.ScrollView"
        }

        private fun humanizeScreenName(screen: BaseScreen): String =
            screen::class.java.simpleName
                .removeSuffix("Screen")
                .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
                .ifBlank { "Unciv" }
    }
}
