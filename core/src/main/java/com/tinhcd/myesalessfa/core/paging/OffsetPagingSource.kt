package com.tinhcd.myesalessfa.core.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pages an endpoint that takes `offset` and `limit`, which is how the report
 * functions page (`report_orders` and the manager's twin).
 *
 * [fetch] returns one page of rows, or throws. A page shorter than was asked
 * for is the end: the endpoints sort on a total order (date, then id), so no
 * row can slip between two pages, and nothing past a short page exists.
 *
 * Forward only. A report opens at its top and the rep scrolls down; a refresh
 * starts again from the first row rather than guessing where a moving list
 * now has the row they were looking at.
 */
class OffsetPagingSource<T : Any>(
    private val fetch: suspend (offset: Int, limit: Int) -> List<T>,
) : PagingSource<Int, T>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val offset = params.key ?: 0
        return try {
            val rows = fetch(offset, params.loadSize)
            LoadResult.Page(
                data = rows,
                prevKey = null,
                nextKey = if (rows.size < params.loadSize) null else offset + rows.size,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null
}
