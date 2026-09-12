package me.paxana.abcmailbox.data.repo

import androidx.paging.PagingSource
import kotlinx.coroutines.test.runTest
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PagePagingSourceTest {

  private fun source(total: Int, fail: Boolean = false) = PagePagingSource<Int> { page, size ->
    if (fail) return@PagePagingSource ApiResult.Failure(AppError.Network(IOException()))
    val start = (page - 1) * size
    val items = (start until minOf(start + size, total)).toList()
    ApiResult.Success(Page(items, total, page, size))
  }

  @Test
  fun `first page points to the second`() = runTest {
    val r = source(total = 45).load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
    assertEquals(20, r.data.size)
    assertNull(r.prevKey)
    assertEquals(2, r.nextKey)
  }

  @Test
  fun `last page has no next key`() = runTest {
    val r = source(total = 45).load(PagingSource.LoadParams.Append(3, 20, false)) as PagingSource.LoadResult.Page
    assertEquals(5, r.data.size)
    assertEquals(2, r.prevKey)
    assertNull(r.nextKey)
  }

  @Test
  fun `exact multiple stops without an empty extra page`() = runTest {
    val r = source(total = 40).load(PagingSource.LoadParams.Append(2, 20, false)) as PagingSource.LoadResult.Page
    assertNull(r.nextKey)
  }

  @Test
  fun `failure surfaces the AppError`() = runTest {
    val r = source(total = 1, fail = true).load(PagingSource.LoadParams.Refresh(null, 20, false))
    assertTrue(r is PagingSource.LoadResult.Error && r.throwable is AppError.Network)
  }
}
