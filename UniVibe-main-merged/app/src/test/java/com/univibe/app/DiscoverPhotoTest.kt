package com.univibe.app

import com.univibe.app.data.repository.MockProfileRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverPhotoTest {
    @Test
    fun noPhotoByDefault() = runTest {
        assertNull(MockProfileRepository().getMyProfile().discoverPhotoPath)
    }

    @Test
    fun photoCanBeSetReplacedAndRemoved() = runTest {
        val repo = MockProfileRepository()
        repo.setDiscoverPhoto("/files/photos/discover_1.jpg")
        assertEquals("/files/photos/discover_1.jpg", repo.getMyProfile().discoverPhotoPath)
        repo.setDiscoverPhoto("/files/photos/discover_2.jpg")
        assertEquals("/files/photos/discover_2.jpg", repo.getMyProfile().discoverPhotoPath)
        repo.setDiscoverPhoto(null)
        assertNull(repo.getMyProfile().discoverPhotoPath)
    }

    @Test
    fun discoverPhotoIsSeparateFromProfileAvatar() = runTest {
        val repo = MockProfileRepository()
        repo.setDiscoverPhoto("/files/photos/discover_1.jpg")
        assertNull(repo.getMyProfile().avatarPath)
    }
}
