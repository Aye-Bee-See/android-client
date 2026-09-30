package me.paxana.abcmailbox.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.message
import me.paxana.abcmailbox.data.repo.BanRecommendation
import me.paxana.abcmailbox.data.repo.BlockedWriter
import me.paxana.abcmailbox.data.repo.BlocksRepository
import me.paxana.abcmailbox.data.repo.WriterBlock
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.SectionTitle
import me.paxana.abcmailbox.ui.common.longDate
import me.paxana.abcmailbox.ui.directory.Loadable
import javax.inject.Inject

data class BlocksUiState(
  val blocks: Loadable<List<WriterBlock>> = Loadable.Loading,
  /** Shown under the blocks; a list that fails to load says so there, and the blocks still show. */
  val recommendations: Loadable<List<BanRecommendation>> = Loadable.Loading,
  val busyWriterId: Int? = null,
  val notice: String? = null,
)

/** The group's blocks (API #171), with Unblock, and its site-wide recommendations and what became of them (API #172). */
@HiltViewModel
class BlocksViewModel @Inject constructor(private val repo: BlocksRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(BlocksUiState())
  val ui: StateFlow<BlocksUiState> = _ui.asStateFlow()

  init { load() }

  fun load() {
    viewModelScope.launch {
      val b = repo.blocks()
      _ui.update { it.copy(blocks = when (b) { is ApiResult.Success -> Loadable.Loaded(b.value); is ApiResult.Failure -> Loadable.Failed(b.error) }) }
      val r = repo.recommendations()
      _ui.update { it.copy(recommendations = when (r) { is ApiResult.Success -> Loadable.Loaded(r.value); is ApiResult.Failure -> Loadable.Failed(r.error) }) }
    }
  }

  /** The writer's held letters go back into the queue as they were, and the writer is told the block is lifted. */
  fun unblock(writer: BlockedWriter) {
    if (_ui.value.busyWriterId != null) return
    _ui.update { it.copy(busyWriterId = writer.id) }
    viewModelScope.launch {
      when (val r = repo.unblock(writer.id)) {
        is ApiResult.Success -> {
          _ui.update { st -> st.copy(busyWriterId = null, notice = strings.plural(R.plurals.notice_unblocked, r.value, r.value), blocks = (st.blocks as? Loadable.Loaded)?.let { l -> Loadable.Loaded(l.value.filter { it.writer.id != writer.id }) } ?: st.blocks) }
        }
        is ApiResult.Failure -> _ui.update { it.copy(busyWriterId = null, notice = r.error.message(strings) ?: strings.get(R.string.error_unblock)) }
      }
    }
  }

  fun noticeShown() = _ui.update { it.copy(notice = null) }
}

@Composable
fun BlocksScreen(onBack: () -> Unit, viewModel: BlocksViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  var confirm by remember { mutableStateOf<BlockedWriter?>(null) }
  LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() } }
  DetailScaffold(title = stringResource(R.string.title_blocked_writers), onBack = onBack) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val b = ui.blocks) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(b.error, onRetry = viewModel::load)
        is Loadable.Loaded -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.blocks_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (b.value.isEmpty()) Text(stringResource(R.string.blocks_none), style = MaterialTheme.typography.bodyLarge)
          b.value.forEach { block ->
            Column(Modifier.fillMaxWidth().testTag("block-${block.writer.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(block.writer.shownName ?: stringResource(R.string.writer_numbered, block.writer.id), style = MaterialTheme.typography.titleMedium)
              Text(stringResource(R.string.block_reason_line, block.reason), style = MaterialTheme.typography.bodyMedium)
              val by = block.blockedBy
              val on = block.blockedAt?.longDate()
              if (by != null || on != null) Text(
                when { by != null && on != null -> stringResource(R.string.blocked_by_on, by, on); by != null -> stringResource(R.string.blocked_by, by); else -> stringResource(R.string.blocked_on, on!!) },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              TextButton(onClick = { confirm = block.writer }, enabled = ui.busyWriterId == null, modifier = Modifier.testTag("unblock-${block.writer.id}")) { Text(stringResource(R.string.action_unblock)) }
            }
            HorizontalDivider()
          }

          SectionTitle(stringResource(R.string.section_ban_recommendations))
          Text(stringResource(R.string.ban_recommendations_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          when (val r = ui.recommendations) {
            is Loadable.Loading -> Unit
            is Loadable.Failed -> Text(stringResource(R.string.ban_recommendations_failed), color = MaterialTheme.colorScheme.error)
            is Loadable.Loaded -> {
              if (r.value.isEmpty()) Text(stringResource(R.string.ban_recommendations_none), style = MaterialTheme.typography.bodyMedium)
              r.value.forEach { rec -> Recommendation(rec) }
            }
          }
        }
      }
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { Snackbar(it) }
    }
  }
  confirm?.let { writer ->
    val name = writer.shownName ?: stringResource(R.string.writer_numbered, writer.id)
    AlertDialog(
      onDismissRequest = { confirm = null },
      title = { Text(stringResource(R.string.unblock_title, name)) },
      text = { Text(stringResource(R.string.unblock_text)) },
      confirmButton = { TextButton(onClick = { confirm = null; viewModel.unblock(writer) }, modifier = Modifier.testTag("unblock-confirm")) { Text(stringResource(R.string.action_unblock)) } },
      dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }
}

@Composable
private fun Recommendation(rec: BanRecommendation) {
  Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("recommendation-${rec.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(rec.writer?.shownName ?: rec.writer?.let { stringResource(R.string.writer_numbered, it.id) } ?: stringResource(R.string.writer_unknown), style = MaterialTheme.typography.titleSmall)
    Text(when (rec.status) {
      BanRecommendation.Status.PENDING -> rec.createdAt?.let { stringResource(R.string.recommendation_pending_since, it.longDate()) } ?: stringResource(R.string.recommendation_pending)
      BanRecommendation.Status.BANNED -> rec.decidedAt?.let { stringResource(R.string.recommendation_banned_on, it.longDate()) } ?: stringResource(R.string.recommendation_banned)
      BanRecommendation.Status.DISMISSED -> rec.decidedAt?.let { stringResource(R.string.recommendation_dismissed_on, it.longDate()) } ?: stringResource(R.string.recommendation_dismissed)
    }, style = MaterialTheme.typography.bodyMedium, color = if (rec.status == BanRecommendation.Status.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary)
    Text(stringResource(R.string.recommendation_your_reason, rec.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    rec.decisionNote?.let { Text(stringResource(R.string.recommendation_note, it), style = MaterialTheme.typography.bodyMedium) }
  }
}
