package dev.carapps.car

import android.content.Context
import android.net.Uri

/**
 * Geely's app-store content provider, which feeds the app grid in Geely's launcher.
 *
 * GUESS: the URIs and column names come from the gappstorage app. That app appears to
 * add sideloaded apps to this list so they show in the launcher. This helper only reads.
 * Writing would change the launcher's app list, so we leave that for a deliberate step.
 */
object GeelyAppStore {
    val APP_INFO: Uri = Uri.parse("content://com.geely.appstore.AppstoreProvider/appInfo")
    val RECENT_PLAY: Uri = Uri.parse("content://com.geely.appstore.AppstoreProvider/recentPlay")

    /** Columns seen in gappstorage's copy of the table. */
    val APP_INFO_COLUMNS = listOf(
        "package_name", "apk_name", "apk_icon", "apk_size", "apk_version_name",
        "apk_version_code", "apk_type", "apk_type_name", "display_screen",
    )

    /** Package names currently in the launcher's app list, or null if the provider refused. */
    fun listPackages(context: Context): List<String>? = try {
        context.contentResolver.query(APP_INFO, arrayOf("package_name"), null, null, null)?.use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) c.getString(0)?.let { out.add(it) }
            out
        }
    } catch (_: Throwable) {
        null
    }
}
