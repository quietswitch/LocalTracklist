package com.libreplayer.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.database.entity.AlbumEntity
import com.libreplayer.data.database.entity.ArtistEntity
import com.libreplayer.data.database.entity.ImportedRootEntity
import com.libreplayer.data.database.entity.RecentlyPlayedEntity
import com.libreplayer.data.database.entity.SongEntity
import com.libreplayer.library.scanner.DeviceLibraryScanner
import com.libreplayer.library.scanner.LibraryScanMode
import com.libreplayer.library.scanner.SourceScan
import com.libreplayer.library.scanner.SourceIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LibrarySyncState(
    val isLoading: Boolean = false,
    val permissionRequired: Boolean = false,
    val errorMessage: String? = null,
    val completedSuccessfully: Boolean = false,
)

interface LibraryRepository {
    val syncState: StateFlow<LibrarySyncState>
    fun observeSongs(): Flow<List<Song>>
    fun observeAlbums(): Flow<List<Album>>
    fun observeArtists(): Flow<List<Artist>>
    fun observeFavorites(): Flow<List<Song>>
    fun observeRecentlyPlayed(limit: Int = 25): Flow<List<Song>>
    fun observeImportedRoots(): Flow<List<ImportedRoot>>
    suspend fun refreshLibraryIfNeeded()
    suspend fun rescanLibrary()
    suspend fun rebuildLibrary()
    suspend fun getSongsByIds(ids: List<String>): List<Song>
    suspend fun getSongById(id: String): Song?
    suspend fun getAllSongs(): List<Song>
    suspend fun toggleFavorite(songId: String)
    suspend fun markSongPlayed(songId: String)
    suspend fun addImportedRoot(uri: String, displayName: String)
    suspend fun removeImportedRoot(uri: String)
}

class DefaultLibraryRepository(
    private val database: AppDatabase,
    private val scanner: DeviceLibraryScanner,
    private val refreshStore: LibraryRefreshStore,
) : LibraryRepository {
    private val songDao = database.songDao()
    private val albumDao = database.albumDao()
    private val artistDao = database.artistDao()
    private val importedRootDao = database.importedRootDao()
    private val recentlyPlayedDao = database.recentlyPlayedDao()
    private val refreshMutex = Mutex()

    private val _syncState = MutableStateFlow(LibrarySyncState())
    override val syncState: StateFlow<LibrarySyncState> = _syncState.asStateFlow()

    override fun observeSongs(): Flow<List<Song>> =
        songDao.observeSongs().map { songs -> songs.map(SongEntity::asModel) }

    override fun observeAlbums(): Flow<List<Album>> =
        albumDao.observeAlbums().map { albums -> albums.map(AlbumEntity::asModel) }

    override fun observeArtists(): Flow<List<Artist>> =
        artistDao.observeArtists().map { artists -> artists.map(ArtistEntity::asModel) }

    override fun observeFavorites(): Flow<List<Song>> =
        songDao.observeFavoriteSongs().map { songs -> songs.map(SongEntity::asModel) }

    override fun observeRecentlyPlayed(limit: Int): Flow<List<Song>> =
        combine(
            recentlyPlayedDao.observeRecentlyPlayed(limit),
            songDao.observeSongs(),
        ) { recentlyPlayed, songs ->
            val songMap = songs.associateBy { it.id }
            recentlyPlayed.mapNotNull { item -> songMap[item.songId]?.asModel() }
        }

    override fun observeImportedRoots(): Flow<List<ImportedRoot>> =
        importedRootDao.observeRoots().map { roots -> roots.map(ImportedRootEntity::asModel) }

    override suspend fun refreshLibraryIfNeeded() {
        refreshLibrary(forceRefresh = false, forceRebuild = false)
    }

    override suspend fun rescanLibrary() {
        refreshLibrary(forceRefresh = true, forceRebuild = false)
    }

    override suspend fun rebuildLibrary() {
        refreshLibrary(forceRefresh = true, forceRebuild = true)
    }

    override suspend fun getSongsByIds(ids: List<String>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val songs = songDao.getSongsByIds(ids).associateBy { it.id }
        return ids.mapNotNull { id -> songs[id]?.asModel() }
    }

    override suspend fun getSongById(id: String): Song? = songDao.getSongById(id)?.asModel()

    override suspend fun getAllSongs(): List<Song> =
        songDao.getAvailableSongs().map(SongEntity::asModel)

    override suspend fun toggleFavorite(songId: String) {
        val song = songDao.getSongById(songId) ?: return
        songDao.updateFavorite(songId, !song.isFavorite)
    }

    override suspend fun markSongPlayed(songId: String) {
        recentlyPlayedDao.upsert(
            RecentlyPlayedEntity(
                songId = songId,
                playedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun addImportedRoot(uri: String, displayName: String) {
        importedRootDao.upsertRoot(
            ImportedRootEntity(
                uri = uri,
                displayName = displayName,
                addedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        rescanLibrary()
    }

    override suspend fun removeImportedRoot(uri: String) {
        refreshMutex.withLock {
            database.withTransaction {
                database.reconcileSources(listOf(SourceScan.Detached(SourceIdentity.root(uri))))
                importedRootDao.deleteRoot(uri)
            }
        }
        rescanLibrary()
    }

    private suspend fun refreshLibrary(
        forceRefresh: Boolean,
        forceRebuild: Boolean,
    ) {
        refreshMutex.withLock {
            _syncState.value = LibrarySyncState(isLoading = true)
            try {
                val cachedSongs = songDao.getAllSongs()
                val importedRoots = importedRootDao.getRoots().map { it.uri }
                val lastSuccessfulRefreshAt = refreshStore.lastSuccessfulRefreshAtMillis()
                val needsSourceInitialization = database.sourceDao().getSources().none { it.reconciledAtEpochMillis > 0 }
                if (
                    !shouldRefreshLibrary(
                        forceRefresh = forceRefresh || needsSourceInitialization,
                        cachedSongCount = cachedSongs.size,
                        lastSuccessfulRefreshAtMillis = lastSuccessfulRefreshAt,
                        nowMillis = System.currentTimeMillis(),
                    )
                ) {
                    _syncState.value = LibrarySyncState()
                    return
                }

                val nowMillis = System.currentTimeMillis()
                val scanMode = if (
                    shouldPerformFullReconciliation(
                        forceRebuild = forceRebuild,
                        cachedSongCount = cachedSongs.size,
                        lastFullReconciliationAtMillis = refreshStore.lastFullReconciliationAtMillis(),
                        nowMillis = nowMillis,
                    )
                ) {
                    LibraryScanMode.FULL_REBUILD
                } else {
                    LibraryScanMode.INCREMENTAL
                }
                val result = scanner.scan(
                    importedRoots = importedRoots,
                    cachedSongs = cachedSongs.map(SongEntity::asScannedSong),
                    sources = database.sourceDao().getSources(),
                    memberships = database.sourceDao().getMemberships(),
                    mode = scanMode,
                )
                val failures = result.sources.filterIsInstance<SourceScan.Unavailable>().map { it.reason }.toMutableList()
                for (source in result.sources) {
                    try {
                        database.reconcileSources(listOf(source))
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        failures += failure.message ?: "Source reconciliation failed."
                    }
                }
                if (failures.isEmpty()) {
                    refreshStore.markSuccessfulSync(fullReconciliation = result.full)
                }
                val stats = result.statistics
                Log.i(LIBRARY_SYNC_LOG_TAG,
                    "full=${result.full} elapsedMs=${stats.elapsedMillis} mediaRows=${stats.mediaStoreRowsRead} " +
                        "mediaIdRows=${stats.mediaStoreIdRowsRead} documents=${stats.documentFilesVisited} " +
                        "metadataReads=${stats.documentMetadataReads} metadataFailures=${stats.documentMetadataFailures} " +
                        "reused=${stats.cachedSongsReused} sourceFailures=${failures.size}")
                _syncState.value = LibrarySyncState(
                    errorMessage = failures.firstOrNull(),
                    completedSuccessfully = failures.isEmpty(),
                    permissionRequired = result.sources.filterIsInstance<SourceScan.Unavailable>().any { it.permissionRequired },
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _syncState.value = LibrarySyncState(errorMessage = failure.message ?: "Unable to refresh library.")
            } finally {
                _syncState.value = _syncState.value.copy(isLoading = false)
            }
        }
    }
}
internal fun shouldRefreshLibrary(
    forceRefresh: Boolean,
    cachedSongCount: Int,
    lastSuccessfulRefreshAtMillis: Long,
    nowMillis: Long,
    refreshIntervalMillis: Long = AUTO_REFRESH_INTERVAL_MS,
): Boolean =
    when {
        forceRefresh -> true
        cachedSongCount <= 0 -> true
        lastSuccessfulRefreshAtMillis <= 0L -> true
        nowMillis - lastSuccessfulRefreshAtMillis >= refreshIntervalMillis -> true
        else -> false
    }

private const val AUTO_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val FULL_RECONCILIATION_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L
private const val LIBRARY_SYNC_LOG_TAG = "LibrePlayerLibrarySync"

internal fun shouldPerformFullReconciliation(
    forceRebuild: Boolean,
    cachedSongCount: Int,
    lastFullReconciliationAtMillis: Long,
    nowMillis: Long,
    intervalMillis: Long = FULL_RECONCILIATION_INTERVAL_MS,
): Boolean =
    forceRebuild || cachedSongCount <= 0 || lastFullReconciliationAtMillis <= 0L ||
        nowMillis - lastFullReconciliationAtMillis >= intervalMillis
