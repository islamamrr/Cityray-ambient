package dev.carapps.probe

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Finds the head unit's own camera / parking apps, the ones that draw the reverse-camera
 * guide lines, and packs their APK files into one zip so we can study them on a computer
 * (static analysis only, we never run them). Read-only: it copies files, it changes nothing.
 */
object CameraApps {

    /** Words in a package name or label that suggest camera, parking or vehicle-data apps. */
    private val MATCH = Regex(
        "camera|avm|rvc|rvm|apa|evs|surround|360|pano|park|pas\\b|dvr|vision|rearview|reverse|guide|" +
            "vehicle|carservice|carsignal|signal|adas|steer|ecarx\\.car|hvac",
        RegexOption.IGNORE_CASE,
    )

    data class Found(val pkg: String, val label: String, val system: Boolean, val files: List<File>) {
        val bytes: Long get() = files.sumOf { it.length() }
    }

    fun find(context: Context): List<Found> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA).mapNotNull { ai ->
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault("")
            if (!MATCH.containsMatchIn(ai.packageName) && !MATCH.containsMatchIn(label)) return@mapNotNull null
            if (ai.packageName == context.packageName) return@mapNotNull null
            val files = (listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList()))
                .map(::File).filter { it.canRead() }
            Found(ai.packageName, label, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, files)
        }.sortedBy { it.pkg }
    }

    fun describe(list: List<Found>): String = buildString {
        append("CAMERA AND VEHICLE APPS ON THIS HEAD UNIT (${list.size})\n")
        for (f in list) {
            append(String.format(Locale.US, "%s  \"%s\"  %s  %.1f MB  %s\n", f.pkg, f.label,
                if (f.system) "system" else "user", f.bytes / 1e6, f.files.joinToString(" ") { it.path }))
        }
    }

    /**
     * Zips the APKs of [list] into the Download folder (Android 10+) and also into the app's
     * own cache for sharing. Returns the cache file and a readable place name.
     */
    fun zip(context: Context, list: List<Found>, onProgress: (String) -> Unit): Pair<File, String> {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "cityray-camera-apks.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("list.txt"))
            zip.write(describe(list).toByteArray())
            zip.closeEntry()
            for (f in list) for (apk in f.files) {
                onProgress("Packing ${f.pkg} (${apk.name})")
                zip.putNextEntry(ZipEntry("${f.pkg}/${apk.name}"))
                apk.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        var where = out.path
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, out.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { o -> out.inputStream().use { it.copyTo(o) } }
                where = "Download/${out.name}"
            }
        }
        return out to where
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = Uri.parse("content://${context.packageName}.share/${file.name}")
        return Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
