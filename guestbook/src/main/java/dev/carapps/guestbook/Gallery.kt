package dev.carapps.guestbook

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Saves pictures to Pictures/Guest Book, so they can be copied off the car with a file explorer. */
object Gallery {

    const val FOLDER = "Guest Book"

    fun save(context: Context, bmp: Bitmap, fileName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("no gallery")
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            dir.mkdirs()
            File(dir, fileName).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        true
    } catch (e: Exception) {
        false
    }

    fun fileName(e: Entry): String {
        val safe = e.name.replace(Regex("[^A-Za-z0-9\\u0600-\\u06FF _-]"), "").trim().ifEmpty { "guest" }
        return "guest-%03d-%s.png".format(e.number, safe)
    }
}
