package com.libreplayer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.data.repository.PlaylistSong
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.UserPlaylist
import com.libreplayer.library.semantics.albumBrowseSongs
import com.libreplayer.library.semantics.artistBrowseAlbums
import com.libreplayer.library.semantics.artistBrowseSongs
import com.libreplayer.library.semantics.sortAlbumTracks
import com.libreplayer.ui.components.AlbumRow
import com.libreplayer.ui.components.ArtistRow
import com.libreplayer.ui.components.DividerItem
import com.libreplayer.ui.components.EmptyState
import com.libreplayer.ui.components.SectionHeader
import com.libreplayer.ui.components.SongRow
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    libraryState: LibraryScreenState,
    settings: AppSettings,
    onBack: () -> Unit,
    searchInput: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenAudioDetails: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    playlists: List<UserPlaylist>,
    onAddSongToPlaylist: (Long, String) -> Unit,
) {
    var selectedSongForPlaylist by remember { mutableStateOf<Song?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search songs, albums, artists") },
                        singleLine = true,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        val contentPadding = secondaryScreenContentPadding(scaffoldPadding = padding)
        if (searchInput.text.isBlank()) {
            EmptyState(
                title = "Search your library",
                message = "Search works across songs, albums, and artists stored on this device.",
                modifier = Modifier.padding(contentPadding),
            )
        } else if (libraryState.searchQuery == searchInput.text) {
            // Results belong to the completed query, not necessarily the latest edit.
            LazyColumn(contentPadding = contentPadding) {
                if (libraryState.searchResult.songs.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    itemsIndexed(libraryState.searchResult.songs, key = { _, song -> song.id }) { index, song ->
                        SongRow(
                            title = displayTitle(song, settings),
                            subtitle = displaySubtitle(song),
                            durationMs = song.durationMs,
                            artworkUri = song.artworkUri,
                            fallbackArtworkUri = song.contentUri,
                            sourceRevisionEpochSeconds = song.dateModifiedEpochSeconds,
                            trailingContent = {
                                SongOverflowMenu(
                                    song = song,
                                    onToggleFavorite = onToggleFavorite,
                                    onAddToQueue = onAddToQueue,
                                    onAddToPlaylist = { selectedSongForPlaylist = song },
                                    onOpenDetails = onOpenAudioDetails,
                                    onOpenAlbum = onOpenAlbum,
                                    onOpenArtist = onOpenArtist,
                                )
                            },
                            onClick = { onPlaySongs(libraryState.searchResult.songs, index) },
                        )
                        DividerItem()
                    }
                }
                if (libraryState.searchResult.albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    itemsIndexed(libraryState.searchResult.albums, key = { _, album -> album.id }) { _, album ->
                        AlbumRow(album = album, onClick = { onOpenAlbum(album.id) })
                        DividerItem()
                    }
                }
                if (libraryState.searchResult.artists.isNotEmpty()) {
                    item { SectionHeader("Artists") }
                    itemsIndexed(libraryState.searchResult.artists, key = { _, artist -> artist.id }) { _, artist ->
                        ArtistRow(artist = artist, onClick = { onOpenArtist(artist.id) })
                        DividerItem()
                    }
                }
                if (
                    libraryState.searchResult.songs.isEmpty() &&
                    libraryState.searchResult.albums.isEmpty() &&
                    libraryState.searchResult.artists.isEmpty()
                ) {
                    item {
                        EmptyState(
                            title = "No matches",
                            message = "Try a different artist, album, title, or file name.",
                        )
                    }
                }
            }
        }
    }

    selectedSongForPlaylist?.let { song ->
        PlaylistPickerDialog(
            song = song,
            playlists = playlists,
            onDismiss = { selectedSongForPlaylist = null },
            onSelectPlaylist = { playlistId ->
                onAddSongToPlaylist(playlistId, song.id)
                selectedSongForPlaylist = null
            },
        )
    }
}

@Composable
fun AlbumDetailScreen(
    albumId: String,
    libraryState: LibraryScreenState,
    settings: AppSettings,
    onBack: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenAudioDetails: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    playlists: List<UserPlaylist>,
    onAddSongToPlaylist: (Long, String) -> Unit,
) {
    // Navigation has already decoded String route arguments exactly once.
    val songs = remember(libraryState.songs, albumId) {
        sortAlbumTracks(albumBrowseSongs(libraryState.songs, albumId))
    }
    val album = libraryState.albums.firstOrNull { it.id == albumId }
    DetailSongsScreen(
        title = album?.title ?: "Album",
        subtitle = album?.artist ?: "${songs.size} songs",
        songs = songs,
        settings = settings,
        playlists = playlists,
        onBack = onBack,
        onPlaySongs = onPlaySongs,
        onAddToQueue = onAddToQueue,
        onToggleFavorite = onToggleFavorite,
        onOpenAudioDetails = onOpenAudioDetails,
        onOpenAlbum = onOpenAlbum,
        onOpenArtist = onOpenArtist,
        onAddSongToPlaylist = onAddSongToPlaylist,
    )
}

@Composable
fun ArtistDetailScreen(
    artistId: String,
    libraryState: LibraryScreenState,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    // Navigation has already decoded String route arguments exactly once.
    val songs = remember(libraryState.songs, artistId) {
        artistBrowseSongs(libraryState.songs, artistId)
    }
    val albums = remember(libraryState.albums, songs) {
        artistBrowseAlbums(libraryState.albums, songs)
    }
    val artist = libraryState.artists.firstOrNull { it.id == artistId }

    ArtistCollectionScreen(
        title = artist?.name ?: "Artist",
        albums = albums,
        songs = songs,
        onBack = onBack,
        onOpenAlbum = onOpenAlbum,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistCollectionScreen(
    title: String,
    albums: List<com.libreplayer.data.repository.Album>,
    songs: List<Song>,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            text = "${albums.size} albums - ${songs.size} songs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        val contentPadding = secondaryScreenContentPadding(scaffoldPadding = padding)

        if (songs.isEmpty()) {
            EmptyState(
                title = "No music",
                message = "This artist does not have any readable local tracks right now.",
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            LazyColumn(contentPadding = contentPadding) {
                if (albums.isNotEmpty()) {
                    item {
                        SectionHeader("Albums")
                    }

                    itemsIndexed(
                        items = albums,
                        key = { _, album -> album.id },
                    ) { _, album ->
                        AlbumRow(
                            album = album,
                            onClick = { onOpenAlbum(album.id) },
                        )
                        DividerItem()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSongsScreen(
    title: String,
    subtitle: String,
    songs: List<Song>,
    settings: AppSettings,
    playlists: List<UserPlaylist>,
    onBack: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenAudioDetails: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onAddSongToPlaylist: (Long, String) -> Unit,
) {
    var selectedSongForPlaylist by remember { mutableStateOf<Song?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val contentPadding = secondaryScreenContentPadding(scaffoldPadding = padding)
        if (songs.isEmpty()) {
            EmptyState(
                title = "No songs",
                message = "This collection does not have any readable local tracks right now.",
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            LazyColumn(contentPadding = contentPadding) {
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        title = displayTitle(song, settings),
                        subtitle = displaySubtitle(song),
                        durationMs = song.durationMs,
                        artworkUri = song.artworkUri,
                        fallbackArtworkUri = song.contentUri,
                        sourceRevisionEpochSeconds = song.dateModifiedEpochSeconds,
                        trailingContent = {
                            SongOverflowMenu(
                                song = song,
                                onToggleFavorite = onToggleFavorite,
                                onAddToQueue = onAddToQueue,
                                onAddToPlaylist = { selectedSongForPlaylist = song },
                                onOpenDetails = onOpenAudioDetails,
                                onOpenAlbum = onOpenAlbum,
                                onOpenArtist = onOpenArtist,
                            )
                        },
                        onClick = { onPlaySongs(songs, index) },
                    )
                    DividerItem()
                }
            }
        }
    }

    selectedSongForPlaylist?.let { song ->
        PlaylistPickerDialog(
            song = song,
            playlists = playlists,
            onDismiss = { selectedSongForPlaylist = null },
            onSelectPlaylist = { playlistId ->
                onAddSongToPlaylist(playlistId, song.id)
                selectedSongForPlaylist = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playlistName: String,
    playlistSongs: Flow<List<PlaylistSong>>? = null,
    smartSongs: List<Song> = emptyList(),
    settings: AppSettings,
    onBack: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRemoveSong: ((Long, String) -> Unit)? = null,
    onMoveSong: ((Long, Int, Int) -> Unit)? = null,
) {
    val collectedSongsState = playlistSongs?.collectAsStateWithLifecycle(initialValue = emptyList())
    val collectedSongs = collectedSongsState?.value.orEmpty()
    val songs = if (playlistSongs != null) collectedSongs.map { it.song } else smartSongs

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlistName) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val contentPadding = secondaryScreenContentPadding(scaffoldPadding = padding)
        if (songs.isEmpty()) {
            EmptyState(
                title = "No songs in this playlist",
                message = if (playlistId >= 0L) "Add songs from the library to start using this playlist." else "This smart collection is empty right now.",
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            LazyColumn(contentPadding = contentPadding) {
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        title = displayTitle(song, settings),
                        subtitle = displaySubtitle(song),
                        durationMs = song.durationMs,
                        artworkUri = song.artworkUri,
                        fallbackArtworkUri = song.contentUri,
                        sourceRevisionEpochSeconds = song.dateModifiedEpochSeconds,
                        trailingContent = {
                            PlaylistSongOverflowMenu(
                                editable = playlistId >= 0L && onRemoveSong != null && onMoveSong != null,
                                canMoveUp = index > 0,
                                canMoveDown = index < songs.lastIndex,
                                isFavorite = song.isFavorite,
                                onToggleFavorite = { onToggleFavorite(song.id) },
                                onMoveUp = { onMoveSong?.invoke(playlistId, index, index - 1) },
                                onMoveDown = { onMoveSong?.invoke(playlistId, index, index + 1) },
                                onRemove = { onRemoveSong?.invoke(playlistId, song.id) },
                            )
                        },
                        onClick = { onPlaySongs(songs, index) },
                    )
                    DividerItem()
                }
            }
        }
    }
}
