package dev.carapps.sparkduel

import android.view.View
import dev.carapps.gamekit.GameActivity

class MainActivity : GameActivity() {
    override fun createGameView(): View = DuelView(this)
}
