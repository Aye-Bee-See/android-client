package me.paxana.abcmailbox.data.repo

import androidx.paging.PagingSource
import androidx.paging.PagingState
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.Page

/**
 * Paging 3 over the API's 1-based `page` / `page_size` lists. One class serves
 * every list endpoint: the caller supplies the request as a function.
 *
 * Paging asks for a key (a page number), gets back the items plus the keys of
 * the neighbouring pages, and stops when `nextKey` is null. Errors come back
 * as `LoadResult.Error` carrying our [me.paxana.abcmailbox.data.api.AppError],
 * which the list UI shows with a retry.
 */
class PagePagingSource<T : Any>(
  private val load: suspend (page: Int, pageSize: Int) -> ApiResult<Page<T>>,
) : PagingSource<Int, T>() {

  override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
    val page = params.key ?: 1
    return when (val result = load(page, params.loadSize)) {
      is ApiResult.Success -> {
        val data = result.value
        LoadResult.Page(
          data = data.items,
          prevKey = if (page > 1) page - 1 else null,
          nextKey = if (data.hasMore && data.items.isNotEmpty()) page + 1 else null,
        )
      }
      is ApiResult.Failure -> LoadResult.Error(result.error)
    }
  }

  /** Where to restart after invalidation: the page around the last viewed item. */
  override fun getRefreshKey(state: PagingState<Int, T>): Int? =
    state.anchorPosition?.let { anchor ->
      state.closestPageToPosition(anchor)?.prevKey?.plus(1) ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
    }
}
