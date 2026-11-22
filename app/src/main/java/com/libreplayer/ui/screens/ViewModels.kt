package com.libreplayer.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.PlaylistRepository
import com.libreplayer.data.repository.PlaylistSong
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.ThemeMode
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.ImportedRoot
import com.libreplayer.data.repository.UserPlaylist
import com.libreplayer.data.repository.LibrarySyncState
import com.libreplayer.media.playback.PlaybackConnection
import com.libreplayer.library.semantics.buildBrowseAlbums
import com.libreplayer.library.semantics.buildBrowseArtists
import com.libreplayer.library.semantics.sortSongs
import com.libreplayer.settings.SettingsRepository
import com.libreplayer.util.LibrarySearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val searchQuery = MutableStateFlow("")

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppSettings(),
    )

    private val sortOptionFlow = settings
        .map { currentSettings -> currentSettings.defaultSortOption }
        .distinctUntilChanged()

    private val browseFlow = libraryRepository.observeSongs()
        .map { songs ->
            LibraryBrowseSnapshot(
                songs = songs,
                albums = enrichAlbumsWithArtwork(
                    albums = buildBrowseAlbums(songs),
                    songs = songs,
                ),
                artists = enrichArtistsWithArtwork(
                    artists = buildBrowseArtists(songs),
                    songs = songs,
                ),
            )
        }
        .flowOn(Dispatchers.Default)

    private val metadataFlow = combine(
        libraryRepository.observeFavorites(),
        libraryRepository.observeRecentlyPlayed(),
        libraryRepository.observeImportedRoots(),
        libraryRepository.syncState,
    ) { favorites, recentlyPlayed, importedRoots, syncState ->
        LibraryMetaSnapshot(favorites, recentlyPlayed, importedRoots, syncState)
    }

    private val orderedLibraryFlow = combine(
        browseFlow,
        sortOptionFlow,
    ) { browse, sortOption ->
        LibraryOrderedSnapshot(
            songs = sortSongs(browse.songs, sortOption),
            albums = browse.albums,
            artists = browse.artists,
        )
    }.flowOn(Dispatchers.Default).shareIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        replay = 1,
    )

    private val catalogPresentationFlow = combine(
        orderedLibraryFlow,
        playlistRepository.observePlaylists(),
    ) { library, playlists ->
        LibraryCatalogPresentation(
            songs = library.songs,
            albums = library.albums,
            artists = library.artists,
            playlists = playlists,
        )
    }.flowOn(Dispatchers.Default)

    private val searchPresentationFlow = combine(
        orderedLibraryFlow,
        searchQuery,
    ) { library, query ->
        LibrarySearchPresentation(
            query = query,
            result = LibrarySearchEngine.search(
                query = query,
                songs = library.songs,
                albums = library.albums,
                artists = library.artists,
            ),
        )
    }.flowOn(Dispatchers.Default)

    private val metadataPresentationFlow = combine(
        metadataFlow,
        sortOptionFlow,
    ) { metadata, sortOption ->
        LibraryMetaPresentation(
            favorites = sortSongs(metadata.favorites, sortOption),
            recentlyPlayed = metadata.recentlyPlayed,
            importedRoots = metadata.importedRoots,
            syncState = metadata.syncState,
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<LibraryScreenState> = combine(
        catalogPresentationFlow,
        metadataPresentationFlow,
        searchPresentationFlow,
    ) { catalog, metadata, search ->
        LibraryScreenState(
            isLoading = metadata.syncState.isLoading,
            permissionRequired = metadata.syncState.permissionRequired,
            errorMessage = metadata.syncState.errorMessage,
            songs = catalog.songs,
            albums = catalog.albums,
            artists = catalog.artists,
            playlists = catalog.playlists,
            favorites = metadata.favorites,
            recentlyPlayed = metadata.recentlyPlayed,
            searchQuery = search.query,
            searchResult = search.result,
            importedRoots = metadata.importedRoots,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LibraryScreenState(),
    )

    suspend fun refreshWhileForeground(changes: Flow<Unit>) {
        com.libreplayer.library.scanner.collectForegroundRefreshes(changes) {
            libraryRepository.rescanLibrary()
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch {
            libraryRepository.rescanLibrary()
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun clearSearch() {
        searchQuery.value = ""
    }

    fun setDefaultSortOption(option: LibrarySortOption) {
        viewModelScope.launch {
            settingsRepository.setDefaultSortOption(option)
        }
    }

    fun toggleFavorite(songId: String) {
        viewModelScope.launch {
            libraryRepository.toggleFavorite(songId)
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name)
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch {
            playlistRepository.renamePlaylist(playlistId, name)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId)
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: String) {
        viewModelScope.launch {
            playlistRepository.addSongs(playlistId, listOf(songId))
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: String) {
        viewModelScope.launch {
            playlistRepository.removeSong(playlistId, songId)
        }
    }

    fun moveSongInPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            playlistRepository.moveSong(playlistId, fromIndex, toIndex)
        }
    }

    fun addImportedRoot(uri: Uri, displayName: String) {
        viewModelScope.launch {
            libraryRepository.addImportedRoot(uri.toString(), displayName)
        }
    }

    fun removeImportedRoot(uri: String) {
        viewModelScope.launch {
            libraryRepository.removeImportedRoot(uri)
        }
    }

    fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSong>> =
        playlistRepository.observePlaylistSongs(playlistId)

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LibrePlayerApplication
                LibraryViewModel(
                    libraryRepository = application.appContainer.libraryRepository,
                    playlistRepository = application.appContainer.playlistRepository,
                    settingsRepository = application.appContainer.settingsRepository,
                )
            }
        }
    }
}

class PlaybackViewModel(
    private val libraryRepository: LibraryRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {
    val uiState: StateFlow<PlaybackUiState> = combine(
        playbackConnection.uiState,
        libraryRepository.observeSongs(),
    ) { playbackState, songs ->
        if (!playbackState.isConnected) return@combine playbackState
        val songMap = songs.associateBy { it.id }
        playbackState.copy(
            currentSong = playbackState.currentSong?.let { songMap[it.id] ?: it },
            queue = playbackState.queue.map { song -> songMap[song.id] ?: song },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlaybackUiState(),
    )

    fun playSong(queue: List<Song>, startIndex: Int) {
        if (startIndex !in queue.indices) return
        viewModelScope.launch {
            playbackConnection.playSong(queue, startIndex)
        }
    }

    fun selectQueueItem(index: Int) = playbackConnection.selectQueueItem(index)

    fun addToQueue(song: Song) {
        viewModelScope.launch {
            playbackConnection.addToQueue(song)
        }
    }

    fun togglePlayPause() = playbackConnection.togglePlayPause()

    fun seekTo(positionMs: Long) = playbackConnection.seekTo(positionMs)

    fun skipNext() = playbackConnection.skipNext()

    fun skipPrevious() = playbackConnection.skipPrevious()

    fun toggleShuffle() {
        playbackConnection.setShuffleEnabled(!uiState.value.shuffleEnabled)
    }

    fun cycleRepeatMode() = playbackConnection.cycleRepeatMode()

    fun toggleFavoriteCurrent() {
        uiState.value.currentSong?.id?.let { songId ->
            viewModelScope.launch {
                libraryRepository.toggleFavorite(songId)
            }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LibrePlayerApplication
                PlaybackViewModel(
                    libraryRepository = application.appContainer.libraryRepository,
                    playbackConnection = application.appContainer.playbackConnection,
                )
            }
        }
    }
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {
    val syncState: StateFlow<LibrarySyncState> = libraryRepository.syncState

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppSettings(),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun setShowFileNamesWhenMetadataMissing(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowFileNamesWhenMetadataMissing(enabled)
        }
    }

    fun setDefaultSortOption(option: LibrarySortOption) {
        viewModelScope.launch {
            settingsRepository.setDefaultSortOption(option)
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch {
            libraryRepository.rescanLibrary()
        }
    }

    fun rebuildLibrary() {
        viewModelScope.launch {
            libraryRepository.rebuildLibrary()
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LibrePlayerApplication
                SettingsViewModel(
                    settingsRepository = application.appContainer.settingsRepository,
                    libraryRepository = application.appContainer.libraryRepository,
                )
            }
        }
    }
}

private data class LibraryBrowseSnapshot(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
)

private data class LibraryOrderedSnapshot(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
)

private data class LibraryCatalogPresentation(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val playlists: List<UserPlaylist>,
)

private data class LibraryMetaSnapshot(
    val favorites: List<Song>,
    val recentlyPlayed: List<Song>,
    val importedRoots: List<ImportedRoot>,
    val syncState: LibrarySyncState,
)

private data class LibraryMetaPresentation(
    val favorites: List<Song>,
    val recentlyPlayed: List<Song>,
    val importedRoots: List<ImportedRoot>,
    val syncState: LibrarySyncState,
)

private data class LibrarySearchPresentation(
    val query: String,
    val result: com.libreplayer.data.repository.SearchResult,
)
