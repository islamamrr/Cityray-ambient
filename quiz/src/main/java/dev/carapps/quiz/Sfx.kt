package dev.carapps.quiz

import android.media.AudioManager
import android.media.ToneGenerator

/** Short beeps from the system tone generator: no audio files needed. */
class Sfx {
    private val tones: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

    private fun play(tone: Int, ms: Int) {
        runCatching { tones?.startTone(tone, ms) }
    }

    fun right() = play(ToneGenerator.TONE_PROP_ACK, 300)
    fun wrong() = play(ToneGenerator.TONE_PROP_NACK, 400)
    fun buzz() = play(ToneGenerator.TONE_PROP_BEEP2, 250)
    fun tick() = play(ToneGenerator.TONE_PROP_BEEP, 60)
    fun win() = play(ToneGenerator.TONE_PROP_PROMPT, 700)

    fun release() {
        runCatching { tones?.release() }
    }
}
