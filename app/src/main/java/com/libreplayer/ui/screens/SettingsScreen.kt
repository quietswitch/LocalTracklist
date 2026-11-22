package com.libreplayer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.compose.material3.LinearProgressIndicator
import com.libreplayer.data.repository.LibrarySyncState
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.ImportedRoot
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    importedRoots: List<ImportedRoot>,
    syncState: LibrarySyncState,
    onSearch: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onShowFileNamesChange: (Boolean) -> Unit,
    onDefaultSortChange: (LibrarySortOption) -> Unit,
    onRescan: () -> Unit,
    onRebuild: () -> Unit,
    onAddImportedRoot: (Uri, String) -> Unit,
    onRemoveImportedRoot: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Imported folder"
            onAddImportedRoot(uri, displayName)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search library")
                    }
                },
            )
        }
        item {
            SettingsCard(title = "Theme") {
                SettingsChipRow(
                    labels = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                    ),
                    selected = settings.themeMode,
                    onSelected = onThemeModeChange,
                )
            }
        }
        item {
            SettingsCard(title = "Metadata display") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show file names when metadata is missing")
                        Text(
                            text = "If a track has no embedded title, use the file name instead of \"Unknown title.\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.showFileNamesWhenMetadataMissing,
                        onCheckedChange = onShowFileNamesChange,
                    )
                }
            }
        }
        item {
            SettingsCard(title = "Default song sort") {
                SettingsChipRow(
                    labels = listOf(
                        LibrarySortOption.TITLE to "Title",
                        LibrarySortOption.ARTIST to "Artist",
                        LibrarySortOption.ALBUM to "Album",
                        LibrarySortOption.DURATION to "Duration",
                        LibrarySortOption.DATE_ADDED to "Date added",
                    ),
                    selected = settings.defaultSortOption,
                    onSelected = onDefaultSortChange,
                )
            }
        }
        item {
            SettingsCard(title = "Library") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onRescan, enabled = !syncState.isLoading) {
                        Text(if (syncState.isLoading) "Scanning library…" else "Rescan library")
                    }
                    when {
                        syncState.isLoading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        syncState.errorMessage != null -> Text(
                            text = "Rescan could not finish: ${syncState.errorMessage}",
                            color = MaterialTheme.colorScheme.error,
                        )
                        syncState.completedSuccessfully -> Text("Rescan complete.")
                    }
                    Text(
                        text = "Rescan checks new, changed, and removed music. Tracks must be at least 30 seconds long. Android must finish indexing transfers before they can appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRebuild, enabled = !syncState.isLoading) {
                        Text("Rebuild library completely")
                    }
                    OutlinedButton(onClick = { treeLauncher.launch(null) }) {
                        Text("Import folder")
                    }
                    if (importedRoots.isEmpty()) {
                        Text(
                            text = "No extra folders imported. MediaStore scanning remains the default.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        importedRoots.forEach { root ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(root.displayName)
                                    Text(
                                        text = root.uri,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onRemoveImportedRoot(root.uri) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            SettingsCard(title = "About LocalTracklist") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Local music player for Android. Free and open-source. Developer: quietswitch.")
                    Text("No ads. No analytics. No user accounts. No subscriptions. No cloud sync by default.")
                    Text("Your music and metadata stay on-device. LocalTracklist does not upload your media off the device.")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun <T> SettingsChipRow(
    labels: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { (value, label) ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onSelected(value) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}
