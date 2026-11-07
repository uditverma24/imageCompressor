package com.imagecompressor.app.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

object Storage {
    const val FOLDER = "ImageCompressor"

    /** Builds the saved file name: "photo.png" -> "photo_compressed.jpg". */
    fun outputName(originalName: String): String {
        val base = originalName.substringBeforeLast('.', originalName)
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifEmpty { "image" }
        return "${base}_compressed.jpg"
    }

    /** Saves [src] into Pictures/ImageCompressor through MediaStore. Returns the saved Uri. */
    fun saveToGallery(context: Context, src: File, displayName: String): Uri {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open output")
                out.use { o -> src.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Android 8-9: write into the public Pictures folder (needs WRITE_EXTERNAL_STORAGE).
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER
            )
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create folder")
            val base = displayName.substringBeforeLast('.')
            var target = File(dir, displayName)
            var i = 1
            while (target.exists()) {
                target = File(dir, "${base}_$i.jpg")
                i++
            }
            src.copyTo(target)
            MediaScannerConnection.scanFile(
                context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null
            )
            return Uri.fromFile(target)
        }
    }

    /** Opens the system share sheet for one or more compressed files. */
    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share compressed images"))
    }
}
