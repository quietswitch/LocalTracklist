package com.libreplayer.library.scanner

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.SongSourceType
import org.junit.Test

class IncrementalScannerPolicyTest {
    @Test
    fun `matching generation skips MediaStore metadata query`() {
        val checkpoint = MediaStoreCheckpoint("version", 10L)

        assertThat(decideGenerationSync(checkpoint, checkpoint, forceFull = false))
            .isEqualTo(GenerationSyncDecision.UNCHANGED)
    }

    @Test
    fun `higher generation requests changed rows`() {
        assertThat(
            decideGenerationSync(
                previous = MediaStoreCheckpoint("version", 10L),
                current = MediaStoreCheckpoint("version", 11L),
                forceFull = false,
            ),
        ).isEqualTo(GenerationSyncDecision.INCREMENTAL)
    }

    @Test
    fun `version change and generation reset force full reconciliation`() {
        assertThat(
            decideGenerationSync(
                previous = MediaStoreCheckpoint("old", 10L),
                current = MediaStoreCheckpoint("new", 1L),
                forceFull = false,
            ),
        ).isEqualTo(GenerationSyncDecision.FULL)
    }

    @Test
    fun `missing checkpoint after database restart forces full reconciliation`() {
        assertThat(
            decideGenerationSync(
                previous = null,
                current = MediaStoreCheckpoint("version", 10L),
                forceFull = false,
            ),
        ).isEqualTo(GenerationSyncDecision.FULL)
    }

    @Test
    fun `incremental merge adds modifies and deletes`() {
        val cachedOne = song("media:1", title = "One")
        val cachedTwo = song("media:2", title = "Old")
        val modifiedTwo = song("media:2", title = "New")
        val addedThree = song("media:3", title = "Three")

        val merged = mergeIncrementalMediaStoreSongs(
            cachedSongs = listOf(cachedOne, cachedTwo),
            changedSongs = listOf(modifiedTwo, addedThree),
            currentIds = setOf("media:2", "media:3"),
        )

        assertThat(merged.map(ScannedSong::id)).containsExactly("media:2", "media:3").inOrder()
        assertThat(merged.first { it.id == "media:2" }.title).isEqualTo("New")
    }

    @Test
    fun `unchanged SAF document reuses cached metadata`() {
        val cached = song(
            id = "document:content://tree/song",
            title = "Cached title",
            sourceType = SongSourceType.DOCUMENT,
            modified = 123L,
        )
        val descriptor = DocumentDescriptor(
            id = cached.id,
            contentUri = cached.contentUri,
            displayName = cached.displayName,
            relativePath = cached.relativePath,
            mimeType = cached.mimeType,
            dateModifiedEpochSeconds = 123L,
        )

        assertThat(canReuseCachedDocument(cached, descriptor)).isTrue()
    }

    @Test
    fun `SAF provider without modification time is reparsed for correctness`() {
        val cached = song(
            id = "document:content://tree/song",
            sourceType = SongSourceType.DOCUMENT,
            modified = 0L,
        )
        val descriptor = DocumentDescriptor(
            id = cached.id,
            contentUri = cached.contentUri,
            displayName = cached.displayName,
            relativePath = cached.relativePath,
            mimeType = cached.mimeType,
            dateModifiedEpochSeconds = 0L,
        )

        assertThat(canReuseCachedDocument(cached, descriptor)).isFalse()
    }

    @Test
    fun `changed SAF modification time reparses metadata`() {
        val cached = song(
            id = "document:content://tree/song",
            sourceType = SongSourceType.DOCUMENT,
            modified = 10L,
        )
        val descriptor = DocumentDescriptor(
            id = cached.id,
            contentUri = cached.contentUri,
            displayName = cached.displayName,
            relativePath = cached.relativePath,
            mimeType = cached.mimeType,
            dateModifiedEpochSeconds = 11L,
        )

        assertThat(canReuseCachedDocument(cached, descriptor)).isFalse()
    }

    @Test
    fun `proven SAF membership restores document path for cached MediaStore metadata`() {
        val media = song("media:42", title = "Cached title", modified = 123L).copy(
            contentUri = "content://media/external_primary/audio/media/42",
            displayName = "song.flac",
            relativePath = "Music/Artist/Album/",
            mimeType = "audio/flac",
        )
        val documentUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
            "document/primary%3AMusic%2FArtist%2FAlbum%2Fsong.flac"
        val membership = com.libreplayer.data.database.entity.SongSourceEntity(
            sourceId = "root", incarnation = 0L,
            itemKey = checkNotNull(SourceIdentity.documentKey(documentUri)),
            songId = media.id,
            contentUri = documentUri,
            physicalKey = SourceIdentity.physicalKey(media),
        )
        val restored = cachedSongForSource(media, membership, SourceIdentity.DOCUMENT)
        val descriptor = DocumentDescriptor(
            id = "document:$documentUri",
            contentUri = documentUri,
            displayName = "song.flac",
            relativePath = java.net.URI(documentUri).path,
            mimeType = "audio/flac",
            dateModifiedEpochSeconds = 123L,
        )
        assertThat(restored.relativePath).isEqualTo(descriptor.relativePath)
        assertThat(canReuseCachedDocument(restored, descriptor)).isTrue()
        assertThat(restored.title).isEqualTo("Cached title")
    }

    @Test
    fun `unproven SAF membership does not inherit document path`() {
        val media = song("media:42", modified = 123L).copy(
            contentUri = "content://media/external_primary/audio/media/42",
            displayName = "song.flac",
            relativePath = "Music/Artist/Album/",
        )
        val documentUri = "content://unrelated.provider/document/song.flac"
        val membership = com.libreplayer.data.database.entity.SongSourceEntity(
            sourceId = "root", incarnation = 0L,
            itemKey = checkNotNull(SourceIdentity.documentKey(documentUri)),
            songId = media.id,
            contentUri = documentUri,
            physicalKey = SourceIdentity.physicalKey(media),
        )
        val restored = cachedSongForSource(media, membership, SourceIdentity.DOCUMENT)
        assertThat(restored.relativePath).isEqualTo(media.relativePath)
        assertThat(canReuseCachedDocument(restored, DocumentDescriptor(
            id = "document:$documentUri", contentUri = documentUri, displayName = "song.flac",
            relativePath = java.net.URI(documentUri).path, mimeType = "audio/mpeg",
            dateModifiedEpochSeconds = 123L,
        ))).isFalse()
    }

    @Test
    fun `SAF duplicate reuses matching cached MediaStore metadata`() {
        val cachedMediaStoreSong = song(
            id = "media:42",
            title = "Cached title",
            modified = 123L,
        ).copy(
            contentUri = "content://media/external/audio/media/42",
            displayName = "song.flac",
            relativePath = "Music/Artist/Album/",
            mimeType = "audio/flac",
        )
        val descriptor = DocumentDescriptor(
            id = "document:content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
                "document/primary%3AMusic%2FArtist%2FAlbum%2Fsong.flac",
            contentUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
                "document/primary%3AMusic%2FArtist%2FAlbum%2Fsong.flac",
            displayName = "song.flac",
            relativePath = "/tree/primary:Music/document/primary:Music/Artist/Album/song.flac",
            mimeType = "audio/flac",
            dateModifiedEpochSeconds = 123L,
        )

        val mediaStorePath = ScannedSongDeduper.canonicalPathKey(cachedMediaStoreSong)
        val documentPath = ScannedSongDeduper.canonicalDocumentPathKey(
            contentUri = descriptor.contentUri,
            relativePath = descriptor.relativePath,
            displayName = descriptor.displayName,
        )
        val reused = cachedMediaStoreSong.asDocumentSong(descriptor)

        assertThat(documentPath).isEqualTo(mediaStorePath)
        assertThat(
            canReuseCachedDocument(
                cached = cachedMediaStoreSong,
                descriptor = descriptor,
                matchedByCanonicalPath = true,
            ),
        ).isTrue()
        assertThat(reused.sourceType).isEqualTo(SongSourceType.DOCUMENT)
        assertThat(reused.id).isEqualTo(descriptor.id)
        assertThat(reused.contentUri).isEqualTo(descriptor.contentUri)
        assertThat(reused.title).isEqualTo("Cached title")
    }
}

private fun song(
    id: String,
    title: String = id,
    sourceType: SongSourceType = SongSourceType.MEDIA_STORE,
    modified: Long = 1L,
) = ScannedSong(
    id = id,
    sourceType = sourceType,
    contentUri = id.removePrefix("document:"),
    title = title,
    artist = "Artist",
    album = "Album",
    durationMs = 60_000L,
    trackNumber = 1,
    discNumber = 1,
    year = 2026,
    dateAddedEpochSeconds = 1L,
    dateModifiedEpochSeconds = modified,
    displayName = "$id.mp3",
    relativePath = "/Music/$id.mp3",
    mimeType = "audio/mpeg",
    artworkUri = null,
)
