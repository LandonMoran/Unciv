package com.unciv.ui.accessibility

import com.unciv.view.CivView
import com.unciv.view.TileView

/**
 * Builds concise TalkBack descriptions for world-map tiles, e.g.
 * "Grassland hill, Forest, Iron, Rome, your city, population 6. Warrior, you, 80 health."
 *
 * Everything is best-effort and wrapped in try/catch by callers: a missing
 * description must never break the snapshot.
 */
object TileDescription {
    fun describe(tileView: TileView, viewingCiv: CivView?): String {
        try {
            if (!tileView.isExplored()) return "Unexplored tile"
            val parts = ArrayList<String>(8)

            val terrain = ArrayList<String>(4)
            try {
                for (t in tileView.getAllTerrains()) {
                    val name = t.name
                    if (name.isNotBlank() && name !in terrain) terrain.add(name)
                }
            } catch (_: Throwable) {
                terrain.add(tileView.baseTerrain)
            }
            if (terrain.isNotEmpty()) parts.add(terrain.joinToString(", "))
            else parts.add(if (tileView.isWater) "Water" else "Land")

            try {
                if (tileView.isAdjacentToRiver()) parts.add("River")
            } catch (_: Throwable) {}

            try {
                val resource = tileView.getViewableResource(viewingCiv)
                if (resource != null) {
                    val amount = try { tileView.resourceAmount } catch (_: Throwable) { 0 }
                    parts.add(if (amount > 1) "${resource.name} ($amount)" else resource.name)
                }
            } catch (_: Throwable) {}

            try {
                val improvement = tileView.getShownImprovement() ?: tileView.improvement
                if (!improvement.isNullOrBlank()) {
                    parts.add(if (tileView.improvementIsPillaged) "$improvement, pillaged" else improvement)
                }
            } catch (_: Throwable) {}

            if (tileView.roadStatus.name != "None") {
                parts.add(if (tileView.roadIsPillaged) "Pillaged road" else tileView.roadStatus.name)
            }

            try {
                val city = tileView.getCity()
                if (city != null) {
                    val cityName = try { city.name } catch (_: Throwable) { "City" }
                    val yours = try {
                        viewingCiv != null && city.owningCiv().civName == viewingCiv.civName
                    } catch (_: Throwable) { false }
                    parts.add(if (yours) "$cityName, your city" else "$cityName")
                } else {
                    try {
                        val owner = tileView.getOwner()
                        if (owner != null) parts.add("Territory of ${owner.civName}")
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}

            try {
                for (unit in tileView.getVisibleUnits()) {
                    val yours = try {
                        viewingCiv != null && unit.civ().civName == viewingCiv.civName
                    } catch (_: Throwable) { false }
                    val who = if (yours) "you" else try { unit.civ().civName } catch (_: Throwable) { "enemy" }
                    val moves = try {
                        val m = unit.currentMovement
                        if (m <= 0.05f) "no moves left" else "${
                            if (m == m.toInt().toFloat()) m.toInt().toString()
                            else "%.1f".format(m)
                        } moves left"
                    } catch (_: Throwable) { "" }
                    val hp = try {
                        val h = unit.unitHealth
                        if (h < 100) ", $h health" else ""
                    } catch (_: Throwable) { "" }
                    val extra = listOf(moves, hp.trimStart(',', ' ')).filter { it.isNotBlank() }
                    val suffix = if (extra.isNotEmpty()) " (${extra.joinToString(", ")})" else ""
                    parts.add("${unit.displayName()}, $who$suffix")
                }
            } catch (_: Throwable) {}

            try {
                if (tileView.isCityCenter()) parts.add("City center")
            } catch (_: Throwable) {}

            if (parts.isEmpty()) return "Tile"
            return parts.joinToString(". ")
        } catch (_: Throwable) {
            return "Map tile"
        }
    }
}
