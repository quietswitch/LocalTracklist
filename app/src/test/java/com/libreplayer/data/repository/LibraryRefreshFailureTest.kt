package com.libreplayer.data.repository

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.database.dao.SongDao
import com.libreplayer.library.scanner.DeviceLibraryScanner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class LibraryRefreshFailureTest {
    @Test fun `cache read failure is observable and does not escape manual rescan`() = runBlocking {
        val database = mockk<AppDatabase>()
        val songs = mockk<SongDao>()
        every { database.songDao() } returns songs
        every { database.albumDao() } returns mockk()
        every { database.artistDao() } returns mockk()
        every { database.importedRootDao() } returns mockk()
        every { database.recentlyPlayedDao() } returns mockk()
        coEvery { songs.getAllSongs() } throws IOException("Cannot read saved library")
        val repository = DefaultLibraryRepository(database, mockk<DeviceLibraryScanner>(), mockk<LibraryRefreshStore>())
        repository.rescanLibrary()
        assertThat(repository.syncState.value.isLoading).isFalse()
        assertThat(repository.syncState.value.errorMessage).isEqualTo("Cannot read saved library")
    }
}