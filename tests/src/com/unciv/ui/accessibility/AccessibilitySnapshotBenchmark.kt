package com.unciv.ui.accessibility

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.RedirectOutput
import com.unciv.testing.RedirectPolicy
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

/**
 * Worst-case snapshot benchmark (font-free so it runs headless).
 *
 * Mimics a large-map world screen: [tiles] tile-sized groups nested 3 deep
 * (ScrollPane > TileGroupMap > tile, exercising parent-chain walks and
 * localToStageCoordinates through transforms) plus [controls] clickable
 * button-like groups with children (exercising descendant-text walks).
 * 80% of tiles are decorative (no listeners: worst case for hasClick walks).
 * Prints avg collect() time — the cost paid on every TalkBack hover.
 */
@RunWith(GdxTestRunner::class)
class AccessibilitySnapshotBenchmark {
    private fun buildStage(tiles: Int, controls: Int): Stage {
        val stage = Stage(ScreenViewport(), Mockito.mock(Batch::class.java))
        // Scroll-pane-like nesting with a scale + offset transform
        val outer = Group().apply { setSize(2000f, 3000f) }
        val mid = Group().apply {
            setSize(2000f, 3000f)
            setPosition(-400f, -600f)
            setScale(1.5f)
        }
        outer.addActor(mid)
        stage.addActor(outer)
        for (i in 0 until tiles) {
            val g = Group()
            g.setSize(120f, 120f)
            g.setPosition((i % 100) * 120f, (i / 100) * 120f)
            if (i % 5 == 0) {
                g.name = "Tile $i"
                g.addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {}
                })
            }
            mid.addActor(g)
        }
        for (c in 0 until controls) {
            val b = Group()
            b.setSize(200f, 80f)
            b.setPosition((c * 37 % 900).toFloat(), (c * 53 % 2200).toFloat())
            b.name = "Control $c"
            b.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {}
            })
            repeat(3) { k ->
                val child = Actor()
                child.setSize(50f, 20f)
                child.name = "part $k"
                b.addActor(child)
            }
            stage.addActor(b)
        }
        // Keep TextButton import used (real buttons need fonts; skipped headless)
        @Suppress("unused")
        val _ref: TextButton? = null
        return stage
    }

    @Test
    @RedirectOutput(RedirectPolicy.Show)
    fun benchmarkWorstCase() {
        for ((tiles, controls) in listOf(1000 to 200, 4000 to 400, 10000 to 800)) {
            val stage = buildStage(tiles, controls)
            repeat(2) { AccessibilitySnapshot.collect(stage) }
            val runs = 5
            var nodes = 0
            val start = System.nanoTime()
            repeat(runs) { nodes = AccessibilitySnapshot.collect(stage).size }
            val avgMs = (System.nanoTime() - start) / 1_000_000.0 / runs
            println("SNAPSHOT_BENCH tiles=$tiles controls=$controls nodes=$nodes avgMs=%.1f".format(avgMs))
        }
    }
}
