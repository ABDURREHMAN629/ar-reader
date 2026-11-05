package com.ahif.lumen.data.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

data class SavedImage(val uri: Uri, val name: String, val size: Long, val dateAdded: Long)

/** Saves tool output into Pictures/AR Reader/<folder> so it also shows up in the Gallery. */
object MediaSaver {

    private fun relativePath(folder: String) = Environment.DIRECTORY_PICTURES + "/AR Reader/" + folder

    suspend fun save(context: Context, bytes: ByteArray, displayName: String, mime: String, folder: String): Uri? =
        saveWith(context, displayName, mime, folder) { it.write(bytes) }

    /** Streams [file] into the gallery folder, so large outputs never sit fully in memory. */
    suspend fun saveFile(context: Context, file: File, displayName: String, mime: String, folder: String): Uri? =
        saveWith(context, displayName, mime, folder) { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }

    private suspend fun saveWith(
        context: Context,
        displayName: String,
        mime: String,
        folder: String,
        write: (OutputStream) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath(folder))
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            if (uri != null) {
                return@withContext runCatching {
                    resolver.openOutputStream(uri)?.use(write) ?: error("No output stream")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    uri
                }.getOrElse {
                    resolver.delete(uri, null, null)
                    null
                }
            }
            // MediaStore refused this type; with all-files access a plain file write still works.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Environment.isExternalStorageManager()) return@withContext null
        }
        runCatching {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AR Reader/$folder")
            dir.mkdirs()
            val base = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "")
            var file = File(dir, displayName)
            var n = 2
            while (file.exists()) { file = File(dir, "$base ($n).$ext"); n++ }
            file.outputStream().use(write)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            Uri.fromFile(file)
        }.getOrNull()
    }

    suspend fun list(context: Context, folder: String): List<SavedImage> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val out = ArrayList<SavedImage>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED
            )
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf(relativePath(folder) + "%"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        out += SavedImage(
                            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                            name = c.getString(1) ?: "image",
                            size = c.getLong(2),
                            dateAdded = c.getLong(3)
                        )
                    }
                }
            }
            out
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AR Reader/$folder")
            (dir.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .map { SavedImage(Uri.fromFile(it), it.name, it.length(), it.lastModified() / 1000) }
        }
    }

    suspend fun delete(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (uri.scheme == "file") File(uri.path!!).delete()
            else context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }
}
