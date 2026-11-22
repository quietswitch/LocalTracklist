package com.libreplayer.refresh

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.repository.*
import com.libreplayer.library.metadata.AudioMetadataReader
import com.libreplayer.library.scanner.*
import com.libreplayer.settings.SettingsRepository
import com.libreplayer.ui.screens.LibraryViewModel
import com.libreplayer.ui.screens.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Real scanner -> source transactions -> Room Flow -> running library/search ViewModel.
 * Only the external provider is replaced. SQLite evaluates the production query predicates.
 */
@RunWith(AndroidJUnit4::class)
class LibraryRefreshIntegrationTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test fun manualRescanRecoversEligibleIdWithUnchangedGeneration() = runBlocking {
        fixture { f ->
            f.initialSync()
            f.provider.add(2, "New FLAC", rowGeneration = 9)
            // Visibility is independent of a cached metadata watermark.
            f.manualRescan()
            f.assertVisible("New FLAC")
            assertEquals(2, f.db.songDao().getAvailableSongs().size)
            assertEquals(1, f.provider.metadataRows)
        }
    }

    @Test fun manualRescanRecoversEligibleIdOmittedFromGenerationDelta() = runBlocking {
        fixture { f ->
            f.initialSync()
            f.provider.add(2, "New FLAC", rowGeneration = 9)
            f.provider.generation = 11
            f.manualRescan()
            f.assertVisible("New FLAC")
            assertEquals(11L, f.db.sourceDao().getSources().single().generation)
        }
    }

    @Test fun normalGenerationDeltaUpdatesOpenSearchAndPreservesReferences() = runBlocking {
        fixture { f ->
            f.initialSync()
            val old = f.db.songDao().getAvailableSongs().single()
            f.repository.toggleFavorite(old.id)
            val playlist = f.playlists.createPlaylist("Keep")
            f.playlists.addSongs(playlist, listOf(old.id))
            f.repository.markSongPlayed(old.id)
            f.provider.add(2, "New FLAC", rowGeneration = 11)
            f.provider.generation = 11
            f.manualRescan()
            f.assertVisible("New FLAC")
            assertTrue(f.db.songDao().getSongById(old.id)!!.isFavorite)
            assertEquals(old.id, f.db.playlistDao().getPlaylistSongsNow(playlist).single().song.id)
            assertEquals(old.id, f.db.recentlyPlayedDao().observeRecentlyPlayed().first().single().songId)
            val state = withTimeout(5000) { f.viewModel.state.first { it.songs.size == 2 && it.albums.sumOf { a -> a.songCount } == 2 } }
            assertEquals(2, state.artists.sumOf { it.songCount })
            assertEquals(1, f.provider.metadataRows)
            f.provider.resetCounts()
            f.manualRescan()
            assertEquals(0, f.provider.metadataRows)
            assertEquals(1, f.provider.idQueries)
        }
    }

    @Test fun documentedEligibilityExcludesFiveSecondsAndNonMusicButIncludesThirtySeconds() = runBlocking {
        fixture { f ->
            f.initialSync()
            f.provider.add(2, "Refresh Test 001", rowGeneration = 11, duration = 5000)
            f.provider.add(3, "Not music", rowGeneration = 11, isMusic = 0)
            f.provider.add(4, "New FLAC", rowGeneration = 11, duration = 30000)
            f.provider.generation = 11
            f.manualRescan()
            f.assertVisible("New FLAC")
            assertEquals(setOf("Existing", "New FLAC"), f.repository.getAllSongs().map { it.title }.toSet())
        }
    }

    @Test fun providerFailureRetainsCheckpointAndRetryDiscoversNewSong() = runBlocking {
        fixture { f ->
            f.initialSync()
            val before = f.db.sourceDao().getSources()
            f.provider.add(2, "New FLAC", rowGeneration = 11)
            f.provider.generation = 11
            f.provider.failQueries = true
            f.repository.rescanLibrary()
            assertNotNull(f.repository.syncState.value.errorMessage)
            assertFalse(f.repository.syncState.value.isLoading)
            assertEquals(before, f.db.sourceDao().getSources())
            assertEquals(1, f.repository.getAllSongs().size)
            f.provider.failQueries = false
            f.manualRescan()
            f.assertVisible("New FLAC")
        }
    }

    @Test fun roomAbortRollsBackSongsAndCheckpointAndRetrySucceeds() = runBlocking {
        fixture { f ->
            f.initialSync()
            val before = f.db.sourceDao().getSources()
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_checkpoint BEFORE UPDATE ON library_sources BEGIN SELECT RAISE(ABORT, 'injected checkpoint failure'); END")
            f.provider.add(2, "New FLAC", rowGeneration = 11)
            f.provider.generation = 11
            f.repository.rescanLibrary()
            assertNotNull(f.repository.syncState.value.errorMessage)
            assertEquals(before, f.db.sourceDao().getSources())
            assertEquals(1, f.repository.getAllSongs().size)
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_checkpoint")
            f.manualRescan()
            f.assertVisible("New FLAC")
        }
    }

    @Test fun closedRoomCancellationDoesNotLeaveLoading() = runBlocking {
        fixture { f ->
            f.initialSync()
            f.db.close()
            try { f.repository.rescanLibrary() } catch (_: CancellationException) { }
            assertFalse(f.repository.syncState.value.isLoading)
        }
    }

    @Test fun repeatedManualRequestsSerializeAndKeepNewSongVisible() = runBlocking {
        fixture { f ->
            f.initialSync()
            f.provider.add(2, "New FLAC", rowGeneration = 11)
            f.provider.generation = 11
            coroutineScope { List(4) { async { f.repository.rescanLibrary() } }.awaitAll() }
            f.assertVisible("New FLAC")
            assertEquals(2, f.repository.getAllSongs().size)
            assertFalse(f.repository.syncState.value.isLoading)
        }
    }

    @Test fun unchangedImportedRootRetainsCachedMetadataDuringAddition() = runBlocking {
        fixture { f ->
            f.initialSync()
            val root = SourceIdentity.root("content://refresh.fixture/tree/root")
            val uri = root.locator + "/document/cached"
            val song = ScannedSong("cached", SongSourceType.DOCUMENT, uri, "Saved SAF", "Test", "Test",
                60000, 1, 1, 2026, 2, 2, "cached.flac", Uri.parse(uri).path, "audio/flac", uri)
            f.db.importedRootDao().upsertRoot(com.libreplayer.data.database.entity.ImportedRootEntity(root.locator, "Saved root", 1))
            f.db.reconcileSources(listOf(SourceScan.Complete(root, listOf(SourceObservation(checkNotNull(SourceIdentity.documentKey(uri)), song)))))
            f.provider.add(2, "New FLAC", rowGeneration = 11)
            f.provider.generation = 11
            f.manualRescan()
            f.assertVisible("New FLAC")
            assertTrue(f.repository.getAllSongs().any { it.title == "Saved SAF" })
            val scan = f.scanner.scan(listOf(root.locator), f.db.songDao().getAllSongs().map { it.asScannedSong() },
                f.db.sourceDao().getSources(), f.db.sourceDao().getMemberships(), LibraryScanMode.INCREMENTAL)
            assertEquals(0, scan.statistics.documentMetadataReads)
            assertEquals(1, scan.statistics.documentFilesVisited)
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.start(); block(f) } finally { f.close() }
    }

    private inner class Fixture {
        val provider = RefreshProvider()
        private val resolver = android.test.mock.MockContentResolver(app).apply {
            provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media;refresh.fixture" })
            addProvider("media", provider)
            addProvider("refresh.fixture", provider)
        }
        val context = object : ContextWrapper(app) {
            override fun getContentResolver(): ContentResolver = resolver
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val scanner = DeviceLibraryScanner(context, AudioMetadataReader(context))
        val store = LibraryRefreshStore(app)
        val repository = DefaultLibraryRepository(db, scanner, store)
        val playlists = DefaultPlaylistRepository(db.playlistDao(), db.songDao(), transaction = { block -> db.withTransaction { block() } })
        private val owners = ViewModelStore()
        lateinit var viewModel: LibraryViewModel
        private lateinit var settingsViewModel: SettingsViewModel

        suspend fun start() {
            provider.add(1, "Existing", rowGeneration = 10)
            withContext(Dispatchers.Main) {
                viewModel = LibraryViewModel(repository, playlists, SettingsRepository(app))
                settingsViewModel = SettingsViewModel(SettingsRepository(app), repository)
                owners.put("library", viewModel)
                owners.put("settings", settingsViewModel)
                viewModel.setSearchQuery("New FLAC")
                viewModel.viewModelScope.launch { repository.refreshLibraryIfNeeded() }
            }
        }

        suspend fun initialSync() {
            withTimeout(10000) { viewModel.state.first { (it.songs.size == 1 && !it.isLoading) || it.errorMessage != null } }
            assertNull(repository.syncState.value.errorMessage)
            provider.resetCounts()
        }

        suspend fun manualRescan() {
            // Same handler used by Settings -> Rescan, in the same ViewModel/session.
            val queriesBefore = provider.queryCount
            withContext(Dispatchers.Main) { settingsViewModel.rescanLibrary() }
            withTimeout(10000) {
                while (provider.queryCount == queriesBefore || repository.syncState.value.isLoading) delay(10)
            }
            assertNull(repository.syncState.value.errorMessage)
        }

        suspend fun assertVisible(title: String) {
            val state = withTimeout(5000) { viewModel.state.first { snapshot ->
                snapshot.songs.any { it.title == title } && snapshot.searchResult.songs.any { it.title == title }
            } }
            assertEquals(1, state.songs.count { it.title == title })
            assertEquals(1, state.searchResult.songs.count { it.title == title })
        }

        suspend fun close() {
            withContext(Dispatchers.Main) { owners.clear() }
            db.close()
            provider.close()
        }
    }
}

/** In-process provider boundary. Real SQLite evaluates scanner projection, selection and args. */
private class RefreshProvider : ContentProvider() {
    val data = SQLiteDatabase.create(null).apply {
        execSQL("CREATE TABLE media (_id INTEGER PRIMARY KEY, title TEXT, artist TEXT, album TEXT, duration INTEGER, track INTEGER, year INTEGER, date_added INTEGER, date_modified INTEGER, _display_name TEXT, relative_path TEXT, mime_type TEXT, album_id INTEGER, generation_modified INTEGER, is_music INTEGER)")
    }
    var generation = 10L
    var failQueries = false
    var metadataRows = 0
    var idQueries = 0
    @Volatile var queryCount = 0

    fun resetCounts() { metadataRows = 0; idQueries = 0; queryCount = 0 }
    fun add(id: Long, title: String, rowGeneration: Long, duration: Long = 60000, isMusic: Int = 1) {
        data.execSQL("INSERT INTO media VALUES (?, ?, 'Test', 'Test', ?, 1, 2026, 1, 1, ?, 'Music/', 'audio/flac', 1, ?, ?)",
            arrayOf<Any>(id, title, duration, "$id.flac", rowGeneration, isMusic))
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        Bundle().apply { putString(Intent.EXTRA_TEXT, "fixture-version"); putLong(Intent.EXTRA_INDEX, generation) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queryCount++
        if (failQueries) throw IOException("Injected provider query failure")
        if (uri.authority == "refresh.fixture") {
            return MatrixCursor(projection).apply {
                addRow(projection!!.map { column -> when(column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "cached"
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "cached.flac"
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> "audio/flac"
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 2000L
                    else -> null
                } })
            }
        }
        val cursor = data.query("media", projection, selection, selectionArgs, null, null, sortOrder)
        if (projection!!.size == 1) idQueries++ else metadataRows += cursor.count
        return cursor
    }
    fun close() = data.close()
    override fun onCreate() = true
    override fun getType(uri: Uri): String = "audio/flac"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
}