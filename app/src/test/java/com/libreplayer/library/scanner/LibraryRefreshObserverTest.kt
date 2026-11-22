package com.libreplayer.library.scanner

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefreshObserverTest {
    @Test fun `foreground entry and provider burst refresh without cancelling active work`() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
        var started = 0
        var completed = 0
        val job = launch { collectForegroundRefreshes(events) { started++; delay(2000); completed++ } }
        runCurrent()
        assertThat(started).isEqualTo(1)
        repeat(32) { events.emit(Unit) }
        advanceTimeBy(750)
        runCurrent()
        assertThat(started).isEqualTo(1)
        assertThat(completed).isEqualTo(0)
        advanceTimeBy(1250)
        runCurrent()
        assertThat(completed).isEqualTo(1)
        assertThat(started).isEqualTo(2)
        advanceTimeBy(2000)
        runCurrent()
        assertThat(completed).isEqualTo(2)
        job.cancelAndJoin()
        assertThat(events.subscriptionCount.value).isEqualTo(0)
        events.emit(Unit)
        advanceTimeBy(10000)
        assertThat(started).isEqualTo(2)
        val resumed = launch { collectForegroundRefreshes(events) { started++ } }
        runCurrent()
        assertThat(started).isEqualTo(3)
        resumed.cancelAndJoin()
    }
}