package com.ahif.lumen.data.reader

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Opens password-protected .docx/.xlsx/.pptx files ("Encrypt with password" in Office).
 * Such a file is not a zip but an OLE compound file holding an `EncryptionInfo` stream and
 * an `EncryptedPackage` stream (MS-OFFCRYPTO). Supports Agile encryption (Office 2010+,
 * AES + SHA-1/256/384/512) and Standard encryption (Office 2007, AES-128 ECB + SHA-1).
 * The password is only used in memory and never stored.
 */
object OfficeCrypto {

    class WrongPasswordException : Exception("Wrong password")
    class UnsupportedEncryptionException(msg: String) : Exception(msg)

    private val CFB_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )

    /** True if [head] (the first bytes of a file) starts like an OLE compound file. */
    fun looksLikeCompoundFile(head: ByteArray): Boolean =
        head.size >= 8 && (0 until 8).all { head[it] == CFB_SIGNATURE[it] }

    /** True if [file] is an encrypted Office package (vs. a legacy .doc/.xls that happens to be OLE). */
    fun isEncryptedPackage(file: ByteArray): Boolean = runCatching {
        looksLikeCompoundFile(file) && CompoundFile(file).let {
            it.stream("EncryptionInfo") != null && it.stream("EncryptedPackage") != null
        }
    }.getOrDefault(false)

    /** Returns the decrypted package (a normal .docx/.xlsx/.pptx zip). */
    fun decrypt(file: ByteArray, password: String): ByteArray {
        val cfb = CompoundFile(file)
        val info = cfb.stream("EncryptionInfo") ?: throw UnsupportedEncryptionException("No EncryptionInfo")
        val pkg = cfb.stream("EncryptedPackage") ?: throw UnsupportedEncryptionException("No EncryptedPackage")
        val bb = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
        val major = bb.short.toInt()
        val minor = bb.short.toInt()
        return when {
            major == 4 && minor == 4 -> decryptAgile(info.copyOfRange(8, info.size), pkg, password)
            minor == 2 && major in 2..4 -> decryptStandard(info, pkg, password)
            else -> throw UnsupportedEncryptionException("Encryption version $major.$minor")
        }
    }

    // ---------------- Agile (Office 2010+) ----------------

    private val BLOCK_VERIFIER_INPUT = bytes(0xfe, 0xa7, 0xd2, 0x76, 0x3b, 0x4b, 0x9e, 0x79)
    private val BLOCK_VERIFIER_VALUE = bytes(0xd7, 0xaa, 0x0f, 0x6d, 0x30, 0x61, 0x34, 0x4e)
    private val BLOCK_KEY_VALUE = bytes(0x14, 0x6e, 0x0b, 0xe7, 0xab, 0xac, 0xd0, 0xd6)

    private fun decryptAgile(xml: ByteArray, pkg: ByteArray, password: String): ByteArray {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val keyData = doc.getElementsByTagNameNS("*", "keyData").item(0) as? org.w3c.dom.Element
            ?: throw UnsupportedEncryptionException("No keyData")
        val encryptors = doc.getElementsByTagNameNS("*", "encryptedKey")
        val pwKey = (0 until encryptors.length).map { encryptors.item(it) as org.w3c.dom.Element }
            .firstOrNull { it.hasAttribute("encryptedKeyValue") }
            ?: throw UnsupportedEncryptionException("Not password-encrypted")

        fun org.w3c.dom.Element.b64(name: String) = Base64.getDecoder().decode(getAttribute(name))
        fun org.w3c.dom.Element.int(name: String) = getAttribute(name).toInt()
        listOf(keyData, pwKey).forEach {
            if (!it.getAttribute("cipherAlgorithm").equals("AES", ignoreCase = true)) {
                throw UnsupportedEncryptionException("Cipher ${it.getAttribute("cipherAlgorithm")}")
            }
        }

        // Password -> key-encryption keys.
        val hashAlg = javaHash(pwKey.getAttribute("hashAlgorithm"))
        val salt = pwKey.b64("saltValue")
        val keyBytes = pwKey.int("keyBits") / 8
        val blockSize = pwKey.int("blockSize")
        var h = digest(hashAlg, salt, password.toByteArray(Charsets.UTF_16LE))
        val spin = pwKey.int("spinCount")
        val md = MessageDigest.getInstance(hashAlg)
        val iter = ByteArray(4)
        for (i in 0 until spin) {
            le32(i, iter)
            md.update(iter); md.update(h)
            h = md.digest()
        }
        fun keyFor(block: ByteArray) = fit(digest(hashAlg, h, block), keyBytes, 0x36)
        val iv = fit(salt, blockSize, 0x36)

        // Check the password before decrypting anything big.
        val verifierInput = aesCbc(keyFor(BLOCK_VERIFIER_INPUT), iv, pwKey.b64("encryptedVerifierHashInput"))
            .copyOf(pwKey.int("saltSize"))
        val verifierHash = aesCbc(keyFor(BLOCK_VERIFIER_VALUE), iv, pwKey.b64("encryptedVerifierHashValue"))
            .copyOf(pwKey.int("hashSize"))
        if (!MessageDigest.isEqual(digest(hashAlg, verifierInput), verifierHash)) throw WrongPasswordException()

        val secret = aesCbc(keyFor(BLOCK_KEY_VALUE), iv, pwKey.b64("encryptedKeyValue"))
            .copyOf(keyData.int("keyBits") / 8)

        // The package is encrypted in 4096-byte segments, each with its own IV.
        val dataHash = javaHash(keyData.getAttribute("hashAlgorithm"))
        val dataSalt = keyData.b64("saltValue")
        val dataBlock = keyData.int("blockSize")
        val size = ByteBuffer.wrap(pkg, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (size < 0 || size > pkg.size) throw UnsupportedEncryptionException("Bad package size")
        val out = ByteArray(size.toInt())
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val key = SecretKeySpec(secret, "AES")
        var segment = 0
        var pos = 8
        var written = 0
        while (written < out.size && pos < pkg.size) {
            val len = minOf(4096, pkg.size - pos).let { it - it % 16 }
            if (len <= 0) break
            le32(segment, iter)
            val segIv = fit(digest(dataHash, dataSalt, iter), dataBlock, 0x36)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(segIv))
            val plain = cipher.doFinal(pkg, pos, len)
            val n = minOf(plain.size, out.size - written)
            System.arraycopy(plain, 0, out, written, n)
            written += n
            pos += len
            segment++
        }
        return out
    }

    // ---------------- Standard (Office 2007) ----------------

    private fun decryptStandard(info: ByteArray, pkg: ByteArray, password: String): ByteArray {
        val bb = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        val headerSize = bb.int
        val headerStart = bb.position()
        bb.int // flags
        bb.int // sizeExtra
        val algId = bb.int
        bb.int // hash alg (SHA-1)
        val keyBits = bb.int.let { if (it == 0) 128 else it }
        if (algId !in setOf(0x660E, 0x660F, 0x6610)) throw UnsupportedEncryptionException("Cipher 0x${algId.toString(16)}")
        bb.position(headerStart + headerSize)
        val saltSize = bb.int
        val salt = ByteArray(saltSize).also { bb.get(it) }
        val encVerifier = ByteArray(16).also { bb.get(it) }
        val verifierHashSize = bb.int
        val encVerifierHash = ByteArray(32).also { bb.get(it) }

        var h = digest("SHA-1", salt, password.toByteArray(Charsets.UTF_16LE))
        val iter = ByteArray(4)
        val md = MessageDigest.getInstance("SHA-1")
        for (i in 0 until 50000) {
            le32(i, iter)
            md.update(iter); md.update(h)
            h = md.digest()
        }
        le32(0, iter)
        val hFinal = digest("SHA-1", h, iter)
        val buf1 = ByteArray(64) { 0x36 }
        val buf2 = ByteArray(64) { 0x5C }
        for (i in hFinal.indices) {
            buf1[i] = (buf1[i].toInt() xor hFinal[i].toInt()).toByte()
            buf2[i] = (buf2[i].toInt() xor hFinal[i].toInt()).toByte()
        }
        val key = (digest("SHA-1", buf1) + digest("SHA-1", buf2)).copyOf(keyBits / 8)

        val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }
        val verifier = ecb.doFinal(encVerifier)
        val verifierHash = ecb.doFinal(encVerifierHash).copyOf(verifierHashSize)
        if (!MessageDigest.isEqual(digest("SHA-1", verifier), verifierHash)) throw WrongPasswordException()

        val size = ByteBuffer.wrap(pkg, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val body = pkg.size - 8
        val plain = ecb.doFinal(pkg, 8, body - body % 16)
        if (size < 0 || size > plain.size) throw UnsupportedEncryptionException("Bad package size")
        return plain.copyOf(size.toInt())
    }

    // ---------------- helpers ----------------

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun le32(v: Int, out: ByteArray) {
        out[0] = v.toByte(); out[1] = (v shr 8).toByte(); out[2] = (v shr 16).toByte(); out[3] = (v shr 24).toByte()
    }

    private fun javaHash(name: String): String = when (name.uppercase().replace("-", "")) {
        "SHA1" -> "SHA-1"
        "SHA256" -> "SHA-256"
        "SHA384" -> "SHA-384"
        "SHA512" -> "SHA-512"
        "MD5" -> "MD5"
        else -> throw UnsupportedEncryptionException("Hash $name")
    }

    private fun digest(alg: String, vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance(alg).run { parts.forEach { update(it) }; digest() }

    /** Truncates or pads [b] (with [pad]) to exactly [size] bytes. */
    private fun fit(b: ByteArray, size: Int, pad: Int): ByteArray =
        if (b.size >= size) b.copyOf(size) else b + ByteArray(size - b.size) { pad.toByte() }

    private fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data, 0, data.size - data.size % 16)
        }
}

/** Minimal read-only reader for OLE/CFB compound files (MS-CFB): just enough to pull out streams. */
internal class CompoundFile(private val data: ByteArray) {
    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize = 1 shl buf.getShort(0x1E).toInt()
    private val miniSectorSize = 1 shl buf.getShort(0x20).toInt()
    private val miniCutoff = buf.getInt(0x38).toLong() and 0xFFFFFFFFL
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val miniStream: ByteArray

    private class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        require(sectorSize in 128..65536 && miniSectorSize in 16..sectorSize) { "Not a compound file" }
        // FAT sector numbers: 109 in the header, the rest in a chain of DIFAT sectors.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) buf.getInt(0x4C + i * 4).let { if (it >= 0) fatSectors += it }
        var difat = buf.getInt(0x44)
        val perDifat = sectorSize / 4 - 1
        var guard = 0
        while (difat >= 0 && guard++ < 100_000) {
            val off = offset(difat)
            for (i in 0 until perDifat) buf.getInt(off + i * 4).let { if (it >= 0) fatSectors += it }
            difat = buf.getInt(off + perDifat * 4)
        }
        val perSector = sectorSize / 4
        fat = IntArray(fatSectors.size * perSector)
        fatSectors.forEachIndexed { s, sector ->
            val off = offset(sector)
            for (i in 0 until perSector) fat[s * perSector + i] = buf.getInt(off + i * 4)
        }
        val mf = readChain(buf.getInt(0x3C), -1)
        miniFat = IntArray(mf.size / 4) { ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN).getInt(it * 4) }

        val dir = readChain(buf.getInt(0x30), -1)
        val dbuf = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        entries = (0 until dir.size / 128).map { i ->
            val base = i * 128
            val nameLen = (dbuf.getShort(base + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
            val name = String(dir, base, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            val sizeLow = dbuf.getInt(base + 0x78).toLong() and 0xFFFFFFFFL
            val sizeHigh = if (sectorSize == 512) 0L else dbuf.getInt(base + 0x7C).toLong() and 0xFFFFFFFFL
            Entry(name, dir[base + 0x42].toInt(), dbuf.getInt(base + 0x74), sizeLow or (sizeHigh shl 32))
        }
        val root = entries.firstOrNull { it.type == 5 }
        miniStream = if (root != null && root.size > 0) readChain(root.start, root.size) else ByteArray(0)
    }

    /** Contents of the stream called [name], or null if there is none. */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        return if (e.size < miniCutoff) readMini(e.start, e.size) else readChain(e.start, e.size)
    }

    private fun offset(sector: Int): Int = (sector + 1) * sectorSize

    private fun readChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && guard++ < 10_000_000) {
            val off = offset(s)
            if (off + sectorSize > data.size) {
                out.write(data, off, (data.size - off).coerceAtLeast(0)); break
            }
            out.write(data, off, sectorSize)
            if (size >= 0 && out.size() >= size) break
            s = if (s < fat.size) fat[s] else -2
        }
        val bytes = out.toByteArray()
        return if (size >= 0 && bytes.size > size) bytes.copyOf(size.toInt()) else bytes
    }

    private fun readMini(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && out.size() < size && guard++ < 10_000_000) {
            val off = s * miniSectorSize
            if (off + miniSectorSize > miniStream.size) break
            out.write(miniStream, off, miniSectorSize)
            s = if (s < miniFat.size) miniFat[s] else -2
        }
        return out.toByteArray().copyOf(size.toInt())
    }
}
