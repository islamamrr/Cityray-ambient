package dev.carapps.surprise

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** A few tiny synthesized sound effects. No files, no permissions. */
class Sfx {

    private val rate = 22050

    private val pickup = make(0.14f) { t, p -> sweep(t, 700f, 1500f, p) * (1f - p) }
    private val crash = make(0.55f) { _, p -> (Random.nextFloat() * 2f - 1f) * exp(-p * 5f) }
    private val beep = make(0.09f) { t, p -> sin(2 * PI * 880 * t).toFloat() * (1f - p) }
    private val go = make(0.35f) { t, p -> sin(2 * PI * 1320 * t).toFloat() * (1f - p) }
    private val over = make(0.9f) { t, p -> sweep(t, 600f, 140f, p) * (1f - p) }

    fun pickup() = play(pickup)
    fun crash() = play(crash)
    fun beep() = play(beep)
    fun go() = play(go)
    fun over() = play(over)

    fun release() {
        listOf(pickup, crash, beep, go, over).forEach { t -> runCatching { t?.release() } }
    }

    private fun sweep(t: Float, from: Float, to: Float, p: Float): Float {
        val f = from + (to - from) * p
        return sin(2 * PI * f * t).toFloat()
    }

    private fun make(seconds: Float, wave: (t: Float, progress: Float) -> Float): AudioTrack? = runCatching {
        val n = (rate * seconds).toInt()
        val samples = ShortArray(n) { i ->
            val t = i.toFloat() / rate
            (wave(t, i.toFloat() / n).coerceIn(-1f, 1f) * 0.5f * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, n)
        track
    }.getOrNull()

    private fun play(track: AudioTrack?) {
        track ?: return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        }
    }
}
