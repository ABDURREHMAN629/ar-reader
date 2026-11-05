package com.ahif.lumen.data.reader

import android.content.Context
import android.net.Uri
import com.ahif.lumen.data.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Detects password-protected Office files and makes a temporary decrypted copy in the
 * app's private cache for the readers. The copy (and anything the readers extracted
 * from it) is wiped when the reader closes, and again on the next app start in case
 * the app was killed while one was open.
 */
object OfficeUnlock {

    private const val PREFIX = "unlocked_"
    private const val MAX_BYTES = 150L * 1024 * 1024

    sealed interface Check {
        data object Plain : Check
        /** Encrypted; [bytes] is the whole protected file, kept to try passwords against. */
        class Protected(val bytes: ByteArray) : Check
        data object TooLarge : Check
    }

    /** Cheap for normal files: only the first 8 bytes are read unless it looks protected. */
    suspend fun check(context: Context, doc: Document): Check = withContext(Dispatchers.IO) {
        runCatching {
            val head = ByteArray(8)
            val n = context.contentResolver.openInputStream(doc.uri)?.use { it.read(head) } ?: 0
            if (n < 8 || !OfficeCrypto.looksLikeCompoundFile(head)) return@runCatching Check.Plain
            if (doc.sizeBytes > MAX_BYTES) return@runCatching Check.TooLarge
            val bytes = context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                ?: return@runCatching Check.Plain
            if (OfficeCrypto.isEncryptedPackage(bytes)) Check.Protected(bytes) else Check.Plain
        }.getOrDefault(Check.Plain)
    }

    /**
     * Decrypts [bytes] with [password] into a private temp file and returns its uri.
     * Throws [OfficeCrypto.WrongPasswordException] for a wrong password.
     */
    suspend fun unlock(context: Context, doc: Document, bytes: ByteArray, password: String): Uri =
        withContext(Dispatchers.Default) {
            val plain = OfficeCrypto.decrypt(bytes, password)
            withContext(Dispatchers.IO) {
                val ext = doc.name.substringAfterLast('.', "zip").lowercase()
                val file = File(context.cacheDir, PREFIX + Integer.toHexString(doc.id.hashCode()) + "." + ext)
                wipe(context, file) // never reuse readers' caches from an earlier unlock
                file.writeBytes(plain)
                Uri.fromFile(file)
            }
        }

    /** Deletes a decrypted copy and the caches the readers built from it. */
    fun wipe(context: Context, file: File) {
        // Same cache names as OfficeHtml.extractToDir and SpreadsheetParser.read (keyed by uri hash).
        val h = Uri.fromFile(file).hashCode()
        File(context.cacheDir, "office_html_$h").deleteRecursively()
        File(context.cacheDir, "xlsx_$h.xlsx").delete()
        file.delete()
    }

    fun wipe(context: Context, uri: Uri) {
        uri.path?.let { wipe(context, File(it)) }
    }

    /** Removes leftovers from a run that ended while a protected file was open. */
    fun wipeAll(context: Context) {
        context.cacheDir.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { wipe(context, it) }
    }
}
