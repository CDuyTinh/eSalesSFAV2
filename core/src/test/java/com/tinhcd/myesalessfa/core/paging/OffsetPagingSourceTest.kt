package com.tinhcd.myesalessfa.core.paging

import androidx.paging.PagingConfig
import androidx.paging.PagingSource.LoadResult
import androidx.paging.testing.TestPager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsetPagingSourceTest {

    private val rows = (1..65).toList()
    private val config = PagingConfig(pageSize = 30, initialLoadSize = 30, enablePlaceholders = false)
    private val asked = mutableListOf<Pair<Int, Int>>()

    private fun source() = OffsetPagingSource { offset, limit ->
        asked += offset to limit
        rows.drop(offset).take(limit)
    }

    @Test
    fun `pages forward by offset until a short page`() = runTest {
        val pager = TestPager(config, source())

        val first = pager.refresh() as LoadResult.Page
        val second = pager.append() as LoadResult.Page
        val third = pager.append() as LoadResult.Page

        assertEquals((1..30).toList(), first.data)
        assertEquals(30, first.nextKey)
        assertEquals((31..60).toList(), second.data)
        assertEquals((61..65).toList(), third.data)
        // Five rows where thirty were asked for: nothing further is requested.
        assertNull(third.nextKey)
        assertEquals(listOf(0 to 30, 30 to 30, 60 to 30), asked)
    }

    @Test
    fun `a full last page costs one empty request, then stops`() = runTest {
        val exact = OffsetPagingSource { offset, limit -> (1..60).toList().drop(offset).take(limit) }
        val pager = TestPager(config, exact)

        pager.refresh()
        pager.append()
        val tail = pager.append() as LoadResult.Page

        assertTrue(tail.data.isEmpty())
        assertNull(tail.nextKey)
    }

    @Test
    fun `a failed fetch is a load error, not a crash`() = runTest {
        val failing = OffsetPagingSource<Int> { _, _ -> error("offline") }

        val result = TestPager(config, failing).refresh()

        assertTrue(result is LoadResult.Error)
        assertEquals("offline", (result as LoadResult.Error).throwable.message)
    }
}
