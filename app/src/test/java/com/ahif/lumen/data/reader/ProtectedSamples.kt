package com.ahif.lumen.data.reader

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes password-protected sample files (password "1234") for trying the unlock screen
 * on a phone. Only runs when MAKE_SAMPLES is set; output goes to build/protected-samples.
 */
class ProtectedSamples {

    @Test
    fun write() {
        val dir = System.getenv("MAKE_SAMPLES")
        assumeTrue(dir != null)
        val out = File(dir!!).apply { mkdirs() }

        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 28f)
                cs.newLineAtOffset(72f, 680f)
                cs.showText("Unlocked! Password worked.")
                cs.endText()
            }
            pdf.protect(StandardProtectionPolicy("owner-secret", "1234", AccessPermission()).apply { encryptionKeyLength = 256 })
            pdf.save(File(out, "Protected PDF (1234).pdf"))
        }

        val docx = ByteArrayOutputStream().also { bos ->
            XWPFDocument().use { d ->
                d.createParagraph().createRun().apply { isBold = true; setText("Unlocked! Password worked.") }
                d.createParagraph().createRun().setText("This Word file was protected with the password 1234.")
                d.write(bos)
            }
        }.toByteArray()
        File(out, "Protected Word (1234).docx").writeBytes(officeEncrypt(docx))

        val xlsx = ByteArrayOutputStream().also { bos ->
            XSSFWorkbook().use { wb ->
                val sheet = wb.createSheet("Secret")
                sheet.createRow(0).apply { createCell(0).setCellValue("Item"); createCell(1).setCellValue("Amount") }
                sheet.createRow(1).apply { createCell(0).setCellValue("Unlocked!"); createCell(1).setCellValue(1234.0) }
                wb.write(bos)
            }
        }.toByteArray()
        File(out, "Protected Excel (1234).xlsx").writeBytes(officeEncrypt(xlsx))
    }

    private fun officeEncrypt(plain: ByteArray): ByteArray {
        val fs = POIFSFileSystem()
        val enc = EncryptionInfo(EncryptionMode.agile).encryptor
        enc.confirmPassword("1234")
        enc.getDataStream(fs).use { it.write(plain) }
        return ByteArrayOutputStream().also { fs.writeFilesystem(it) }.toByteArray()
    }
}
