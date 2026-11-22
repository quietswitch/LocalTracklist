package com.libreplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.media.playback.queueOccurrenceKey
import com.libreplayer.ui.components.ArtworkVariant
import com.libreplayer.ui.components.ArtworkThumbnail
import com.libreplayer.ui.components.DividerItem
import com.libreplayer.ui.components.EmptyState
import com.libreplayer.ui.components.SongRow
import com.libreplayer.util.formatDuration
import com.libreplayer.util.formatRemainingDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: PlaybackUiState,
    settings: AppSettings,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAudioDetails: (String) -> Unit,
) {
    val song = playbackState.currentSong
    if (song == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Now playing") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            EmptyState(
                title = "Nothing loaded",
                message = "Pick a song from your local library to start playback.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    var sliderValue by remember(song.id) { mutableFloatStateOf(playbackState.positionMs.toFloat()) }
    var dragging by remember(song.id) { mutableStateOf(false) }

    LaunchedEffect(playbackState.positionMs, song.id, dragging) {
        if (!dragging) {
            sliderValue = playbackState.positionMs.toFloat()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now playing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    NowPlayingMoreMenu(
                        onOpenAudioDetails = { onOpenAudioDetails(song.id) },
                    )
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // The square artwork scales to the available height and width.
            // Remaining content scrolls when the device has a short viewport.
            val artworkSize = minOf(
                (maxWidth - 56.dp).coerceAtLeast(160.dp),
                (maxHeight * 0.46f).coerceAtLeast(160.dp),
                344.dp,
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ArtworkThumbnail(
                    artworkUri = song.artworkUri,
                    fallbackArtworkUri = song.contentUri,
                    sourceRevisionEpochSeconds = song.dateModifiedEpochSeconds,
                    fallbackText = displayTitle(song, settings),
                    variant = ArtworkVariant.FULL,
                    modifier = Modifier
                        .size(artworkSize)
                        .clip(RoundedCornerShape(20.dp)),
                )
                Spacer(modifier = Modifier.height(24.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = displayTitle(song, settings),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.resolvedArtist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (song.resolvedAlbum.isNotBlank() &&
                        !song.resolvedAlbum.equals(song.resolvedTitle, ignoreCase = true)
                    ) {
                        Text(
                            text = song.resolvedAlbum,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    playbackState.errorMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Slider(
                    value = sliderValue.coerceIn(
                        0f,
                        playbackState.durationMs.coerceAtLeast(1L).toFloat(),
                    ),
                    onValueChange = {
                        dragging = true
                        sliderValue = it
                    },
                    onValueChangeFinished = {
                        onSeekTo(sliderValue.toLong())
                        dragging = false
                    },
                    valueRange = 0f..playbackState.durationMs.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(5.dp),
                            drawStopIndicator = null,
                            thumbTrackGapSize = 0.dp,
                        )
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(sliderValue.toLong()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatRemainingDuration(sliderValue.toLong(), playbackState.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onToggleShuffle, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = if (playbackState.shuffleEnabled)
                                "Turn shuffle off" else "Turn shuffle on",
                            tint = if (playbackState.shuffleEnabled)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onSkipPrevious, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    FilledIconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            imageVector = if (playbackState.primaryControlShowsPause)
                                Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playbackState.primaryControlShowsPause)
                                "Pause" else "Play",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    IconButton(onClick = onSkipNext, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    IconButton(onClick = onCycleRepeatMode, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = when (playbackState.repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                                else -> Icons.Filled.Repeat
                            },
                            contentDescription = when (playbackState.repeatMode) {
                                Player.REPEAT_MODE_ONE -> "Repeat one"
                                Player.REPEAT_MODE_ALL -> "Repeat all"
                                else -> "Repeat off"
                            },
                            tint = if (playbackState.repeatMode == Player.REPEAT_MODE_OFF)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (song.isFavorite) Icons.Filled.Favorite
                                else Icons.Filled.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (song.isFavorite) "Favorited" else "Favorite")
                    }
                    TextButton(onClick = onOpenQueue) {
                        Icon(
                            Icons.Filled.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Queue")
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingMoreMenu(
    onOpenAudioDetails: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Audio details") },
                onClick = {
                    onOpenAudioDetails()
                    expanded = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQueueScreen(
    playbackState: PlaybackUiState,
    settings: AppSettings,
    onBack: () -> Unit,
    onPlaySongAt: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val contentPadding = secondaryScreenContentPadding(scaffoldPadding = padding)
        if (playbackState.queue.isEmpty()) {
            EmptyState(
                title = "Queue is empty",
                message = "Start playback from the library to see the current queue.",
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            LazyColumn(contentPadding = contentPadding) {
                itemsIndexed(
                    playbackState.queue,
                    key = { index, song -> queueOccurrenceKey(index, song.id) },
                ) { index, song ->
                    SongRow(
                        title = displayTitle(song, settings),
                        subtitle = displaySubtitle(song),
                        supportingText = if (index == playbackState.currentIndex) "Playing now" else null,
                        durationMs = song.durationMs,
                        artworkUri = song.artworkUri,
                        fallbackArtworkUri = song.contentUri,
                        sourceRevisionEpochSeconds = song.dateModifiedEpochSeconds,
                        onClick = { onPlaySongAt(index) },
                    )
                    DividerItem()
                }
            }
        }
    }
}

