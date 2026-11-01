package dev.carapps.hunch

import android.view.View
import dev.carapps.gamekit.GameActivity

class MainActivity : GameActivity() {
    override fun createGameView(): View = HunchView(this)
}
