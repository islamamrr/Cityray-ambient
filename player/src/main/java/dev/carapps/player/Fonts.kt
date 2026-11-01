package dev.carapps.player

import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * Sora for headings, DM Sans for text. GitHub Actions downloads both (OFL) into assets
 * at build time; if they're missing the system sans-serif is used instead.
 */
class Fonts(assets: AssetManager) {
    val displayBold = load(assets, "fonts/Sora.ttf", 700) ?: Typeface.create("sans-serif", Typeface.BOLD)
    val displaySemi = load(assets, "fonts/Sora.ttf", 600) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val body = load(assets, "fonts/DMSans.ttf", 400) ?: Typeface.create("sans-serif", Typeface.NORMAL)
    val bodyMedium = load(assets, "fonts/DMSans.ttf", 500) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun load(assets: AssetManager, path: String, weight: Int): Typeface? = try {
        assets.open(path).close()
        Typeface.Builder(assets, path).setFontVariationSettings("'wght' $weight").build()
    } catch (_: Exception) {
        null
    }
}
