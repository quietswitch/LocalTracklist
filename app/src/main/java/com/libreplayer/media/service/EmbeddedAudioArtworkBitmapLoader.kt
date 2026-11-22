package com.libreplayer.media.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MediaStore song URIs point to audio files containing embedded covers, not to image files.
 * Load the embedded picture for MediaSession/legacy Bluetooth clients while keeping the
 * normal image-URI loader as a fallback. Work stays off the main thread.
 */
@UnstableApi
internal class EmbeddedAudioArtworkBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    private val fallback = DataSourceBitmapLoader.Builder(context).build()

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val result = SettableFuture.create<Bitmap>()
        scope.launch(Dispatchers.IO) {
            try {
                val embedded = loadEmbeddedPicture(uri)
                if (embedded != null) {
                    result.set(embedded)
                } else {
                    result.setFuture(fallback.loadBitmap(uri))
                }
            } catch (exception: CancellationException) {
                result.cancel(true)
                throw exception
            } catch (_: Exception) {
                result.setFuture(fallback.loadBitmap(uri))
            }
        }
        return result
    }

    private fun loadEmbeddedPicture(uri: Uri): Bitmap? {
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            if (bytes.size > MAX_EMBEDDED_PICTURE_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_ARTWORK_DIMENSION_PX) {
                sampleSize *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } catch (exception: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val MAX_ARTWORK_DIMENSION_PX = 384
        const val MAX_EMBEDDED_PICTURE_BYTES = 6 * 1024 * 1024
    }
}
