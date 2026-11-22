package com.libreplayer.library.scanner

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart

/** Collected only while the UI is started; no background worker or polling. */
internal fun observeMediaStoreChanges(resolver: ContentResolver): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) { trySend(Unit) }
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val path = uri?.pathSegments.orEmpty()
            if (path.size <= 1 || "audio" in path || "file" in path) trySend(Unit)
        }
    }
    resolver.registerContentObserver(Uri.parse("content://media"), true, observer)
    awaitClose { resolver.unregisterContentObserver(observer) }
}.buffer(Channel.CONFLATED)

@OptIn(FlowPreview::class)
internal suspend fun collectForegroundRefreshes(changes: Flow<Unit>, refresh: suspend () -> Unit) {
    // Foreground entry is immediate. Bursts settle before scanning; a change arriving
    // during a scan queues one follow-up instead of cancelling its transaction.
    changes.debounce(750L).onStart { emit(Unit) }.conflate().collect { refresh() }
}