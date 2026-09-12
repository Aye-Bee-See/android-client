package me.paxana.abcmailbox.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import me.paxana.abcmailbox.data.api.AppError

/**
 * A LazyColumn over Paging's items with the three states every list needs:
 * first load (spinner or error), empty, and "loading more" at the bottom.
 * The `header` slot holds search and filter chips so they scroll with the list.
 */
@Composable
fun <T : Any> PagedList(
  items: LazyPagingItems<T>,
  emptyText: String,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(),
  header: (LazyListScope.() -> Unit)? = null,
  row: @Composable (T) -> Unit,
) {
  LazyColumn(modifier = modifier, contentPadding = contentPadding) {
    header?.invoke(this)
    when (val refresh = items.loadState.refresh) {
      is LoadState.Loading -> item("refresh-loading") { LoadingBox() }
      is LoadState.Error -> item("refresh-error") { ErrorBox(refresh.error.asAppError(), onRetry = { items.retry() }) }
      is LoadState.NotLoading -> if (items.itemCount == 0) item("empty") { EmptyBox(emptyText) }
    }
    items(count = items.itemCount) { index ->
      items[index]?.let { row(it) }
      HorizontalDivider()
    }
    when (val append = items.loadState.append) {
      is LoadState.Loading -> item("append-loading") { LoadingBox() }
      is LoadState.Error -> item("append-error") { ErrorBox(append.error.asAppError(), onRetry = { items.retry() }) }
      else -> Unit
    }
  }
}

private fun Throwable.asAppError(): AppError = this as? AppError ?: AppError.Unexpected(this)
