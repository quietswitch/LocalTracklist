package com.libreplayer.library.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.database.entity.SongSourceEntity
import com.libreplayer.data.repository.ProvenanceFixtures as F
import com.libreplayer.library.metadata.AudioMetadataReader
import com.libreplayer.library.metadata.ExtractedMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URI

class DeviceLibraryScannerTest {
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val metadataReader = mockk<AudioMetadataReader>()
    private val treeUri = mockk<Uri>()
    private val childrenUri = mockk<Uri>()
    private val scanner = DeviceLibraryScanner(context, metadataReader)

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class, Environment::class, Uri::class, DocumentsContract::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        // Local Android stubs use the pre-Q branch. Leave MediaStore unavailable so
        // these tests exercise only the imported root's independently scoped result.
        every { Environment.isExternalStorageEmulated() } returns false
        every { context.contentResolver } returns resolver
        every { Uri.parse(F.rootA.locator) } returns treeUri
        every { DocumentsContract.isTreeUri(treeUri) } returns true
        every { DocumentsContract.getTreeDocumentId(treeUri) } returns "rootA"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, "rootA") } returns childrenUri
        document("broken", metadata = null)
        document("healthy", ExtractedMetadata("Healthy title", "Artist", "Album", 60_000L, 1, 1, 2026))
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class, Environment::class, Uri::class, DocumentsContract::class)
    }

    @Test
    fun `cached document metadata failure retains song and completes incremental root scan`() {
        assertCachedFailure(LibraryScanMode.INCREMENTAL)
    }

    @Test
    fun `cached document metadata failure retains song and completes full root scan`() {
        assertCachedFailure(LibraryScanMode.FULL_REBUILD)
    }

    @Test
    fun `new document metadata failure is skipped and later documents are observed`() {
        every { resolver.query(childrenUri, any(), null, null, null) } returns children("broken", "healthy")

        val result = scan()

        assertThat(result.root()).isInstanceOf(SourceScan.Complete::class.java)
        val observations = (result.root() as SourceScan.Complete).observations
        assertThat(observations.map { it.song.title }).containsExactly("Healthy title")
        assertThat(observations.single().song.contentUri)
            .isEqualTo("content://provider.a/tree/rootA/document/healthy")
        assertThat(result.statistics.documentFilesVisited).isEqualTo(2)
        assertThat(result.statistics.documentMetadataReads).isEqualTo(2)
        assertThat(result.statistics.documentMetadataFailures).isEqualTo(1)
        assertThat(result.statistics.cachedSongsReused).isEqualTo(0)
    }

    @Test
    fun `missing children cursor keeps root unavailable`() {
        every { resolver.query(childrenUri, any(), null, null, null) } returns null

        val result = scan()

        assertThat(result.root()).isInstanceOf(SourceScan.Unavailable::class.java)
        assertThat(result.statistics.documentFilesVisited).isEqualTo(0)
        assertThat(result.statistics.documentMetadataFailures).isEqualTo(0)
    }

    @Test
    fun `revoked tree permission keeps root unavailable and requests permission`() {
        every { resolver.query(childrenUri, any(), null, null, null) } throws SecurityException("Grant revoked")

        val result = scan()

        assertThat(result.root()).isInstanceOf(SourceScan.Unavailable::class.java)
        val unavailable = result.root() as SourceScan.Unavailable
        assertThat(unavailable.permissionRequired).isTrue()
        assertThat(unavailable.reason).isEqualTo("Grant revoked")
        assertThat(result.statistics.documentMetadataFailures).isEqualTo(0)
    }

    @Test
    fun `loading provider cursor keeps root unavailable`() {
        every { resolver.query(childrenUri, any(), null, null, null) } returns children("healthy", loading = true)

        val result = scan()

        assertThat(result.root()).isInstanceOf(SourceScan.Unavailable::class.java)
        assertThat(result.statistics.documentFilesVisited).isEqualTo(0)
        assertThat(result.statistics.documentMetadataReads).isEqualTo(0)
    }

    @Test
    fun `provider failure after metadata failure still withholds root authority`() {
        val cursor = children("broken")
        var advances = 0
        every { cursor.moveToNext() } answers {
            if (advances++ == 0) true else throw IOException("Enumeration interrupted")
        }
        // This cursor yields one discovered file before its enumeration fails.
        every { cursor.getString(0) } returns "broken"
        every { cursor.getString(1) } returns "broken.mp3"
        every { cursor.getString(2) } returns "audio/mpeg"
        every { resolver.query(childrenUri, any(), null, null, null) } returns cursor

        val result = scan()

        assertThat(result.root()).isInstanceOf(SourceScan.Unavailable::class.java)
        assertThat((result.root() as SourceScan.Unavailable).reason).isEqualTo("Enumeration interrupted")
        assertThat(result.statistics.documentMetadataFailures).isEqualTo(1)
    }

    @Test
    fun `proven MediaStore alias avoids rereading unchanged SAF document metadata`() {
        val root = SourceIdentity.root("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val rootUri = mockk<Uri>()
        val childCollectionUri = mockk<Uri>()
        val childUri = mockk<Uri>()
        val documentId = "primary:Music/song.mp3"
        val documentUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
            "document/primary%3AMusic%2Fsong.mp3"

        every { Uri.parse(root.locator) } returns rootUri
        every { DocumentsContract.isTreeUri(rootUri) } returns true
        every { DocumentsContract.getTreeDocumentId(rootUri) } returns "primary:Music"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, "primary:Music") } returns childCollectionUri
        every { DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId) } returns childUri
        every { childUri.toString() } returns documentUri
        every { childUri.path } returns URI(documentUri).path
        val cursor = children(documentId)
        every { cursor.getString(1) } returns "song.mp3"
        every { resolver.query(childCollectionUri, any(), null, null, null) } returns cursor

        val media = F.media().copy(
            title = "Cached song",
            contentUri = "content://media/external_primary/audio/media/42",
            relativePath = "Music/",
            displayName = "song.mp3",
            mimeType = "audio/mpeg",
            dateModifiedEpochSeconds = 2L,
        )
        val member = SongSourceEntity(
            sourceId = root.id,
            incarnation = root.incarnation,
            itemKey = checkNotNull(SourceIdentity.documentKey(documentUri)),
            songId = media.id,
            contentUri = documentUri,
            physicalKey = SourceIdentity.physicalKey(media),
        )
        val result = runBlocking {
            scanner.scan(
                importedRoots = listOf(root.locator),
                cachedSongs = listOf(media),
                sources = listOf(root),
                memberships = listOf(member),
                mode = LibraryScanMode.INCREMENTAL,
            )
        }
        val scanned = result.sources.single { it.source.id == root.id } as SourceScan.Complete
        assertThat(scanned.observations.single().song.title).isEqualTo("Cached song")
        assertThat(result.statistics.documentFilesVisited).isEqualTo(1)
        assertThat(result.statistics.documentMetadataReads).isEqualTo(0)
        assertThat(result.statistics.cachedSongsReused).isEqualTo(1)
        io.mockk.verify(exactly = 0) { metadataReader.read(childUri) }
    }

    private fun assertCachedFailure(mode: LibraryScanMode) {
        val cached = F.document(item = "broken").copy(id = "saved-song", title = "Cached title")
        every { resolver.query(childrenUri, any(), null, null, null) } returns children("broken", "healthy")

        val result = scan(cached = listOf(cached), mode = mode)

        assertThat(result.root()).isInstanceOf(SourceScan.Complete::class.java)
        val observations = (result.root() as SourceScan.Complete).observations
        assertThat(observations.map { it.song.title }).containsExactly("Cached title", "Healthy title").inOrder()
        assertThat(observations.first().itemKey).isEqualTo("10:provider.a6:broken")
        assertThat(observations.first().song).isEqualTo(cached.copy(
            id = "document:content://provider.a/tree/rootA/document/broken",
            displayName = "broken.mp3",
            relativePath = "/tree/rootA/document/broken",
            dateAddedEpochSeconds = 2L,
            dateModifiedEpochSeconds = 2L,
        ))
        assertThat(result.statistics.documentFilesVisited).isEqualTo(2)
        assertThat(result.statistics.documentMetadataReads).isEqualTo(2)
        assertThat(result.statistics.documentMetadataFailures).isEqualTo(1)
        assertThat(result.statistics.cachedSongsReused).isEqualTo(1)
    }

    private fun scan(
        cached: List<ScannedSong> = emptyList(),
        mode: LibraryScanMode = LibraryScanMode.INCREMENTAL,
    ): ScopedLibraryScan = runBlocking {
        scanner.scan(
            importedRoots = listOf(F.rootA.locator),
            cachedSongs = cached,
            sources = listOf(F.rootA),
            memberships = cached.map { song ->
                SongSourceEntity(F.rootA.id, F.rootA.incarnation,
                    checkNotNull(SourceIdentity.documentKey(song.contentUri)), song.id, song.contentUri, null)
            },
            mode = mode,
        )
    }

    private fun ScopedLibraryScan.root(): SourceScan = sources.single { it.source.id == F.rootA.id }

    private fun document(id: String, metadata: ExtractedMetadata?) {
        val rawUri = "${F.rootA.locator}/document/$id"
        val uri = mockk<Uri>()
        every { uri.toString() } returns rawUri
        every { uri.path } returns URI(rawUri).path
        every { DocumentsContract.buildDocumentUriUsingTree(treeUri, id) } returns uri
        every { metadataReader.read(uri) } returns metadata
    }

    private fun children(vararg ids: String, loading: Boolean = false): Cursor {
        val cursor = mockk<Cursor>()
        val extras = mockk<Bundle>()
        val columns = listOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        var index = -1
        every { cursor.extras } returns extras
        every { extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) } returns loading
        every { cursor.getColumnIndexOrThrow(any()) } answers { columns.indexOf(firstArg<String>()).also { require(it >= 0) } }
        every { cursor.moveToNext() } answers { ++index < ids.size }
        every { cursor.getString(0) } answers { ids[index] }
        every { cursor.getString(1) } answers { "${ids[index]}.mp3" }
        every { cursor.getString(2) } returns "audio/mpeg"
        every { cursor.isNull(3) } returns false
        every { cursor.getLong(3) } returns 2_000L
        every { cursor.close() } returns Unit
        return cursor
    }
}
