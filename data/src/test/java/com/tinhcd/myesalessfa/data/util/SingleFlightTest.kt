package com.tinhcd.myesalessfa.data.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightTest {

    @Test
    fun `two callers arriving together make one call`() = runTest {
        val scope = TestScope(testScheduler)
        val flight = SingleFlight<Int>(scope)
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()

        val first = async { flight.run { calls.incrementAndGet(); gate.await(); 7 } }
        val second = async { flight.run { calls.incrementAndGet(); gate.await(); 7 } }

        advanceUntilIdle()
        gate.complete(Unit)

        assertEquals(7, first.await())
        assertEquals(7, second.await())
        // The exact bug this exists for: signing in reached the profile fetch
        // twice at once and the two racing requests produced a 400 on one.
        assertEquals(1, calls.get())
    }

    @Test
    fun `a caller arriving after the first settles starts a fresh call`() = runTest {
        val scope = TestScope(testScheduler)
        val flight = SingleFlight<Int>(scope)
        val calls = AtomicInteger()

        flight.run { calls.incrementAndGet() }
        flight.run { calls.incrementAndGet() }

        // Not a cache. A retry after a failure has to reach the server again.
        assertEquals(2, calls.get())
    }

    @Test
    fun `a failure reaches every caller waiting on it`() = runTest {
        val scope = TestScope(testScheduler)
        val flight = SingleFlight<String>(scope)
        val gate = CompletableDeferred<Unit>()

        val first = async { runCatching { flight.run { gate.await(); error("boom") } } }
        val second = async { runCatching { flight.run { gate.await(); error("boom") } } }

        advanceUntilIdle()
        gate.complete(Unit)

        assertEquals("boom", first.await().exceptionOrNull()?.message)
        assertEquals("boom", second.await().exceptionOrNull()?.message)
    }

    @Test
    fun `a failed call does not poison the next one`() = runTest {
        val scope = TestScope(testScheduler)
        val flight = SingleFlight<String>(scope)

        runCatching { flight.run { error("boom") } }
        val second = flight.run { "recovered" }

        assertEquals("recovered", second)
    }
}
