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
  /** A `choose_relay` hold being answered: the letter, and the groups that mail to where the person is held now. */
  val relayQuestion: RelayQuestion? = null,
)

data class RelayQuestion(val messageId: Int, val facilityName: String, val options: List<me.paxana.abcmailbox.domain.Group>)

@HiltViewModel
class ThreadViewModel(
  private val repo: LettersRepository,
  sessions: SessionRepository,
  private val route: ThreadRoute,
  private val strings: Strings,
  private val directory: me.paxana.abcmailbox.data.repo.DirectoryRepository,
) : ViewModel() {

  @Inject
  constructor(repo: LettersRepository, sessions: SessionRepository, strings: Strings, directory: me.paxana.abcmailbox.data.repo.DirectoryRepository, savedStateHandle: SavedStateHandle) :
    this(repo, sessions, savedStateHandle.toRoute<ThreadRoute>(), strings, directory)

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

  /**
   * A `choose_relay` hold: the person was moved to a facility where the writer has to say who mails the letter.
   * Asks the directory where they are *now*, not what this screen loaded before the move. (As the iOS app does.)
   */
  fun askWhoMails(messageId: Int, prisonerId: Int) {
    _ui.update { it.copy(busyMessageId = messageId) }
    viewModelScope.launch {
      val prisoner = directory.prisoner(prisonerId)
      val facility = ((prisoner as? ApiResult.Success)?.value?.facilityId)?.let { directory.facility(it) }
      _ui.update { st ->
        when {
          prisoner is ApiResult.Failure -> st.copy(busyMessageId = null, notice = prisoner.error.userMessage ?: strings.get(R.string.error_lookup_relay))
          facility == null -> st.copy(busyMessageId = null, notice = strings.get(R.string.relay_place_unknown))
          facility is ApiResult.Failure -> st.copy(busyMessageId = null, notice = facility.error.userMessage ?: strings.get(R.string.error_lookup_relay))
          else -> {
            val f = (facility as ApiResult.Success).value
            val options = f.relayGroups.filter { it.accountStatus == null || it.accountStatus == "active" }
            if (options.isEmpty()) st.copy(busyMessageId = null, notice = strings.get(R.string.relay_none_listed, f.name))
            else st.copy(busyMessageId = null, relayQuestion = RelayQuestion(messageId, f.name, options))
          }
        }
      }
    }
  }

  fun relayQuestionDismissed() = _ui.update { it.copy(relayQuestion = null) }

  fun chooseRelay(group: me.paxana.abcmailbox.domain.Group) {
    val question = _ui.value.relayQuestion ?: return
    _ui.update { it.copy(relayQuestion = null, busyMessageId = question.messageId) }
    viewModelScope.launch {
      val notice = when (val r = repo.chooseRelay(question.messageId, group.id)) {
        is ApiResult.Success -> strings.get(R.string.relay_chosen, group.name)
        is ApiResult.Failure -> r.error.userMessage ?: strings.get(R.string.error_choose_relay)
      }
      _ui.update { it.copy(busyMessageId = null, notice = notice) }
      load()
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

/** [passwordStaysOnPhone] starts false, so the prompt never promises more than it knows while it asks. */
data class UnlockUiState(val password: String = "", val busy: Boolean = false, val error: String? = null, val passwordStaysOnPhone: Boolean = false)

@HiltViewModel
class UnlockViewModel @Inject constructor(private val sessions: me.paxana.abcmailbox.data.session.SessionRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(UnlockUiState())
  val ui: StateFlow<UnlockUiState> = _ui.asStateFlow()
  init { viewModelScope.launch { val stays = sessions.passwordStaysOnPhone(); _ui.update { it.copy(passwordStaysOnPhone = stays) } } }
  fun onPassword(v: String) = _ui.update { it.copy(password = v, error = null) }
  fun unlock() {
    val pw = _ui.value.password
    if (pw.isEmpty()) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = sessions.unlock(pw)) {
        is ApiResult.Success -> _ui.update { UnlockUiState(passwordStaysOnPhone = it.passwordStaysOnPhone) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: strings.get(R.string.error_unlock)) }
      }
    }
  }
}
