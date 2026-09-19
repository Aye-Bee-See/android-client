package me.paxana.abcmailbox.ui.letters

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.LettersRepository
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.Attachment
import me.paxana.abcmailbox.domain.Thread
import me.paxana.abcmailbox.ui.directory.Loadable
import me.paxana.abcmailbox.ui.nav.ThreadRoute
import java.io.File
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(repo: LettersRepository) : ViewModel() {
  val threads: Flow<PagingData<Thread>> = repo.threads().cachedIn(viewModelScope)
}

data class ThreadUiState(
  val thread: Loadable<Thread> = Loadable.Loading,
  val retentionDays: Int? = null,
  val busyMessageId: Int? = null,
  /** Set for group members: the group they act for. Null for writers. */
  val staffGroupId: Int? = null,
  val isStaff: Boolean = false,
  val notice: String? = null,
  /** A downloaded attachment ready to open, consumed by the screen. */
  val openFile: Pair<File, String>? = null,
)

@HiltViewModel
class ThreadViewModel(
  private val repo: LettersRepository,
  sessions: SessionRepository,
  private val route: ThreadRoute,
  private val strings: Strings,
) : ViewModel() {

  @Inject
  constructor(repo: LettersRepository, sessions: SessionRepository, strings: Strings, savedStateHandle: SavedStateHandle) :
    this(repo, sessions, savedStateHandle.toRoute<ThreadRoute>(), strings)

  // Who is looking decides what the screen offers: a group member records replies and writes for its writers.
  private val viewer = (sessions.state.value as? SessionState.SignedIn)?.session?.user
  private val _ui = MutableStateFlow(ThreadUiState(isStaff = viewer?.isStaff == true, staffGroupId = viewer?.takeIf { it.isStaff }?.chapterId))
  val ui: StateFlow<ThreadUiState> = _ui.asStateFlow()

  init {
    load()
    viewModelScope.launch { (repo.retentionDays() as? ApiResult.Success)?.let { r -> _ui.update { it.copy(retentionDays = r.value) } } }
  }

  fun load() {
    viewModelScope.launch {
      val current = _ui.value.thread
      if (current !is Loadable.Loaded) _ui.update { it.copy(thread = Loadable.Loading) }
      _ui.update { st ->
        st.copy(thread = when (val r = repo.thread(route.chatId)) {
          is ApiResult.Success -> Loadable.Loaded(r.value)
          is ApiResult.Failure -> Loadable.Failed(r.error)
        })
      }
    }
  }

  fun delete(messageId: Int) {
    _ui.update { it.copy(busyMessageId = messageId) }
    viewModelScope.launch {
      val notice = when (val r = repo.delete(messageId)) {
        is ApiResult.Success -> strings.get(R.string.letter_deleted)
        is ApiResult.Failure -> r.error.userMessage ?: strings.get(R.string.error_delete_letter)
      }
      _ui.update { it.copy(busyMessageId = null, notice = notice) }
      load()
    }
  }

  fun open(attachment: Attachment) {
    viewModelScope.launch {
      when (val r = repo.download(attachment)) {
        is ApiResult.Success -> _ui.update { it.copy(openFile = r.value to attachment.mimeType) }
        is ApiResult.Failure -> _ui.update { it.copy(notice = r.error.userMessage ?: strings.get(R.string.error_download_file)) }
      }
    }
  }

  fun fileOpened() = _ui.update { it.copy(openFile = null) }
  fun noticeShown() = _ui.update { it.copy(notice = null) }
}

internal fun AppError.orGeneric(fallback: String) = userMessage ?: fallback

data class UnlockUiState(val password: String = "", val busy: Boolean = false, val error: String? = null)

@HiltViewModel
class UnlockViewModel @Inject constructor(private val sessions: me.paxana.abcmailbox.data.session.SessionRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(UnlockUiState())
  val ui: StateFlow<UnlockUiState> = _ui.asStateFlow()
  fun onPassword(v: String) = _ui.update { it.copy(password = v, error = null) }
  fun unlock() {
    val pw = _ui.value.password
    if (pw.isEmpty()) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = sessions.unlock(pw)) {
        is ApiResult.Success -> _ui.update { UnlockUiState() }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: strings.get(R.string.error_unlock)) }
      }
    }
  }
}
