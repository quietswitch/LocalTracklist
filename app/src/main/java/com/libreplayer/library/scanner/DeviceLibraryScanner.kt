package com.libreplayer.library.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.libreplayer.library.semantics.decodeMediaStoreTrackNumber
import com.libreplayer.library.semantics.normalizeMetadataYear
import androidx.documentfile.provider.DocumentFile
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.data.database.entity.SongSourceEntity
import com.libreplayer.library.metadata.AudioMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class ScannedSong(
    val id: String,
    val sourceType: SongSourceType,
    val contentUri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val dateAddedEpochSeconds: Long,
    val dateModifiedEpochSeconds: Long,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val artworkUri: String?,
)

data class MediaStoreCheckpoint(
    val version: String,
    val generation: Long,
)

enum class LibraryScanMode {
    INCREMENTAL,
    FULL_REBUILD,
}

data class LibraryScanStatistics(
    val elapsedMillis: Long,
    val mediaStoreRowsRead: Int,
    val mediaStoreIdRowsRead: Int,
    val documentFilesVisited: Int,
    val documentMetadataReads: Int,
    val documentMetadataFailures: Int,
    val cachedSongsReused: Int,
)

internal data class ScopedLibraryScan(
    val sources: List<SourceScan>,
    val statistics: LibraryScanStatistics,
    val full: Boolean,
)

class DeviceLibraryScanner(
    private val context: Context,
    private val metadataReader: AudioMetadataReader,
) {
    internal suspend fun scan(
        importedRoots: List<String>,
        cachedSongs: List<ScannedSong>,
        sources: List<com.libreplayer.data.database.entity.LibrarySourceEntity>,
        memberships: List<com.libreplayer.data.database.entity.SongSourceEntity>,
        mode: LibraryScanMode,
    ): ScopedLibraryScan = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val results = mutableListOf<SourceScan>()
        var mediaRows = 0
        var idRows = 0
        var reused = 0
        val counters = DocumentScanCounters()
        val forceFull = mode == LibraryScanMode.FULL_REBUILD
        val songsById = cachedSongs.associateBy { it.id }
        val previousSources = sources.associateBy { it.id }
        val mediaSources = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.getExternalVolumeNames(context).sorted().map(SourceIdentity::media)
            } else {
                // A physical primary card on old APIs has no durable volume identity here.
                // Only a platform-proven emulated primary storage scope is authoritative.
                if (!android.os.Environment.isExternalStorageEmulated() ||
                    android.os.Environment.getExternalStorageState() != android.os.Environment.MEDIA_MOUNTED
                ) throw IOException("MediaStore storage identity is unavailable; import an exact SAF root.")
                listOf(SourceIdentity.media("emulated_primary"))
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            results += SourceScan.Unavailable(SourceIdentity.media("emulated_primary"), failure.message.orEmpty(), failure is SecurityException)
            emptyList()
        }
        val requested = mediaSources + importedRoots.map(SourceIdentity::root)
        sources.filter { old -> old.kind == SourceIdentity.MEDIA && requested.none { it.id == old.id } }.forEach { old ->
            // Missing volumes never constitute empty enumeration. Explicitly detached roots
            // retain aliases but are no longer requested; revoked registered roots fail below.
            results += SourceScan.Unavailable(old, "Source is not available.")
        }
        for (descriptor in requested) {
            val source = previousSources[descriptor.id] ?: descriptor
            val cached = memberships.filter { it.sourceId == source.id && it.incarnation == source.incarnation && it.present }
                .mapNotNull { member ->
                    songsById[member.songId]?.let { song ->
                        cachedSongForSource(song, member, source.kind)
                    }
                }
            try {
                if (source.kind == SourceIdentity.MEDIA) {
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Audio.Media.getContentUri(source.locator)
                    } else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    val before = currentCheckpoint(source.locator)
                    val previous = source.version?.let { version -> source.generation?.let { MediaStoreCheckpoint(version, it) } }
                    val decision = decideGenerationSync(previous, before, forceFull || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                    val scanned = if (decision == GenerationSyncDecision.FULL) {
                        queryMediaStoreSongs(collection, null).also { mediaRows += it.size }
                    } else {
                        val changed = if (decision == GenerationSyncDecision.INCREMENTAL) {
                            queryMediaStoreSongs(collection, checkNotNull(previous).generation).also { mediaRows += it.size }
                        } else emptyList()
                        val ids = queryCurrentMediaStoreIds(collection).also { idRows += it.size }
                        mergeIncrementalMediaStoreSongs(cached, changed, ids).also { reused += it.size - changed.size }
                    }
                    // A provider mutation between enumeration and its ID/checkpoint queries cannot
                    // authorize a partial deletion set. Retry on the next refresh instead.
                    if (before != currentCheckpoint(source.locator)) throw IOException("MediaStore changed during enumeration.")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && source.locator !in MediaStore.getExternalVolumeNames(context)) {
                        throw IOException("MediaStore volume became unavailable.")
                    }
                    results += SourceScan.Complete(source, scanned.map { SourceObservation(it.id, it) }, before)
                } else {
                    val destination = mutableListOf<ScannedSong>()
                    val byDocument = cached.mapNotNull { song -> SourceIdentity.documentKey(song.contentUri)?.let { it to song } }.toMap()
                    val uri = Uri.parse(source.locator)
                    if (DocumentsContract.isTreeUri(uri)) {
                        walkDocumentTree(uri, destination, byDocument, forceFull, counters)
                    } else {
                        val root = DocumentFile.fromSingleUri(context, uri) ?: throw IOException("Cannot open document root.")
                        if (!root.exists() || !root.canRead()) throw IOException("Document root is unavailable.")
                        walkDocument(root, destination, byDocument, forceFull, counters)
                    }
                    val observations = destination.map { song ->
                        SourceObservation(checkNotNull(SourceIdentity.documentKey(song.contentUri)), song)
                    }.distinctBy { it.itemKey }
                    results += SourceScan.Complete(source, observations)
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                results += SourceScan.Unavailable(source, failure.message ?: "Source enumeration failed.", failure is SecurityException)
            }
        }
        ScopedLibraryScan(results, LibraryScanStatistics(
            SystemClock.elapsedRealtime() - startedAt, mediaRows, idRows, counters.filesVisited,
            counters.metadataReads, counters.metadataFailures, reused + counters.cachedSongsReused,
        ), forceFull)
    }

    private fun currentCheckpoint(volume: String): MediaStoreCheckpoint? {
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getVersion(context, volume)
            else MediaStore.getVersion(context)
        if (version == null) throw IOException("MediaStore version is unavailable.")
        val generation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getGeneration(context, volume) else 0
        return MediaStoreCheckpoint(version, generation)
    }
    private fun queryMediaStoreSongs(collection: Uri, minimumGenerationExclusive: Long?): List<ScannedSong> {
        val projection = mediaStoreAudioProjection(Build.VERSION.SDK_INT)
        val selectionParts = mutableListOf(
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            "${MediaStore.Audio.Media.DURATION} >= 30000",
        )
        val selectionArgs = mutableListOf<String>()
        if (minimumGenerationExclusive != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            selectionParts += "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
            selectionArgs += minimumGenerationExclusive.toString()
        }

        return buildList {
            context.contentResolver.query(
                collection,
                projection,
                selectionParts.joinToString(" AND "),
                selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val song = cursor.toScannedSong(collection)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || isProvenEmulatedPath(song.relativePath)) add(song)
                }
            } ?: throw IOException("MediaStore returned no metadata cursor.")
        }
    }

    private fun queryCurrentMediaStoreIds(collection: Uri): Set<String> {
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} >= 30000"
        return buildSet {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                selection,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                while (cursor.moveToNext()) {
                    add(cursor.getLong(idColumn).toString())
                }
            } ?: throw IOException("MediaStore returned no identity cursor.")
        }
    }

    private fun Cursor.toScannedSong(collection: Uri): ScannedSong {
        val mediaId = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
        val rawTrackNumber = getInt(getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK))
        val trackDiscNumbers = decodeMediaStoreTrackNumber(rawTrackNumber)
        val contentUri = ContentUris.withAppendedId(collection, mediaId)
        return ScannedSong(
            id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaId.toString() else sourceKey(mediaId.toString(), getString(getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)).orEmpty()),
            sourceType = SongSourceType.MEDIA_STORE,
            contentUri = contentUri.toString(),
            title = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)),
            artist = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)),
            album = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)),
            durationMs = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)),
            trackNumber = trackDiscNumbers.trackNumber,
            discNumber = trackDiscNumbers.discNumber,
            year = normalizeMetadataYear(
                getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)),
            ),
            dateAddedEpochSeconds = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)),
            dateModifiedEpochSeconds = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)),
            displayName = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                ?: mediaId.toString(),
            relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getString(getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH))
            } else {
                getString(getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
            },
            mimeType = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)),
            artworkUri = contentUri.toString(),
        )
    }

    private fun isProvenEmulatedPath(path: String?): Boolean {
        if (path == null) return false
        val root = android.os.Environment.getExternalStorageDirectory().canonicalFile
        val file = java.io.File(path).canonicalFile
        return file.path.startsWith(root.path.trimEnd('/') + "/")
    }

    private fun walkDocumentTree(
        treeUri: Uri,
        destination: MutableList<ScannedSong>,
        cachedById: Map<String, ScannedSong>,
        forceFull: Boolean,
        counters: DocumentScanCounters,
    ) {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        walkDocumentTreeChildren(
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            destination = destination,
            cachedById = cachedById,
            forceFull = forceFull,
            counters = counters,
        )
    }

    private fun walkDocumentTreeChildren(
        treeUri: Uri,
        parentDocumentId: String,
        destination: MutableList<ScannedSong>,
        cachedById: Map<String, ScannedSong>,
        forceFull: Boolean,
        counters: DocumentScanCounters,
        visited: MutableSet<String> = mutableSetOf(),
    ) {
        if (!visited.add(parentDocumentId)) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )
        val cursor = context.contentResolver.query(
            childrenUri,
            DOCUMENT_PROJECTION,
            null,
            null,
            null,
        ) ?: throw IOException("Document provider returned no children cursor.")
        cursor.use {
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                throw IOException("Document provider enumeration is still loading.")
            }
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (it.moveToNext()) {
                val documentId = it.getString(idColumn)
                val displayName = it.getString(nameColumn).orEmpty()
                val mimeType = it.getString(mimeColumn)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkDocumentTreeChildren(
                        treeUri = treeUri,
                        parentDocumentId = documentId,
                        destination = destination,
                        cachedById = cachedById,
                        forceFull = forceFull,
                        counters = counters,
                        visited = visited,
                    )
                } else if (isAudioCandidate(mimeType, displayName)) {
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    processDocument(
                        uri = documentUri,
                        descriptor = DocumentDescriptor(
                            id = "document:$documentUri",
                            contentUri = documentUri.toString(),
                            displayName = displayName.ifBlank { documentId.substringAfterLast('/') },
                            relativePath = documentUri.path,
                            mimeType = mimeType,
                            dateModifiedEpochSeconds = if (it.isNull(modifiedColumn)) {
                                0L
                            } else {
                                (it.getLong(modifiedColumn) / 1000L).coerceAtLeast(0L)
                            },
                        ),
                        destination = destination,
                        cachedById = cachedById,
                        forceFull = forceFull,
                        counters = counters,
                    )
                }
            }
        }
    }

    private fun walkDocument(
        file: DocumentFile,
        destination: MutableList<ScannedSong>,
        cachedById: Map<String, ScannedSong>,
        forceFull: Boolean,
        counters: DocumentScanCounters,
    ) {
        if (file.isDirectory) {
            throw IOException("A directory requires an exact tree grant.")
        }
        if (!file.isFile || !file.isAudioCandidate()) return

        val uri = file.uri
        val modifiedSeconds = (file.lastModified() / 1000L).coerceAtLeast(0L)
        val descriptor = DocumentDescriptor(
            id = "document:$uri",
            contentUri = uri.toString(),
            displayName = file.name ?: uri.lastPathSegment.orEmpty(),
            relativePath = uri.path,
            mimeType = file.type,
            dateModifiedEpochSeconds = modifiedSeconds,
        )
        processDocument(
            uri = uri,
            descriptor = descriptor,
            destination = destination,
            cachedById = cachedById,
            forceFull = forceFull,
            counters = counters,
        )
    }

    private fun processDocument(
        uri: Uri,
        descriptor: DocumentDescriptor,
        destination: MutableList<ScannedSong>,
        cachedById: Map<String, ScannedSong>,
        forceFull: Boolean,
        counters: DocumentScanCounters,
    ) {
        counters.filesVisited++
        val cached = SourceIdentity.documentKey(descriptor.contentUri)?.let(cachedById::get)
        val matchedByCanonicalPath = false
        if (
            !forceFull && cached != null &&
            canReuseCachedDocument(cached, descriptor, matchedByCanonicalPath)
        ) {
            destination += cached.asDocumentSong(descriptor)
            counters.cachedSongsReused++
            return
        }

        counters.metadataReads++
        val metadata = metadataReader.read(uri)
        if (metadata == null) {
            counters.metadataFailures++
            if (cached != null) {
                destination += cached.asDocumentSong(descriptor)
                counters.cachedSongsReused++
            }
            throw IOException("Document metadata read failed; root authority withheld.")
        }
        val duration = metadata.durationMs ?: 0L
        if (duration <= 0L || duration in 1L..<30_000L) return
        destination += ScannedSong(
            id = descriptor.id,
            sourceType = SongSourceType.DOCUMENT,
            contentUri = descriptor.contentUri,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            durationMs = duration,
            trackNumber = metadata.trackNumber,
            discNumber = metadata.discNumber,
            year = metadata.year,
            dateAddedEpochSeconds = descriptor.dateModifiedEpochSeconds,
            dateModifiedEpochSeconds = descriptor.dateModifiedEpochSeconds,
            displayName = descriptor.displayName,
            relativePath = descriptor.relativePath,
            mimeType = descriptor.mimeType,
            artworkUri = descriptor.contentUri,
        )
    }

    private fun DocumentFile.isAudioCandidate(): Boolean {
        return isAudioCandidate(type, name.orEmpty())
    }

    private fun isAudioCandidate(mimeType: String?, displayName: String): Boolean {
        val type = mimeType.orEmpty().lowercase()
        val name = displayName.lowercase()
        return type.startsWith("audio/") || SUPPORTED_EXTENSIONS.any(name::endsWith)
    }

    private companion object {
        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val SUPPORTED_EXTENSIONS = listOf(
            ".mp3",
            ".flac",
            ".m4a",
            ".aac",
            ".ogg",
            ".opus",
            ".wav",
            ".amr",
        )
    }
}

private class DocumentScanCounters(
    var filesVisited: Int = 0,
    var metadataReads: Int = 0,
    var cachedSongsReused: Int = 0,
    var metadataFailures: Int = 0,
)

internal data class DocumentDescriptor(
    val id: String,
    val contentUri: String,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val dateModifiedEpochSeconds: Long,
)

/**
 * A SAF source membership can refer to a MediaStore-preferred catalog song.
 * Preserve its document identity, but recover its document-relative path only
 * when the stored membership proves the same provider-qualified physical file
 * in both MediaStore and SAF. Unproven or opaque providers stay conservative.
 */
internal fun cachedSongForSource(
    song: ScannedSong,
    membership: SongSourceEntity,
    sourceKind: String,
): ScannedSong {
    val provenMediaAlias = sourceKind == SourceIdentity.DOCUMENT &&
        song.sourceType == SongSourceType.MEDIA_STORE &&
        membership.physicalKey != null &&
        SourceIdentity.physicalKey(song) == membership.physicalKey &&
        SourceIdentity.physicalKey(song.copy(contentUri = membership.contentUri)) == membership.physicalKey
    val documentPath = if (provenMediaAlias) {
        runCatching { java.net.URI(membership.contentUri).path }.getOrNull()
    } else null
    return song.copy(
        id = membership.itemKey,
        contentUri = membership.contentUri,
        relativePath = documentPath ?: song.relativePath,
    )
}

internal fun canReuseCachedDocument(
    cached: ScannedSong,
    descriptor: DocumentDescriptor,
    matchedByCanonicalPath: Boolean = false,
): Boolean =
    descriptor.dateModifiedEpochSeconds > 0L &&
        cached.displayName == descriptor.displayName &&
        cached.dateModifiedEpochSeconds == descriptor.dateModifiedEpochSeconds &&
        (
            matchedByCanonicalPath ||
                (
                    cached.contentUri == descriptor.contentUri &&
                        cached.relativePath == descriptor.relativePath &&
                        cached.mimeType == descriptor.mimeType
                )
            )

internal fun ScannedSong.asDocumentSong(descriptor: DocumentDescriptor): ScannedSong =
    copy(
        id = descriptor.id,
        sourceType = SongSourceType.DOCUMENT,
        contentUri = descriptor.contentUri,
        dateAddedEpochSeconds = descriptor.dateModifiedEpochSeconds,
        dateModifiedEpochSeconds = descriptor.dateModifiedEpochSeconds,
        displayName = descriptor.displayName,
        relativePath = descriptor.relativePath,
        mimeType = descriptor.mimeType,
        artworkUri = descriptor.contentUri,
    )

internal fun mergeIncrementalMediaStoreSongs(
    cachedSongs: List<ScannedSong>,
    changedSongs: List<ScannedSong>,
    currentIds: Set<String>,
): List<ScannedSong> {
    val merged = cachedSongs.associateByTo(LinkedHashMap(), ScannedSong::id)
    changedSongs.forEach { merged[it.id] = it }
    return merged.values.filter { it.id in currentIds }
}

internal enum class GenerationSyncDecision {
    FULL,
    UNCHANGED,
    INCREMENTAL,
}

internal fun decideGenerationSync(
    previous: MediaStoreCheckpoint?,
    current: MediaStoreCheckpoint?,
    forceFull: Boolean,
): GenerationSyncDecision =
    when {
        forceFull -> GenerationSyncDecision.FULL
        previous == null || current == null -> GenerationSyncDecision.FULL
        previous.version != current.version -> GenerationSyncDecision.FULL
        current.generation < previous.generation -> GenerationSyncDecision.FULL
        current.generation == previous.generation -> GenerationSyncDecision.UNCHANGED
        else -> GenerationSyncDecision.INCREMENTAL
    }

internal fun mediaStoreAudioProjection(sdkInt: Int): Array<String> =
    buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.TRACK)
        add(MediaStore.Audio.Media.YEAR)
        add(MediaStore.Audio.Media.DATE_ADDED)
        add(MediaStore.Audio.Media.DATE_MODIFIED)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        if (sdkInt >= Build.VERSION_CODES.Q) {
            add(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            add(MediaStore.Audio.Media.DATA)
        }
        add(MediaStore.Audio.Media.MIME_TYPE)
        add(MediaStore.Audio.Media.ALBUM_ID)
        if (sdkInt >= Build.VERSION_CODES.R) {
            add(MediaStore.MediaColumns.GENERATION_MODIFIED)
        }
    }.toTypedArray()
