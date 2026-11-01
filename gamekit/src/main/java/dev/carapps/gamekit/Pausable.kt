package dev.carapps.gamekit

/** A game view that can be frozen while the app is in the background. */
interface Pausable {
    fun setPaused(paused: Boolean)
}
