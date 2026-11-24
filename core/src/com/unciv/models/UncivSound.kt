package com.unciv.models

/**
 * Represents an Unciv Sound, either from a predefined set or custom with a specified filename.
 */
data class UncivSound(
    /** The base filename without extension. */
    val fileName: String
) {
    /** For deserialization - used in [GameSettingsMultiplayer][com.unciv.models.metadata.GameSettings.GameSettingsMultiplayer] */
    @Suppress("unused")
    private constructor() : this("")

    companion object {
        val Bombard = UncivSound("bombard")
        val Chimes = UncivSound("chimes")
        val Choir = UncivSound("choir")
        val Click = UncivSound("click")
        val Coin = UncivSound("coin")
        val Construction = UncivSound("construction")
        val Fire = UncivSound("fire")
        val Fortify = UncivSound("fortify")
        val Notification1 = UncivSound("notification1")
        val Notification2 = UncivSound("notification2")
        val Paper = UncivSound("paper")
        val Policy = UncivSound("policy")
        val Promote = UncivSound("promote")
        val Setup = UncivSound("setup")
        val Silent = UncivSound("")
        val Slider = UncivSound("slider")
        val Swap = UncivSound("swap")
        val TalkBackAttack = UncivSound("talkback_attack")
        val TalkBackCity = UncivSound("talkback_city")
        val TalkBackError = UncivSound("talkback_error")
        val TalkBackMove = UncivSound("talkback_move")
        val TalkBackSelect = UncivSound("talkback_select")
        val TalkBackTech = UncivSound("talkback_tech")
        val TalkBackTurn = UncivSound("talkback_turn")
        val Upgrade = UncivSound("upgrade")
        val Whoosh = UncivSound("whoosh")

        /** Explicit registry keeps sound discovery independent of full Kotlin reflection. */
        val builtInSounds = listOf(
            Bombard, Chimes, Choir, Click, Coin, Construction, Fire, Fortify,
            Notification1, Notification2, Paper, Policy, Promote, Setup, Silent,
            Slider, Swap,
            TalkBackAttack, TalkBackCity, TalkBackError, TalkBackMove,
            TalkBackSelect, TalkBackTech, TalkBackTurn,
            Upgrade, Whoosh
        )
    }
}
