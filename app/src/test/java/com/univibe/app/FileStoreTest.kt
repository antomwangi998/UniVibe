package com.univibe.app

import com.univibe.app.data.media.FileStore
import com.univibe.app.features.chats.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStoreTest {
    @Test fun theExtensionDecidesTheType_notThePhonesGuess() {
        assertEquals("application/pdf", FileStore.typeFor("Notes.PDF", "application/octet-stream"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", FileStore.typeFor("essay.docx", null))
        assertEquals("text/csv", FileStore.typeFor("grades.csv", "text/plain"))
    }

    @Test fun aKnownTypeWithoutAnExtensionIsStillAccepted() {
        assertEquals("application/pdf", FileStore.typeFor("lecture", "application/pdf"))
    }

    @Test fun nothingThatCanRunIsEverAllowed() {
        for (name in listOf("setup.apk", "tool.exe", "run.sh", "page.html", "image.svg", "bundle.zip", "app.jar", "script.js", "archive.rar")) {
            assertNull(name, FileStore.typeFor(name, null))
        }
        assertNull(FileStore.typeFor("evil", "application/vnd.android.package-archive"))
        assertNull(FileStore.typeFor("evil", "application/octet-stream"))
        assertFalse(FileStore.TYPES.keys.any { it in setOf("apk", "exe", "sh", "bat", "jar", "zip", "html", "svg") })
    }

    @Test fun theSizeLimitMatchesTheServer() {
        assertEquals(25L * 1024 * 1024, FileStore.MAX_BYTES)
    }

    @Test fun namesLoseFoldersAndHiddenDirectionTricks() {
        assertEquals("notes.pdf", FileStore.cleanName("/storage/emulated/0/Download/notes.pdf"))
        assertEquals("notes.pdf", FileStore.cleanName("C:\\Users\\me\\notes.pdf"))
        val tricky = FileStore.cleanName("invoice\u202Efdp.exe")
        assertFalse(tricky.contains('\u202e'))
        assertEquals("linebreak.pdf", FileStore.cleanName("line\nbreak\u0000.pdf"))
        assertEquals("document", FileStore.cleanName("   "))
        assertTrue(FileStore.cleanName("Résumé – final.docx").startsWith("Résumé"))
    }

    @Test fun sizesReadNaturally() {
        assertEquals("812 B", formatBytes(812))
        assertEquals("340 KB", formatBytes(340L * 1024))
        assertTrue(formatBytes(1_258_291).endsWith(" MB"))
    }
}
