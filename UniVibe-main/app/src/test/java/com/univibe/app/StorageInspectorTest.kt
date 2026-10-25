package com.univibe.app

import com.univibe.app.data.storage.StorageInspector
import com.univibe.app.data.storage.formatBytes
import java.io.File
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageInspectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(dir: File, name: String, bytes: Int) = File(dir, name).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }

    private fun inspector(cache: File, files: File) = StorageInspector(cache, files) { 5_000L to 20_000L }

    @Test fun measuresEachFolderFromRealFiles() = runTest {
        val cache = tmp.newFolder("cache"); val files = tmp.newFolder("files")
        write(cache, "image_cache/a.bin", 1000); write(cache, "image_cache/b.bin", 500)
        write(cache, "osmdroid/tiles/t1", 300)
        write(cache, "voice_1.m4a", 200)
        write(files, "photos/p1.jpg", 4000); write(files, "videos/v1.mp4", 6000)
        write(files, "datastore/settings.pb", 50) // not media: must not be counted as photos or videos
        val s = inspector(cache, files).measure()
        assertEquals(1500, s.imageCacheBytes)
        assertEquals(300, s.mapCacheBytes)
        assertEquals(200, s.otherCacheBytes)
        assertEquals(2000, s.cacheBytes)
        assertEquals(10_000, s.mediaBytes)
        assertEquals(5_000, s.deviceAvailableBytes)
        assertEquals(20_000, s.deviceTotalBytes)
    }

    @Test fun missingFoldersCountAsZero() = runTest {
        val s = inspector(File(tmp.root, "nope"), File(tmp.root, "none")).measure()
        assertEquals(0, s.cacheBytes); assertEquals(0, s.mediaBytes)
    }

    @Test fun clearingTheCacheFreesItAndNeverTouchesUserMedia() = runTest {
        val cache = tmp.newFolder("cache"); val files = tmp.newFolder("files")
        write(cache, "image_cache/a.bin", 1000); write(cache, "osmdroid/tiles/t1", 300); write(cache, "x.tmp", 100)
        val photo = write(files, "photos/p1.jpg", 4000)
        val video = write(files, "videos/v1.mp4", 6000)
        val freed = inspector(cache, files).clearCache()
        assertEquals(1400, freed)
        assertTrue("the cache folder itself stays", cache.exists())
        assertEquals(0, StorageInspector.sizeOf(cache))
        assertTrue(photo.exists()); assertTrue(video.exists())
    }

    @Test fun clearingAnEmptyCacheFreesNothing() = runTest {
        assertEquals(0, inspector(tmp.newFolder("c"), tmp.newFolder("f")).clearCache())
    }

    @Test fun sizesAreShownLikeAndroidDoes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536, Locale.US))
        assertEquals("23.4 MB", formatBytes((23.4 * 1024 * 1024).toLong(), Locale.US))
        assertEquals("1.20 GB", formatBytes((1.2 * 1024 * 1024 * 1024).toLong(), Locale.US))
    }
}
