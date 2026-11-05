package com.ahif.lumen.data.reader

import org.apache.poi.poifs.crypt.CipherAlgorithm
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.crypt.HashAlgorithm
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Encrypts packages with Apache POI (as Office does) and checks [OfficeCrypto] gets them back. */
class OfficeCryptoTest {

    private fun samplePackage(extraBytes: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document>Hello secret world</w:document>".toByteArray())
            zip.putNextEntry(ZipEntry("word/media/blob.bin"))
            zip.write(Random(7).nextBytes(extraBytes)) // incompressible, so the file is really this big
        }
        return bos.toByteArray()
    }

    private fun encrypt(plain: ByteArray, password: String, info: EncryptionInfo): ByteArray {
        val fs = POIFSFileSystem()
        val enc = info.encryptor
        enc.confirmPassword(password)
        enc.getDataStream(fs).use { it.write(plain) }
        return ByteArrayOutputStream().also { fs.writeFilesystem(it) }.toByteArray()
    }

    private fun roundTrip(info: EncryptionInfo, size: Int) {
        val plain = samplePackage(size)
        val file = encrypt(plain, "pässwörd 123", info)
        assertTrue(OfficeCrypto.isEncryptedPackage(file))
        assertArrayEquals(plain, OfficeCrypto.decrypt(file, "pässwörd 123"))
        try {
            OfficeCrypto.decrypt(file, "wrong")
            fail("wrong password accepted")
        } catch (_: OfficeCrypto.WrongPasswordException) {
        }
    }

    @Test fun agileDefault_small() = roundTrip(EncryptionInfo(EncryptionMode.agile), 1000)

    @Test fun agileDefault_multiSegment() = roundTrip(EncryptionInfo(EncryptionMode.agile), 300_000)

    /** Bigger than 109 FAT sectors, so the compound file needs DIFAT sectors. */
    @Test fun agile_needsDifat() = roundTrip(EncryptionInfo(EncryptionMode.agile), 8_000_000)

    @Test fun agile_aes128Sha1() = roundTrip(
        EncryptionInfo(EncryptionMode.agile, CipherAlgorithm.aes128, HashAlgorithm.sha1, 128, 16, null), 50_000
    )

    @Test fun agile_aes192Sha384() = roundTrip(
        EncryptionInfo(EncryptionMode.agile, CipherAlgorithm.aes192, HashAlgorithm.sha384, 192, 16, null), 50_000
    )

    @Test fun standard() = roundTrip(EncryptionInfo(EncryptionMode.standard), 50_000)

    @Test fun plainZipIsNotEncrypted() {
        assertFalse(OfficeCrypto.isEncryptedPackage(samplePackage(100)))
    }
}
