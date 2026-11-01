package dev.carapps.tumbler

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The safe's sounds, synthesized at startup into small WAV files and played through a
 * SoundPool, so many clicks can overlap with low delay. No bundled audio, no permissions.
 */
class Sounds(context: Context) {

    private val rate = 22050
    private val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    var muted = false

    private val tick = load("tick", 0.03f) { t, p ->
        val n = Random.nextFloat() * 2f - 1f
        (n * 0.5f + sin(2 * PI * 3400 * t).toFloat() * 0.6f) * exp(-p * 9f)
    }
    private val thud = load("thud", 0.07f) { t, p ->
        val n = Random.nextFloat() * 2f - 1f
        (sin(2 * PI * 210 * t).toFloat() * 0.9f + n * 0.35f) * exp(-p * 6f)
    }
    private val clunk = load("clunk", 0.35f) { t, p ->
        val f = 140f - 70f * p
        val body = sin(2 * PI * f * t).toFloat() * exp(-p * 5f)
        val ring = sin(2 * PI * 1870 * t).toFloat() * 0.25f * exp(-p * 8f)
        body + ring
    }
    private val rattle = load("rattle", 0.22f) { t, p ->
        val bursts = ((p * 3f) % 1f)
        val n = Random.nextFloat() * 2f - 1f
        (n * 0.6f + sin(2 * PI * 900 * t).toFloat() * 0.3f) * exp(-bursts * 14f)
    }
    private val jam = load("jam", 0.4f) { t, p ->
        val sq = if (sin(2 * PI * 95 * t) > 0) 0.7f else -0.7f
        sq * (1f - p)
    }
    private val alarm = load("alarm", 1.6f) { t, _ ->
        val f = if ((t * 4).toInt() % 2 == 0) 880f else 660f
        val sq = if (sin(2 * PI * f * t) > 0) 0.55f else -0.55f
        sq
    }
    private val fanfare = load("fanfare", 1.2f) { t, p ->
        val notes = floatArrayOf(523f, 659f, 784f, 1046f)
        val i = (p * 4f).toInt().coerceIn(0, 3)
        val local = (p * 4f) - i
        val tone = sin(2 * PI * notes[i] * t).toFloat() + 0.3f * sin(2 * PI * notes[i] * 2 * t).toFloat()
        tone * 0.6f * exp(-local * 2.5f) * (if (i == 3) 1f - local * 0.3f else 1f)
    }
    private val beep = load("beep", 0.08f) { t, p -> sin(2 * PI * 990 * t).toFloat() * (1f - p) * 0.6f }

    /** A dial click. [strength] 0..1 makes it heavier and louder near the gate. */
    fun tick(strength: Float, heavy: Boolean) {
        val s = strength.coerceIn(0f, 1f)
        play(tick, 0.16f + 0.84f * s.pow(1.4f), 1.3f - 0.55f * s)
        if (s > 0.35f) play(thud, (s - 0.35f) * 1.2f, 1.2f - 0.3f * s)
        if (heavy) play(thud, 1f, 0.8f)
    }

    fun clunk() = play(clunk, 1f, 1f)
    fun rattle() = play(rattle, 0.9f, 1f)
    fun jam() = play(jam, 1f, 1f)
    fun alarm() = play(alarm, 0.8f, 1f)
    fun fanfare() = play(fanfare, 0.9f, 1f)
    fun beep() = play(beep, 0.7f, 1f)

    fun release() = runCatching { pool.release() }

    private fun play(id: Int, volume: Float, rateMul: Float) {
        if (muted || id == 0) return
        val v = volume.coerceIn(0f, 1f)
        runCatching { pool.play(id, v, v, 1, 0, rateMul.coerceIn(0.5f, 2f)) }
    }

    private fun load(name: String, seconds: Float, wave: (t: Float, progress: Float) -> Float): Int = runCatching {
        val n = (rate * seconds).toInt()
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            val s = (wave(t, i.toFloat() / n).coerceIn(-1f, 1f) * 0.8f * Short.MAX_VALUE).toInt()
            pcm.putShort(s.toShort())
        }
        val file = File(dir, "$name.wav")
        FileOutputStream(file).use { out ->
            out.write(wavHeader(n * 2))
            out.write(pcm.array())
        }
        pool.load(file.absolutePath, 1)
    }.getOrDefault(0)

    private fun wavHeader(dataBytes: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataBytes); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes)
        return b.array()
    }
}
