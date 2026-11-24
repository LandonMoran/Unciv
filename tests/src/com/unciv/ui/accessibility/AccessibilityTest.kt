package com.unciv.ui.accessibility

import com.badlogic.gdx.scenes.scene2d.Actor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityTest {
    @Test
    fun `stripMarkup removes color tags but keeps text`() {
        assertEquals("Warrior", AccessibilitySnapshot.stripMarkup("[#FF0000]Warrior[]"))
        assertEquals("{Pick a tech}!", AccessibilitySnapshot.stripMarkup("{Pick a tech}!"))
        assertEquals("plain", AccessibilitySnapshot.stripMarkup("plain"))
    }

    @Test
    fun `labels round-trip through actor name`() {
        val actor = Actor()
        assertNull(AccessibilityLabels.inferLabel(actor))
        AccessibilityLabels.setLabel(actor, "Social Policies")
        assertEquals("Social Policies", AccessibilityLabels.inferLabel(actor))
    }

    @Test
    fun `labels reject texture paths and blank names`() {
        val actor = Actor()
        actor.name = "OtherIcons/Undo"
        assertNull(AccessibilityLabels.inferLabel(actor))
        actor.name = "   "
        assertNull(AccessibilityLabels.inferLabel(actor))
    }

    @Test
    fun `announcer delivers messages to listeners`() {
        val received = ArrayList<AccessibilityAnnouncer.Announcement>()
        val listener: (AccessibilityAnnouncer.Announcement) -> Unit = { received.add(it) }
        AccessibilityAnnouncer.announcementListeners.add(listener)
        try {
            AccessibilityAnnouncer.announce("Your turn")
            AccessibilityAnnouncer.announce("   ")
            assertEquals(1, received.size)
            assertEquals("Your turn", received[0].message)
        } finally {
            AccessibilityAnnouncer.announcementListeners.remove(listener)
        }
    }
}
