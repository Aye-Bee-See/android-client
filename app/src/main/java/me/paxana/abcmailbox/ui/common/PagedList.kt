package me.paxana.abcmailbox.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
 * Pulling down reloads it, in every list of the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : Any> PagedList(
  items: LazyPagingItems<T>,
  emptyText: String,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(),
  header: (LazyListScope.() -> Unit)? = null,
  row: @Composable (T) -> Unit,
) {
  // Pull to refresh. Paging keeps the rows on screen while it reloads, so the pulled-down spinner is the only
  // sign of work; the big first-load spinner is for when there is nothing to show yet.
  var pulled by remember { mutableStateOf(false) }
  val refresh = items.loadState.refresh
  LaunchedEffect(refresh) { if (refresh !is LoadState.Loading) pulled = false }

  PullToRefreshBox(isRefreshing = pulled, onRefresh = { pulled = true; items.refresh() }, modifier = modifier) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
      header?.invoke(this)
      when (refresh) {
        is LoadState.Loading -> if (!pulled && items.itemCount == 0) item("refresh-loading") { LoadingBox() }
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
}

private fun Throwable.asAppError(): AppError = this as? AppError ?: AppError.Unexpected(this)
