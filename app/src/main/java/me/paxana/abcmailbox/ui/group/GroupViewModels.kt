package me.paxana.abcmailbox.ui.group

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
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
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.ReturnedAs
import me.paxana.abcmailbox.domain.ReturnReason
import me.paxana.abcmailbox.domain.ManagedWriter
import me.paxana.abcmailbox.domain.QueueItem
import me.paxana.abcmailbox.ui.directory.Loadable
import me.paxana.abcmailbox.ui.nav.HandoffRoute
import me.paxana.abcmailbox.ui.nav.LetterWorkRoute
import java.io.File
import javax.inject.Inject

/** What the print queue can show: one status at a time, or the queued letters that are held (API PR #106). */
sealed interface QueueFilter {
  data class ByStatus(val status: LetterStatus) : QueueFilter
  data object Held : QueueFilter
}

/** The print queue: letters this group relays, one filter at a time. */
/** Marking several letters at once (API PR #111). `selected` null means "not selecting". */
data class QueueSelection(val selected: Set<Int>? = null, val busy: Boolean = false, val notice: String? = null, /** Goes up after a batch went through, so the screen reloads the list. */ val done: Int = 0) {
  val selecting: Boolean get() = selected != null
  val count: Int get() = selected?.size ?: 0
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QueueViewModel @Inject constructor(private val group: GroupRepository, sessions: SessionRepository, private val strings: Strings) : ViewModel() {
  private val groupId: Int? = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.chapterId
  private val _filter = MutableStateFlow<QueueFilter>(QueueFilter.ByStatus(LetterStatus.QUEUED))
  val filter: StateFlow<QueueFilter> = _filter.asStateFlow()
  /** False when the account has no group yet; the API would answer 403 and explain, but there is nothing to ask for. */
  val hasGroup: Boolean = groupId != null

  val items: Flow<PagingData<QueueItem>> = _filter
    .flatMapLatest { f -> groupId?.let { id -> when (f) { is QueueFilter.ByStatus -> group.queue(id, f.status); QueueFilter.Held -> group.held(id) } } ?: emptyFlow() }
    .cachedIn(viewModelScope)

  private val _selection = MutableStateFlow(QueueSelection())
  val selection: StateFlow<QueueSelection> = _selection.asStateFlow()

  /** Changing the filter ends a selection: "printed" and "mailed" are different next steps, and a tick must never carry over. */
  fun setFilter(f: QueueFilter) { _filter.value = f; _selection.update { QueueSelection(done = it.done) } }

  /** What the letters on this page can be moved to together, if anything. Held letters and returns are decided one at a time. */
  val nextStep: LetterStatus? get() = when ((_filter.value as? QueueFilter.ByStatus)?.status) { LetterStatus.QUEUED -> LetterStatus.PRINTED; LetterStatus.PRINTED -> LetterStatus.MAILED; else -> null }

  fun startSelecting() { if (nextStep != null) _selection.update { it.copy(selected = emptySet(), notice = null) } }
  fun stopSelecting() = _selection.update { it.copy(selected = null) }
  fun noticeShown() = _selection.update { it.copy(notice = null) }

  fun toggle(item: QueueItem) {
    if (item.letter.isHeld) return // printing a held letter is a decision about that letter (release), never part of a sweep
    _selection.update { st ->
      val now = st.selected ?: return@update st
      when {
        item.letter.id in now -> st.copy(selected = now - item.letter.id)
        now.size >= me.paxana.abcmailbox.data.repo.BATCH_MAX -> st.copy(notice = strings.get(R.string.error_batch_too_many, me.paxana.abcmailbox.data.repo.BATCH_MAX))
        else -> st.copy(selected = now + item.letter.id)
      }
    }
  }

  /** All or none, on the server: either every ticked letter moves, or none does and the sentence says which one stopped it. */
  fun markSelected() {
    val next = nextStep ?: return
    val ids = _selection.value.selected?.toList()?.takeIf { it.isNotEmpty() } ?: return
    if (_selection.value.busy) return
    _selection.update { it.copy(busy = true, notice = null) }
    viewModelScope.launch {
      when (val r = group.setStatusOfMany(ids, next)) {
        is ApiResult.Success -> _selection.update { QueueSelection(notice = strings.plural(if (next == LetterStatus.PRINTED) R.plurals.notice_many_printed else R.plurals.notice_many_mailed, r.value), done = it.done + 1) }
        // The ticks stay: after un-ticking the one letter that stopped it, the rest can go.
        is ApiResult.Failure -> _selection.update { it.copy(busy = false, notice = strings.get(R.string.error_batch_nothing_changed, r.error.userMessage ?: strings.get(R.string.error_update_letter))) }
      }
    }
  }
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
  private val strings: Strings,
) : ViewModel() {
  @Inject constructor(group: GroupRepository, letters: LettersRepository, strings: Strings, handle: SavedStateHandle) : this(group, letters, handle.toRoute<LetterWorkRoute>(), strings)

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
        is ApiResult.Success -> strings.get(R.string.notice_shared_with, partner.name)
        is ApiResult.Failure -> r.error.userMessage ?: strings.get(R.string.error_share)
      }) }
    }
  }

  /** The lifecycle only moves forward; the API refuses anything else and its sentence is shown. */
  /** [release]: printing a held letter on purpose. The screen asks first; this only carries the answer. */
  fun advance(release: Boolean = false) {
    val current = (_ui.value.item as? Loadable.Loaded)?.value ?: return
    val next = when (current.letter.status) {
      LetterStatus.QUEUED -> LetterStatus.PRINTED
      LetterStatus.PRINTED -> LetterStatus.MAILED
      else -> return
    }
    _ui.update { it.copy(busy = true) }
    viewModelScope.launch {
      when (val r = group.setStatus(route.messageId, next, release = release)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, item = Loadable.Loaded(current.copy(letter = r.value.copy(attachments = current.letter.attachments))), notice = strings.get(if (next == LetterStatus.PRINTED) R.string.notice_marked_printed else R.string.notice_marked_mailed)) }
        is ApiResult.Failure -> {
          // Held since this screen loaded: the person was moved or freed in the meantime. Not a question to pop at
          // someone mid-press: say so, and show the letter again, now with its reason and "Print it anyway…".
          val held = (r.error as? AppError.Conflict)?.name == "LetterHeldError"
          _ui.update { it.copy(busy = false, notice = if (held) strings.get(R.string.notice_held_since) else r.error.userMessage ?: strings.get(R.string.error_update_letter)) }
          if (held) load()
        }
      }
    }
  }

  /** The mail brought it back. From `mailed` only; the writer is told, with the reason's code and never the note's text on a lock screen. */
  fun markReturned(reason: ReturnReason, note: String) {
    val current = (_ui.value.item as? Loadable.Loaded)?.value ?: return
    if (current.letter.status != LetterStatus.MAILED) return
    _ui.update { it.copy(busy = true) }
    viewModelScope.launch {
      when (val r = group.setStatus(route.messageId, LetterStatus.RETURNED, returned = ReturnedAs(reason, note))) {
        is ApiResult.Success -> {
          // The full letter again: the note lives on the history row, which the status answer does not carry.
          _ui.update { it.copy(busy = false, item = Loadable.Loaded(current.copy(letter = r.value.copy(attachments = current.letter.attachments))), notice = strings.get(if (reason.putsAddressInDoubt) R.string.notice_returned_address else R.string.notice_returned)) }
          load()
        }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, notice = r.error.userMessage ?: strings.get(R.string.error_update_letter)) }
      }
    }
  }

  fun open(attachment: Attachment) {
    viewModelScope.launch {
      when (val r = letters.download(attachment)) {
        is ApiResult.Success -> _ui.update { it.copy(openFile = r.value to attachment.mimeType) }
        is ApiResult.Failure -> _ui.update { it.copy(notice = r.error.userMessage ?: strings.get(R.string.error_download_file)) }
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
class AddWriterViewModel @Inject constructor(private val group: GroupRepository, private val strings: Strings) : ViewModel() {
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
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: strings.get(R.string.error_add_writer)) }
      }
    }
  }
}

data class HandoffUiState(val writerName: String, val token: IssuedToken? = null, val busy: Boolean = false, val error: String? = null, val revoked: Boolean = false)

/** The claim token is shown once, here, and is never stored on the phone. */
@HiltViewModel
class HandoffViewModel(private val group: GroupRepository, private val route: HandoffRoute, private val strings: Strings) : ViewModel() {
  @Inject constructor(group: GroupRepository, strings: Strings, handle: SavedStateHandle) : this(group, handle.toRoute<HandoffRoute>(), strings)

  private val _ui = MutableStateFlow(HandoffUiState(route.writerName))
  val ui: StateFlow<HandoffUiState> = _ui.asStateFlow()

  fun generate() {
    _ui.update { it.copy(busy = true, error = null, revoked = false) }
    viewModelScope.launch {
      when (val r = group.issueToken(route.writerId)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, token = r.value) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: strings.get(R.string.error_make_token)) }
      }
    }
  }

  fun revoke() {
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = group.revokeToken(route.writerId)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, token = null, revoked = true) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: strings.get(R.string.error_revoke_token)) }
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
class GroupKeyViewModel @Inject constructor(private val group: GroupRepository, private val strings: Strings) : ViewModel() {
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
        it.copy(busy = false, error = (r as? ApiResult.Failure)?.error?.let { e -> e.userMessage ?: strings.get(R.string.error_set_up_group_key) },
          notice = if (r is ApiResult.Success) strings.get(R.string.notice_group_key_set_up) else null)
      }
    }
  }

  fun loadMembers() {
    viewModelScope.launch {
      _ui.update { it.copy(members = when (val r = group.members()) { is ApiResult.Success -> Loadable.Loaded(r.value); is ApiResult.Failure -> Loadable.Failed(r.error) }) }
    }
  }

  fun hand(member: GroupMember) = change(member, strings.get(R.string.notice_key_handed, member.name)) { group.handKeyTo(member.id) }
  fun stop(member: GroupMember) = change(member, strings.get(R.string.notice_key_stopped, member.name)) { group.stopHandingKeyTo(member.id) }
  /** Only offered for a member who holds the key: an owner who did not could hand it to nobody, not even themselves. */
  fun makeOwner(member: GroupMember) = change(member, strings.get(R.string.notice_owner_made, member.name)) { when (val r = group.makeOwner(member.id)) { is ApiResult.Success -> ApiResult.Success(Unit); is ApiResult.Failure -> r } }

  private fun change(member: GroupMember, done: String, call: suspend () -> ApiResult<Unit>) {
    if (_ui.value.busyMemberId != null) return
    _ui.update { it.copy(busyMemberId = member.id, error = null, notice = null) }
    viewModelScope.launch {
      when (val r = call()) {
        is ApiResult.Success -> { _ui.update { it.copy(busyMemberId = null, notice = done) }; loadMembers() }
        is ApiResult.Failure -> _ui.update { it.copy(busyMemberId = null, error = r.error.userMessage ?: strings.get(R.string.error_did_not_work)) }
      }
    }
  }

  fun noticeShown() = _ui.update { it.copy(notice = null) }
}
