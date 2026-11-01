package dev.carapps.gamekit

object Palette {
    const val BG = 0xFF0B0F18.toInt()
    const val PANEL = 0xFF161D2C.toInt()
    const val PANEL_HI = 0xFF222B3F.toInt()
    const val TEXT = 0xFFF2F4F8.toInt()
    const val MUTED = 0xFF8A93A6.toInt()
    const val ACCENT = 0xFFFF5A36.toInt()
    const val GOLD = 0xFFFFC400.toInt()

    val PLAYERS = intArrayOf(
        0xFF3D8BFF.toInt(), // blue
        0xFFFF4D6A.toInt(), // red
        0xFF35D07F.toInt(), // green
        0xFFB46CFF.toInt(), // purple
        0xFFFF9A2E.toInt(), // orange
        0xFF2ED3D3.toInt(), // teal
    )
    val NAMES = arrayOf("Blue", "Red", "Green", "Purple", "Orange", "Teal")

    /** [color] with its alpha replaced by [a] (0..255). */
    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Mixes [color] toward black by [f] (0 = unchanged, 1 = black). */
    fun darken(color: Int, f: Float): Int {
        val k = 1f - f
        val r = ((color shr 16 and 0xFF) * k).toInt()
        val g = ((color shr 8 and 0xFF) * k).toInt()
        val b = ((color and 0xFF) * k).toInt()
        return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}
