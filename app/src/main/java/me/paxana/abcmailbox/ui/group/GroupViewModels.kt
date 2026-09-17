package me.paxana.abcmailbox.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.crypto.GroupKeyState
import me.paxana.abcmailbox.data.repo.GroupRepository
import me.paxana.abcmailbox.data.repo.LettersRepository
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.Attachment
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.GroupMember
import me.paxana.abcmailbox.domain.IssuedToken
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.ManagedWriter
import me.paxana.abcmailbox.domain.QueueItem
import me.paxana.abcmailbox.ui.directory.Loadable
import me.paxana.abcmailbox.ui.nav.HandoffRoute
import me.paxana.abcmailbox.ui.nav.LetterWorkRoute
import java.io.File
import javax.inject.Inject

/** The print queue: letters this group relays, one status at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QueueViewModel @Inject constructor(private val group: GroupRepository, sessions: SessionRepository) : ViewModel() {
  private val groupId: Int? = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.chapterId
  private val _status = MutableStateFlow(LetterStatus.QUEUED)
  val status: StateFlow<LetterStatus> = _status.asStateFlow()
  /** False when the account has no group yet; the API would answer 403 and explain, but there is nothing to ask for. */
  val hasGroup: Boolean = groupId != null

  val items: Flow<PagingData<QueueItem>> = _status
    .flatMapLatest { s -> groupId?.let { group.queue(it, s) } ?: emptyFlow() }
    .cachedIn(viewModelScope)

  fun setStatus(s: LetterStatus) { _status.value = s }
}

@HiltViewModel
class WritersViewModel @Inject constructor(private val group: GroupRepository) : ViewModel() {
  private val _writers = MutableStateFlow<Loadable<List<ManagedWriter>>>(Loadable.Loading)
  val writers: StateFlow<Loadable<List<ManagedWriter>>> = _writers.asStateFlow()

  fun load() {
    viewModelScope.launch {
      if (_writers.value !is Loadable.Loaded) _writers.value = Loadable.Loading
      _writers.value = when (val r = group.writers()) {
        is ApiResult.Success -> Loadable.Loaded(r.value)
        is ApiResult.Failure -> Loadable.Failed(r.error)
      }
    }
  }
}

data class LetterWorkUiState(
  val item: Loadable<QueueItem> = Loadable.Loading,
  val busy: Boolean = false,
  val notice: String? = null,
  val openFile: Pair<File, String>? = null,
  /** End-to-end only: other relay groups of the facility this letter could be shared with. */
  val partners: List<Group> = emptyList(),
)

/** One letter as the relay group sees it: who it goes to, what it says, and where it is in the queue. */
@HiltViewModel
class LetterWorkViewModel(
  private val group: GroupRepository,
  private val letters: LettersRepository,
  private val route: LetterWorkRoute,
) : ViewModel() {
  @Inject constructor(group: GroupRepository, letters: LettersRepository, handle: SavedStateHandle) : this(group, letters, handle.toRoute<LetterWorkRoute>())

  private val _ui = MutableStateFlow(LetterWorkUiState())
  val ui: StateFlow<LetterWorkUiState> = _ui.asStateFlow()

  init { load() }

  fun load() {
    viewModelScope.launch {
      val r = group.queueItem(route.messageId)
      _ui.update { st -> st.copy(item = when (r) {
        is ApiResult.Success -> Loadable.Loaded(r.value)
        is ApiResult.Failure -> Loadable.Failed(r.error)
      }) }
      (r as? ApiResult.Success)?.value?.letter?.prisonerId?.let { id -> _ui.update { it.copy(partners = group.partnersFor(id)) } }
    }
  }

  /** Seals this letter's content key to a partner relay group. The letter itself is not re-encrypted or moved. */
  fun share(partner: Group) {
    _ui.update { it.copy(busy = true) }
    viewModelScope.launch {
      val r = group.shareWith(route.messageId, partner.id)
      _ui.update { it.copy(busy = false, notice = when (r) {
        is ApiResult.Success -> "${partner.name} can now read this letter."
        is ApiResult.Failure -> r.error.userMessage ?: "Could not share the letter."
      }) }
    }
  }

  /** The lifecycle only moves forward; the API refuses anything else and its sentence is shown. */
  fun advance() {
    val current = (_ui.value.item as? Loadable.Loaded)?.value ?: return
    val next = when (current.letter.status) {
      LetterStatus.QUEUED -> LetterStatus.PRINTED
      LetterStatus.PRINTED -> LetterStatus.MAILED
      else -> return
    }
    _ui.update { it.copy(busy = true) }
    viewModelScope.launch {
      when (val r = group.setStatus(route.messageId, next)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, item = Loadable.Loaded(current.copy(letter = r.value.copy(attachments = current.letter.attachments))), notice = "Marked as ${next.label.lowercase()}.") }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, notice = r.error.userMessage ?: "Could not update the letter.") }
      }
    }
  }

  fun open(attachment: Attachment) {
    viewModelScope.launch {
      when (val r = letters.download(attachment)) {
        is ApiResult.Success -> _ui.update { it.copy(openFile = r.value to attachment.mimeType) }
        is ApiResult.Failure -> _ui.update { it.copy(notice = r.error.userMessage ?: "Could not download the file.") }
      }
    }
  }

  fun fileOpened() = _ui.update { it.copy(openFile = null) }
  fun noticeShown() = _ui.update { it.copy(notice = null) }
}

data class AddWriterUiState(val name: String = "", val email: String = "", val note: String = "", val busy: Boolean = false, val error: String? = null, val created: ManagedWriter? = null, val thenWrite: Boolean = false) {
  val canSubmit: Boolean get() = !busy && name.trim().length in 3..32
}

@HiltViewModel
class AddWriterViewModel @Inject constructor(private val group: GroupRepository) : ViewModel() {
  private val _ui = MutableStateFlow(AddWriterUiState())
  val ui: StateFlow<AddWriterUiState> = _ui.asStateFlow()
  fun onName(v: String) = _ui.update { it.copy(name = v, error = null) }
  fun onEmail(v: String) = _ui.update { it.copy(email = v, error = null) }
  fun onNote(v: String) = _ui.update { it.copy(note = v, error = null) }

  fun submit(thenWrite: Boolean) {
    val s = _ui.value
    if (!s.canSubmit) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = group.addWriter(s.name, s.email, s.note)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, created = r.value, thenWrite = thenWrite) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: "Could not add the writer.") }
      }
    }
  }
}

data class HandoffUiState(val writerName: String, val token: IssuedToken? = null, val busy: Boolean = false, val error: String? = null, val revoked: Boolean = false)

/** The claim token is shown once, here, and is never stored on the phone. */
@HiltViewModel
class HandoffViewModel(private val group: GroupRepository, private val route: HandoffRoute) : ViewModel() {
  @Inject constructor(group: GroupRepository, handle: SavedStateHandle) : this(group, handle.toRoute<HandoffRoute>())

  private val _ui = MutableStateFlow(HandoffUiState(route.writerName))
  val ui: StateFlow<HandoffUiState> = _ui.asStateFlow()

  fun generate() {
    _ui.update { it.copy(busy = true, error = null, revoked = false) }
    viewModelScope.launch {
      when (val r = group.issueToken(route.writerId)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, token = r.value) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: "Could not make a token.") }
      }
    }
  }

  fun revoke() {
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = group.revokeToken(route.writerId)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, token = null, revoked = true) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: "Could not revoke the token.") }
      }
    }
  }
}

// End-to-end mode: the group's key ------------------------------------------------------------

data class GroupKeyUiState(
  val members: Loadable<List<GroupMember>> = Loadable.Loading,
  val busy: Boolean = false,
  val busyMemberId: Int? = null,
  val error: String? = null,
  val notice: String? = null,
)

/**
 * The group key banner on the inbox and the members screen share this. The
 * key's state lives in the repository (it is a fact about the session, not
 * about a screen); this only adds what a screen needs around it.
 */
@HiltViewModel
class GroupKeyViewModel @Inject constructor(private val group: GroupRepository) : ViewModel() {
  val keyState: StateFlow<GroupKeyState> = group.keyState
  private val _ui = MutableStateFlow(GroupKeyUiState())
  val ui: StateFlow<GroupKeyUiState> = _ui.asStateFlow()

  /** Asks the server again: another member may have set the key up, or handed it to this one, since the last look. */
  fun refresh() { viewModelScope.launch { group.refreshKeyState() } }

  fun setUp() {
    if (_ui.value.busy) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      val r = group.setUpGroupKey()
      _ui.update {
        it.copy(busy = false, error = (r as? ApiResult.Failure)?.error?.let { e -> e.userMessage ?: "Could not set up the group key." },
          notice = if (r is ApiResult.Success) "The group key is set up. Hand it to the other members so they can read letters too." else null)
      }
    }
  }

  fun loadMembers() {
    viewModelScope.launch {
      _ui.update { it.copy(members = when (val r = group.members()) { is ApiResult.Success -> Loadable.Loaded(r.value); is ApiResult.Failure -> Loadable.Failed(r.error) }) }
    }
  }

  fun hand(member: GroupMember) = change(member, "${member.name} can now read the group's letters, from their next sign-in or refresh.") { group.handKeyTo(member.id) }
  fun stop(member: GroupMember) = change(member, "${member.name} will no longer be handed the group key.") { group.stopHandingKeyTo(member.id) }

  private fun change(member: GroupMember, done: String, call: suspend () -> ApiResult<Unit>) {
    if (_ui.value.busyMemberId != null) return
    _ui.update { it.copy(busyMemberId = member.id, error = null, notice = null) }
    viewModelScope.launch {
      when (val r = call()) {
        is ApiResult.Success -> { _ui.update { it.copy(busyMemberId = null, notice = done) }; loadMembers() }
        is ApiResult.Failure -> _ui.update { it.copy(busyMemberId = null, error = r.error.userMessage ?: "That did not work. Please try again.") }
      }
    }
  }

  fun noticeShown() = _ui.update { it.copy(notice = null) }
}
