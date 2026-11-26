package com.libreplayer.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.libreplayer.library.scanner.observeMediaStoreChanges
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.ReportDrawnWhen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.libreplayer.app.AppContainer
import com.libreplayer.data.repository.SmartPlaylistType
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.ui.components.MiniPlayer
import com.libreplayer.ui.screens.AlbumDetailScreen
import com.libreplayer.ui.screens.AudioDetailsRoute
import com.libreplayer.ui.screens.ArtistDetailScreen
import com.libreplayer.ui.screens.LibraryViewModel
import com.libreplayer.ui.screens.NowPlayingScreen
import com.libreplayer.ui.screens.PlaybackQueueScreen
import com.libreplayer.ui.screens.PlaybackViewModel
import com.libreplayer.ui.screens.PlaylistDetailScreen
import com.libreplayer.ui.screens.PlaylistsScreen
import com.libreplayer.ui.screens.SearchScreen
import com.libreplayer.ui.screens.SettingsScreen
import com.libreplayer.ui.screens.SettingsViewModel
import com.libreplayer.ui.screens.SongsAlbumsArtistsScreen
import com.libreplayer.ui.screens.SongsSection
import com.libreplayer.ui.theme.LibrePlayerTheme

@Composable
fun LibrePlayerApp(appContainer: AppContainer) {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory())
    val playbackViewModel: PlaybackViewModel = viewModel(factory = PlaybackViewModel.factory())
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory())

    val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val syncState by settingsViewModel.syncState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    LaunchedEffect(lifecycleOwner, libraryViewModel, context) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            libraryViewModel.refreshWhileForeground(observeMediaStoreChanges(context.contentResolver))
        }
    }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    ReportDrawnWhen {
        isLibraryStartupReadyForFullyDrawn(libraryState)
    }

    LibrePlayerTheme(themeMode = settings.themeMode) {
        Scaffold(
            bottomBar = {
                BottomBarContent(
                    currentRoute = currentRoute,
                    navController = navController,
                    playbackViewModel = playbackViewModel,
                )
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = AppRoute.Albums.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(AppRoute.Albums.route) {
                    SongsAlbumsArtistsScreen(
                        title = "Albums",
                        section = SongsSection.ALBUMS,
                        libraryState = libraryState,
                        settings = settings,
                        onSearch = { navController.navigate(AppRoute.Search.route) },
                        onSortChange = libraryViewModel::setDefaultSortOption,
                        onGrantPermissionRescan = libraryViewModel::rescanLibrary,
                        onToggleFavorite = libraryViewModel::toggleFavorite,
                        onPlaySongs = playbackViewModel::playSong,
                        onAddToQueue = playbackViewModel::addToQueue,
                        onOpenAudioDetails = { navController.navigate(AppRoute.AudioDetails.create(it)) },
                        onOpenAlbum = { navController.navigate(AppRoute.AlbumDetail.create(it)) },
                        onOpenArtist = { navController.navigate(AppRoute.ArtistDetail.create(it)) },
                        playlists = libraryState.playlists,
                        onAddSongToPlaylist = libraryViewModel::addSongToPlaylist,
                        contentPadding = PaddingValues(0.dp),
                    )
                }
                composable(AppRoute.Artists.route) {
                    SongsAlbumsArtistsScreen(
                        title = "Artists",
                        section = SongsSection.ARTISTS,
                        libraryState = libraryState,
                        settings = settings,
                        onSearch = { navController.navigate(AppRoute.Search.route) },
                        onSortChange = libraryViewModel::setDefaultSortOption,
                        onGrantPermissionRescan = libraryViewModel::rescanLibrary,
                        onToggleFavorite = libraryViewModel::toggleFavorite,
                        onPlaySongs = playbackViewModel::playSong,
                        onAddToQueue = playbackViewModel::addToQueue,
                        onOpenAudioDetails = { navController.navigate(AppRoute.AudioDetails.create(it)) },
                        onOpenAlbum = { navController.navigate(AppRoute.AlbumDetail.create(it)) },
                        onOpenArtist = { navController.navigate(AppRoute.ArtistDetail.create(it)) },
                        playlists = libraryState.playlists,
                        onAddSongToPlaylist = libraryViewModel::addSongToPlaylist,
                        contentPadding = PaddingValues(0.dp),
                    )
                }
                composable(AppRoute.Playlists.route) {
                    PlaylistsScreen(
                        libraryState = libraryState,
                        onSearch = { navController.navigate(AppRoute.Search.route) },
                        onCreatePlaylist = libraryViewModel::createPlaylist,
                        onRenamePlaylist = libraryViewModel::renamePlaylist,
                        onDeletePlaylist = libraryViewModel::deletePlaylist,
                        onOpenPlaylist = { navController.navigate(AppRoute.PlaylistDetail.create(it)) },
                        onOpenSmartPlaylist = { navController.navigate(AppRoute.SmartPlaylistDetail.create(it.name)) },
                        contentPadding = PaddingValues(0.dp),
                    )
                }
                composable(AppRoute.Settings.route) {
                    SettingsScreen(
                        settings = settings,
                        importedRoots = libraryState.importedRoots,
                        syncState = syncState,
                        onSearch = { navController.navigate(AppRoute.Search.route) },
                        onThemeModeChange = settingsViewModel::setThemeMode,
                        onShowFileNamesChange = settingsViewModel::setShowFileNamesWhenMetadataMissing,
                        onDefaultSortChange = settingsViewModel::setDefaultSortOption,
                        onRescan = settingsViewModel::rescanLibrary,
                        onRebuild = settingsViewModel::rebuildLibrary,
                        onAddImportedRoot = libraryViewModel::addImportedRoot,
                        onRemoveImportedRoot = libraryViewModel::removeImportedRoot,
                        contentPadding = PaddingValues(0.dp),
                    )
                }
                composable(AppRoute.Search.route) {
                    SearchScreen(
                        libraryState = libraryState,
                        settings = settings,
                        onBack = navController::navigateUp,
                        searchInput = libraryViewModel.searchInput,
                        onQueryChange = libraryViewModel::updateSearchInput,
                        onToggleFavorite = libraryViewModel::toggleFavorite,
                        onPlaySongs = playbackViewModel::playSong,
                        onAddToQueue = playbackViewModel::addToQueue,
                        onOpenAudioDetails = { navController.navigate(AppRoute.AudioDetails.create(it)) },
                        onOpenAlbum = { navController.navigate(AppRoute.AlbumDetail.create(it)) },
                        onOpenArtist = { navController.navigate(AppRoute.ArtistDetail.create(it)) },
                        playlists = libraryState.playlists,
                        onAddSongToPlaylist = libraryViewModel::addSongToPlaylist,
                    )
                }
                composable(
                    route = AppRoute.AlbumDetail.route,
                    arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val albumId = backStackEntry.arguments?.getString("albumId").orEmpty()
                    AlbumDetailScreen(
                        albumId = albumId,
                        libraryState = libraryState,
                        settings = settings,
                        onBack = navController::navigateUp,
                        onPlaySongs = playbackViewModel::playSong,
                        onAddToQueue = playbackViewModel::addToQueue,
                        onToggleFavorite = libraryViewModel::toggleFavorite,
                        onOpenAudioDetails = { navController.navigate(AppRoute.AudioDetails.create(it)) },
                        onOpenAlbum = { navController.navigate(AppRoute.AlbumDetail.create(it)) },
                        onOpenArtist = { navController.navigate(AppRoute.ArtistDetail.create(it)) },
                        playlists = libraryState.playlists,
                        onAddSongToPlaylist = libraryViewModel::addSongToPlaylist,
                    )
                }
                composable(
                    route = AppRoute.ArtistDetail.route,
                    arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val artistId = backStackEntry.arguments?.getString("artistId").orEmpty()
                    ArtistDetailScreen(
                        artistId = artistId,
                        libraryState = libraryState,
                        onBack = navController::navigateUp,
                        onOpenAlbum = { navController.navigate(AppRoute.AlbumDetail.create(it)) },
                    )
                }
                composable(
                    route = AppRoute.PlaylistDetail.route,
                    arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                ) { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: -1L
                    PlaylistDetailScreen(
                        playlistId = playlistId,
                        playlistName = libraryState.playlists.firstOrNull { it.id == playlistId }?.name ?: "Playlist",
                        playlistSongs = libraryViewModel.observePlaylistSongs(playlistId),
                        settings = settings,
                        onBack = navController::navigateUp,
                        onPlaySongs = playbackViewModel::playSong,
                        onToggleFavorite = libraryViewModel::toggleFavorite,
                        onRemoveSong = libraryViewModel::removeSongFromPlaylist,
                        onMoveSong = libraryViewModel::moveSongInPlaylist,
                    )
                }
                composable(
                    route = AppRoute.SmartPlaylistDetail.route,
                    arguments = listOf(navArgument("type") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val type = backStackEntry.arguments?.getString("type").orEmpty()
                    val smartType = runCatching { SmartPlaylistType.valueOf(type) }
                        .getOrDefault(SmartPlaylistType.FAVORITES)
                    PlaylistDetailScreen(
                        playlistId = -1L,
                        playlistName = if (smartType == SmartPlaylistType.FAVORITES) "Favorites" else "Recently played",
                        smartSongs = if (smartType == SmartPlaylistType.FAVORITES) {
                            libraryState.favorites
                        } else {
                            libraryState.recentlyPlayed
                        },
                        settings = settings,
                        onBack = navController::navigateUp,
                        onPlaySongs = playbackViewModel::playSong,
                        onToggleFavorite = libraryViewModel::toggleFavorite,
                    )
                }
                composable(AppRoute.NowPlaying.route) {
                    val playbackState by playbackViewModel.uiState.collectAsStateWithLifecycle()
                    NowPlayingScreen(
                        playbackState = playbackState,
                        settings = settings,
                        onBack = navController::navigateUp,
                        onTogglePlayPause = playbackViewModel::togglePlayPause,
                        onSkipNext = playbackViewModel::skipNext,
                        onSkipPrevious = playbackViewModel::skipPrevious,
                        onSeekTo = playbackViewModel::seekTo,
                        onToggleShuffle = playbackViewModel::toggleShuffle,
                        onCycleRepeatMode = playbackViewModel::cycleRepeatMode,
                        onToggleFavorite = playbackViewModel::toggleFavoriteCurrent,
                        onOpenQueue = { navController.navigate(AppRoute.Queue.route) },
                        onOpenAudioDetails = { navController.navigate(AppRoute.AudioDetails.create(it)) },
                    )
                }
                composable(AppRoute.Queue.route) {
                    val playbackState by playbackViewModel.uiState.collectAsStateWithLifecycle()
                    PlaybackQueueScreen(
                        playbackState = playbackState,
                        settings = settings,
                        onBack = navController::navigateUp,
                        onPlaySongAt = playbackViewModel::selectQueueItem,
                    )
                }
                composable(
                    route = AppRoute.AudioDetails.route,
                    arguments = listOf(navArgument("songId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val songId = backStackEntry.arguments?.getString("songId").orEmpty()
                    AudioDetailsRoute(
                        songId = songId,
                        onBack = navController::navigateUp,
                    )
                }
            }
        }
    }
}

internal fun isLibraryStartupReadyForFullyDrawn(state: LibraryScreenState): Boolean =
    state.songs.isNotEmpty() ||
        state.permissionRequired ||
        state.errorMessage != null ||
        !state.isLoading

@Composable
private fun BottomBarContent(
    currentRoute: String?,
    navController: androidx.navigation.NavHostController,
    playbackViewModel: PlaybackViewModel,
) {
    val playbackState by playbackViewModel.uiState.collectAsStateWithLifecycle()
    val isTopLevelRoute = currentRoute in topLevelRouteIds
    val showMiniPlayer = currentRoute != AppRoute.NowPlaying.route &&
        playbackState.currentSong != null
    val chromeMode = resolveBottomChromeMode(
        isTopLevelRoute = isTopLevelRoute,
        showMiniPlayer = showMiniPlayer,
    )

    androidx.compose.foundation.layout.Column {
        if (showMiniPlayer) {
            MiniPlayer(
                state = playbackState,
                onOpenNowPlaying = { navController.navigate(AppRoute.NowPlaying.route) },
                onTogglePlayPause = playbackViewModel::togglePlayPause,
                onSkipNext = playbackViewModel::skipNext,
            )
        }
        when (chromeMode) {
            BottomChromeMode.BOTTOM_NAVIGATION -> {
                NavigationBar {
                    topLevelRoutes.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                if (currentRoute != destination.route) {
                                    navController.navigate(destination.route) {
                                        popUpTo(AppRoute.Albums.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { androidx.compose.material3.Text(destination.label) },
                        )
                    }
                }
            }
            BottomChromeMode.SYSTEM_NAVIGATION_INSET -> SystemNavigationInsetSpacer()
            BottomChromeMode.NONE -> Unit
        }
    }
}

@Composable
private fun SystemNavigationInsetSpacer() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars),
        )
    }
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val topLevelRoutes = listOf(
    TopLevelDestination(AppRoute.Albums.route, "Albums", Icons.Filled.Album),
    TopLevelDestination(AppRoute.Artists.route, "Artists", Icons.Filled.Person),
    TopLevelDestination(AppRoute.Playlists.route, "Playlists", Icons.Filled.PlaylistPlay),
    TopLevelDestination(AppRoute.Settings.route, "Settings", Icons.Filled.Settings),
)

private val topLevelRouteIds = topLevelRoutes.map(TopLevelDestination::route).toSet()
