package me.paxana.abcmailbox.ui.letters

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.data.api.message
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.OutboxRepository
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.files.StagedFile
import me.paxana.abcmailbox.data.repo.DirectoryRepository
import me.paxana.abcmailbox.data.repo.Draft
import me.paxana.abcmailbox.data.repo.DraftsRepository
import me.paxana.abcmailbox.data.repo.LetterEdit
import me.paxana.abcmailbox.data.repo.LettersRepository
import me.paxana.abcmailbox.data.repo.NewLetter
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.ComposeAdvice
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.MailRules
import me.paxana.abcmailbox.domain.composeAdvice
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.domain.RelayChoice
import me.paxana.abcmailbox.domain.estimatePages
import me.paxana.abcmailbox.domain.resolveRelay
import me.paxana.abcmailbox.ui.nav.ComposeRoute
import javax.inject.Inject

/** The API's default since PR #84 (`UPLOAD_MAX_BYTES`, 20 MiB). */
const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
val ATTACHMENT_MIME_TYPES = arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp")

data class ComposeUiState(
  val editing: Boolean = false,
  /** Group accounts: who the letter is from ("Anonymous writer" or a managed writer's name). */
  val writingAs: String? = null,
  /** Group accounts: recording a prisoner's reply rather than writing a letter. */
  val recordingReply: Boolean = false,
  /** Set while the camera app is open; the shot lands in this file. */
  val cameraTarget: java.io.File? = null,
  val prisoner: Prisoner? = null,
  val facility: Facility? = null,
  val relay: RelayChoice = RelayChoice.Direct,
  val selectedRelay: Int? = null,
  val body: String = "",
  val note: String = "",
  val showNote: Boolean = false,
  val attachments: List<StagedFile> = emptyList(),
  val loading: Boolean = true,
  val sending: Boolean = false,
  val progress: String? = null,
  val error: String? = null,
  val draftRestored: Boolean = false,
  /** This letter takes the place of one that came back, or of one that was held: said at the top, so the person knows why the text is already there. */
  val sendingAgain: Boolean = false,
  /** The letter is with the server and its files have been tried: the screen leaves, for [sentChatId] where it is known. */
  val sent: Boolean = false,
  val sentChatId: Int? = null,
  /** The letter went, and some of its files did not: said where the person lands, since this screen is done. */
  val sentNotice: String? = null,
  /** Opened on a queued letter that has since gone (sent, or deleted): what is here must not be sent, it would be a second letter. */
  val queuedCopyGone: Boolean = false,
  /** Back on this screen after being away from it: asking whether the queued letter is still there to edit. */
  val recheckingQueued: Boolean = false,
  /** The server could not be reached, so the letter went to the outbox instead. The screen closes and says so. */
  val queuedOffline: Boolean = false,
  /** Queued because the server is pacing this account (a `429`), not for want of a connection: when it goes. */
  val queuedLimitedUntil: java.time.Instant? = null,
) {
  val characters: Int get() = body.length
  val pages: Int get() = estimatePages(characters)
  val relayIsBlocked: Boolean get() = relay is RelayChoice.Blocked
  val needsRelayChoice: Boolean get() = !recordingReply && (relay as? RelayChoice.Choose)?.required == true && selectedRelay == null
  private val mailRules: MailRules get() = facility?.rules ?: MailRules()
  /** What the facility's rules mean for this letter; recomputed as the writer types. */
  fun advice(strings: Strings): List<ComposeAdvice> = composeAdvice(mailRules, pages, attachments.count { it.mimeType.startsWith("image/") }, strings)
  /** Where pictures are refused only a PDF may be attached (API guidance for `no_photos`). */
  val allowedAttachmentTypes: Array<String> get() = if (mailRules.forbidsPhotos) arrayOf("application/pdf") else ATTACHMENT_MIME_TYPES
  // A recorded reply is not mailed anywhere, so the facility's routing cannot block it.
  val canSend: Boolean get() = !loading && !sending && !sent && !queuedCopyGone && !recheckingQueued && (recordingReply || (!relayIsBlocked && !needsRelayChoice)) && (body.isNotBlank() || attachments.isNotEmpty())
}

/**
 * New letter or edit of a queued one. Loads the prisoner and facility (for
 * the rules card and relay resolution), restores a draft, autosaves the draft
 * while typing, and on send creates the letter then uploads attachments.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ComposeViewModel(
  private val letters: LettersRepository,
  private val directory: DirectoryRepository,
  private val drafts: DraftsRepository,
  private val files: LocalFilesContract,
  private val sessions: SessionRepository,
  private val route: ComposeRoute,
  private val outbox: OutboxRepository,
  private val strings: Strings,
) : ViewModel() {

  /**
   * Hilt uses this one. `toRoute` reads a Bundle, which does not exist on the
   * JVM, so tests construct the ViewModel with the route directly instead.
   */
  @Inject
  constructor(
    letters: LettersRepository,
    directory: DirectoryRepository,
    drafts: DraftsRepository,
    files: LocalFilesContract,
    sessions: SessionRepository,
    outbox: OutboxRepository,
    strings: Strings,
    savedStateHandle: SavedStateHandle,
  ) : this(letters, directory, drafts, files, sessions, savedStateHandle.toRoute<ComposeRoute>(), outbox, strings)
  // Read each time, never once: this screen is opened signed out (it offers to sign in and comes back to the same
  // ViewModel), and restored after the process died before the stored session has been read. As the iOS model does.
  private val me get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user
  private val userId: Int? get() = me?.id
  private val isStaff: Boolean get() = me?.isStaff == true
  private val staffGroupId: Int? get() = me?.takeIf { it.isStaff }?.chapterId
  // Drafts belong to a writer's own letters; a group's letters for others are not drafted on this phone.
  /** The letter whose text this one starts from: one that came back, or a held one that has to be sealed again. */
  private val sendingAgainFrom: Int? = route.resendOf ?: route.replacesHeld
  private val usesDrafts: Boolean get() = route.editMessageId == null && route.writerId == null && route.replyForUserId == null && route.outboxId == null && sendingAgainFrom == null && !isStaff

  /**
   * One key per letter *as written*: pressing Send twice, or Send again after a timeout, repeats it, and the
   * server answers with the letter it already has. Changing the words makes it a different letter, so the
   * key is dropped and the next Send makes a new one (the server refuses a reused key on different text).
   * A letter reopened from the outbox starts with the key it was queued under.
   *
   * The note to the relay group and the choice of group are not part of what the server tells letters apart by (its
   * `beginIdempotent` for a letter: who from, who to, the text), so changing only those keeps the key: under a new
   * one, an earlier try that had arrived would be joined by a second letter. [relay] and [note] are what the key was
   * first tried with; if they have changed since and the server answers with the earlier letter, see [bringInLine].
   */
  private class SendKey(val key: String, val relay: Int?, val note: String?)
  private var sendKey: SendKey? = null
  /** The queued letter this screen was opened on (see [ComposeRoute.outboxId]), which is held back from sending meanwhile. */
  private var reopened: me.paxana.abcmailbox.data.repo.OutboxPayload? = null
  /** Whether the screen is on show. Not showing (another tab, a screen on top), a queued letter is not held back for it. */
  private var showing = true
  /** The queued letter was let go while the screen was not showing, and must be asked for again before anything is sent. */
  private var away = false
  // All three above `init` on purpose: Kotlin runs initialisers top to bottom, and load(), started in init, writes them.

  private fun writingAs(): String? = when {
    route.replyForUserId != null -> null
    route.writerName != null -> route.writerName
    isStaff && route.editMessageId == null -> strings.get(R.string.writer_anonymous)
    else -> null
  }

  private val _ui = MutableStateFlow(ComposeUiState(editing = route.editMessageId != null, recordingReply = route.replyForUserId != null, writingAs = writingAs()))
  val ui: StateFlow<ComposeUiState> = _ui.asStateFlow()

  init {
    viewModelScope.launch { load() }
    // Someone signed in after this screen was built (from its own prompt, or the stored session arrived late): what
    // depends on who that is (a draft of theirs, "writing as") is read again. Nothing typed is lost: there is no
    // editor to type in while signed out, and only an untouched editor is reloaded.
    // Compared with the account known at construction, not with "the first value seen": a StateFlow replays only its
    // latest, so a session read in between would otherwise be taken for the one the first load() already had.
    var known = userId
    viewModelScope.launch {
      sessions.state.map { (it as? SessionState.SignedIn)?.session?.user?.id }.distinctUntilChanged().collect { id ->
        if (id == known) return@collect
        known = id
        if (id != null && _ui.value.body.isBlank() && _ui.value.note.isBlank()) { _ui.update { it.copy(writingAs = writingAs(), loading = true) }; load() }
      }
    }
    // Autosave: whenever the text changes, wait for a pause, then persist.
    viewModelScope.launch {
      _ui.map { Triple(it.body, it.note, it.selectedRelay) }
        .distinctUntilChanged()
        .drop(1)
        .debounce(600)
        .collect { (body, note, relay) ->
          val uid = userId ?: return@collect
          if (!usesDrafts || _ui.value.loading || _ui.value.sent) return@collect
          if (body.isBlank() && note.isBlank()) drafts.delete(uid, route.prisonerId)
          else drafts.save(uid, route.prisonerId, Draft(body, note.ifBlank { null }, relay, System.currentTimeMillis()))
        }
    }
  }

  /** Counts loads, so that a load started later (someone signed in) is not overwritten by one started earlier finishing after it. */
  private var loads = 0

  private suspend fun load() {
    val thisLoad = ++loads
    val prisoner = (directory.prisoner(route.prisonerId) as? ApiResult.Success)?.value
    val facility = prisoner?.facilityId?.let { (directory.facility(it) as? ApiResult.Success)?.value } ?: prisoner?.facility
    val relay = resolveRelay(facility)
    var body = ""
    var note = ""
    var selected: Int? = (relay as? RelayChoice.Automatic)?.group?.id
    var restored = false
    var queuedCopyGone = false // sent, or deleted, between the tap on Edit and this screen opening: there is nothing to edit
    route.editMessageId?.let { id ->
      (letters.letter(id) as? ApiResult.Success)?.value?.let { l ->
        body = l.body; note = l.relayNote.orEmpty()
        // The group the letter had, unless it no longer serves where the person is (they were moved and the letter
        // is held for a choice): then nothing is chosen, and the picker below insists.
        val stillServes = (relay as? RelayChoice.Choose)?.options?.any { it.id == l.relayGroupId } ?: true
        selected = l.relayGroupId?.takeIf { stillServes } ?: selected
      }
    } ?: sendingAgainFrom?.let { id ->
      // The words only. Who mails it is decided afresh, from where the directory says the person is today.
      (letters.letter(id) as? ApiResult.Success)?.value?.takeIf { !it.locked }?.let { l -> body = l.body; note = l.relayNote.orEmpty() }
    } ?: route.outboxId?.let { id ->
      outbox.open(id)?.let { (queued, staged) ->
        body = queued.body; note = queued.relayNote.orEmpty(); selected = queued.relayChapter ?: selected
        _ui.update { it.copy(attachments = staged) }
        // Unchanged, it is still the same letter: an earlier attempt may have arrived unheard, and only the same
        // key lets the server say so. Edited, it is a different letter and gets a new key (as the iOS app does).
        sendKey = SendKey(queued.idempotencyKey, queued.relayChapter, queued.relayNote)
        reopened = queued
        if (!showing) letGo() // left while this was loading
      } ?: run { queuedCopyGone = true }
    } ?: userId?.takeIf { usesDrafts }?.let { uid ->
      drafts.load(uid, route.prisonerId)?.let { d ->
        body = d.body; note = d.note.orEmpty(); selected = d.relayChapter ?: selected; restored = true
      }
    }
    if (thisLoad != loads) return // a newer load has the say
    _ui.update {
      it.copy(
        prisoner = prisoner, facility = facility, relay = relay, selectedRelay = selected,
        body = body, note = note, showNote = note.isNotBlank(), loading = false, draftRestored = restored, sendingAgain = sendingAgainFrom != null,
        error = if (prisoner == null) strings.get(R.string.error_load_prisoner) else if (queuedCopyGone) strings.get(R.string.error_outbox_letter_gone) else null,
        queuedCopyGone = queuedCopyGone,
      )
    }
  }

  /**
   * The screen is no longer showing, and this ViewModel may outlive that by a long way: a bottom tab saves the screens
   * of the tab that was left, ViewModels and all. A queued letter is not held back for an editor nobody is looking at.
   */
  fun onHidden() { showing = false; letGo() }

  private fun letGo() {
    val id = route.outboxId?.takeIf { reopened != null && !_ui.value.queuedCopyGone } ?: return
    away = true
    outbox.release(id)
  }

  /** Showing again: the letter is held back again if it is still there. If it went meanwhile, nothing here may be sent. */
  fun onShown() {
    showing = true
    val id = route.outboxId?.takeIf { away } ?: return
    away = false
    _ui.update { it.copy(recheckingQueued = true) }
    viewModelScope.launch {
      val still = outbox.hold(id)
      if (!still) reopened = null // not this screen's to delete any more
      _ui.update { it.copy(recheckingQueued = false, queuedCopyGone = !still, error = if (still) it.error else strings.get(R.string.error_outbox_letter_gone)) }
    }
  }

  override fun onCleared() {
    // Left without sending: the queued letter goes back in line. Sent or queued again: it is gone, and this does nothing.
    route.outboxId?.let(outbox::release)
    // Files staged for a letter that never left this screen are plain copies nobody will read again.
    _ui.value.attachments.forEach(files::discard)
  }

  fun onBodyChange(v: String) { sendKey = null; _ui.update { it.copy(body = v, error = null) } }
  fun onNoteChange(v: String) = _ui.update { it.copy(note = v, error = null) } // the key stays: see [SendKey]
  fun onToggleNote() = _ui.update { it.copy(showNote = !it.showNote) }
  fun onSelectRelay(id: Int?) = _ui.update { it.copy(selectedRelay = id, error = null) }
  fun draftNoticeShown() = _ui.update { it.copy(draftRestored = false) }

  fun attach(uri: Uri) {
    viewModelScope.launch {
      val staged = runCatching { files.stage(uri) }.getOrElse {
        _ui.update { s -> s.copy(error = strings.get(R.string.error_read_file)) }; return@launch
      }
      when {
        staged.mimeType.startsWith("image/") && _ui.value.facility?.rules?.forbidsPhotos == true -> { files.discard(staged); _ui.update { it.copy(error = strings.get(R.string.error_no_images_here)) } }
        staged.mimeType !in ATTACHMENT_MIME_TYPES -> { files.discard(staged); _ui.update { it.copy(error = strings.get(R.string.error_file_type)) } }
        staged.size > MAX_ATTACHMENT_BYTES -> { files.discard(staged); _ui.update { it.copy(error = strings.get(R.string.error_file_too_big)) } }
        else -> _ui.update { it.copy(attachments = it.attachments + staged, error = null) }
      }
    }
  }

  /** Step one of taking a photo: a file for the camera app to fill, and the Uri to give it. */
  fun prepareCamera(): Uri {
    val (file, uri) = files.newCameraTarget()
    _ui.update { it.copy(cameraTarget = file) }
    return uri
  }

  fun onPhotoResult(taken: Boolean) {
    val file = _ui.value.cameraTarget ?: return
    _ui.update { it.copy(cameraTarget = null) }
    if (!taken || file.length() == 0L) { file.delete(); return }
    val shot = files.stageCameraShot(file)
    when {
      shot.size > MAX_ATTACHMENT_BYTES -> { files.discard(shot); _ui.update { it.copy(error = strings.get(R.string.error_photo_too_big)) } }
      else -> _ui.update { it.copy(attachments = it.attachments + shot, error = null) }
    }
  }

  fun removeAttachment(staged: StagedFile) {
    files.discard(staged)
    _ui.update { it.copy(attachments = it.attachments - staged) }
  }

  fun send() {
    val s = _ui.value
    if (!s.canSend) return
    val relayChapter = when (val r = s.relay) {
      is RelayChoice.Choose -> s.selectedRelay
      is RelayChoice.Automatic -> r.group.id
      else -> null
    }
    _ui.update { it.copy(sending = true, error = null, progress = strings.get(if (it.editing) R.string.progress_saving else R.string.progress_sending)) }
    viewModelScope.launch {
      if (route.editMessageId != null) {
        when (val r = letters.edit(LetterEdit(route.editMessageId, s.body, s.note.ifBlank { null }, relayChapter))) {
          is ApiResult.Failure -> _ui.update { it.copy(sending = false, progress = null, error = r.error.message(strings) ?: strings.get(R.string.error_save_letter)) }
          is ApiResult.Success -> uploadThen(route.editMessageId, s.attachments, chatIdOf(route.editMessageId))
        }
        return@launch
      }
      val note = s.note.ifBlank { null }
      val key = sendKey ?: SendKey(java.util.UUID.randomUUID().toString(), relayChapter, note).also { sendKey = it }
      val letter = NewLetter(
        prisonerId = route.prisonerId, body = s.body, relayNote = note, relayChapter = relayChapter,
        asWriterId = route.replyForUserId ?: route.writerId, fromPrisoner = route.replyForUserId != null,
        // End-to-end: the server lets a group hold an envelope where it relays for the facility (or manages the writer).
        groupRelaysFacility = staffGroupId != null && s.facility?.relayGroups?.any { it.id == staffGroupId } == true,
        idempotencyKey = key.key,
        // A queued "send again" opened for editing is still that: the links travel with the queued copy, not the route.
        resendOf = route.resendOf ?: reopened?.resendOf, replacesHeld = route.replacesHeld ?: reopened?.replacesHeld,
        reference = route.reference,
      )
      when (val r = letters.send(letter)) {
        is ApiResult.Failure ->
          // No answer is not a reason to lose the evening's letter: it goes to the outbox and is sent when the
          // phone is next online. "No answer" includes a timeout, where the letter may in fact have arrived:
          // the outbox retries under the same Idempotency-Key, so the server returns that letter rather than
          // making a second. If the server answered "no", the writer sees that now, while they can still fix it.
          if (r.error.gotNoAnswer()) {
            outbox.queue(s.prisoner?.name ?: strings.get(R.string.prisoner_numbered, route.prisonerId), s.writingAs.takeIf { route.writerId != null }, letter, s.attachments)
            finishedWith(queued = true)
          } else if (r.error is AppError.RateLimited) {
            // Paced, not refused (API PR #128): the letter was not saved, and the outbox sends it under the same key
            // once the wait is over, even if the app is closed by then. Nothing for the writer to do but know when.
            outbox.queue(s.prisoner?.name ?: strings.get(R.string.prisoner_numbered, route.prisonerId), s.writingAs.takeIf { route.writerId != null }, letter, s.attachments)
            outbox.waitOut(r.error.retryAfterSeconds)
            finishedWith(queued = true, limitedUntil = outbox.limitedUntil.value)
          } else if ((r.error as? AppError.Forbidden)?.isGroupBlock == true) groupBlocked(relayChapter)
          else _ui.update { it.copy(sending = false, progress = null, error = r.error.message(strings) ?: strings.get(R.string.error_send_letter)) }
        is ApiResult.Success -> {
          finishedWith(queued = false)
          bringInLine(r.value, key, letter)
          uploadThen(r.value.id, s.attachments, r.value.threadId)
        }
      }
    }
  }

  /**
   * The group that would mail this letter blocked the writer (API #171): only that group. Where the facility has another,
   * the choice is offered again without it; where it has none, nothing can carry this letter for now, and the screen
   * says so. The letter stays on the screen either way.
   */
  private fun groupBlocked(relayChapter: Int?) = _ui.update { st ->
    val relay = st.relay
    val name = when (relay) {
      is RelayChoice.Choose -> relay.options.firstOrNull { it.id == relayChapter }?.name
      is RelayChoice.Automatic -> relay.group.name
      else -> null
    } ?: strings.get(R.string.the_relay_group)
    val others = (relay as? RelayChoice.Choose)?.options?.filter { it.id != relayChapter }.orEmpty()
    if (others.isNotEmpty()) st.copy(sending = false, progress = null, relay = RelayChoice.Choose(others, required = true), selectedRelay = null, error = strings.get(R.string.error_group_block_choose, name))
    else st.copy(sending = false, progress = null, error = strings.get(R.string.error_group_block_only, name))
  }

  /**
   * The relay group or the note was changed after the key's first try, and the server answered with the letter that
   * first try made (it arrived unheard): the letter exists once, as it should, but says what it said then. The change
   * is made to it as an edit. Best effort: a letter already printed cannot be edited, and stays as it was sent.
   */
  private suspend fun bringInLine(sent: me.paxana.abcmailbox.domain.Letter, key: SendKey, asked: NewLetter) {
    if (key.relay == asked.relayChapter && key.note == asked.relayNote) return // nothing changed since the first try
    if (sent.relayGroupId == asked.relayChapter && sent.relayNote?.ifBlank { null } == asked.relayNote) return // this try made the letter
    letters.edit(LetterEdit(sent.id, asked.body, asked.relayNote, asked.relayChapter))
  }

  /** The letter has left this screen, to the server or to the outbox: the draft and any outbox copy it came from are done with. */
  private suspend fun finishedWith(queued: Boolean, limitedUntil: java.time.Instant? = null) {
    if (usesDrafts) userId?.let { drafts.delete(it, route.prisonerId) }
    route.outboxId?.takeIf { reopened != null }?.let { old -> outbox.forget(old) }
    if (queued) _ui.update { it.copy(sending = false, progress = null, attachments = emptyList(), queuedOffline = true, queuedLimitedUntil = limitedUntil) }
  }

  /** No connection, a connection that died, or a reply that is not our API's (a Wi-Fi login page). A 5xx is an answer: the writer sees it. */
  private fun AppError.gotNoAnswer(): Boolean =
    this is AppError.Network || (this is AppError.Unexpected && cause is kotlinx.serialization.SerializationException)

  private suspend fun chatIdOf(messageId: Int): Int? = (letters.letter(messageId) as? ApiResult.Success)?.value?.threadId

  /**
   * The letter exists; attach files one by one. A failed upload is reported but the letter stays sent, and so this
   * screen is over either way, as on iOS: left open with Send live, the next press was a second letter (or, with the
   * text unchanged, a try at files already deleted). The files that failed can be added by editing the letter.
   */
  private suspend fun uploadThen(messageId: Int, staged: List<StagedFile>, chatId: Int?) {
    val failed = mutableListOf<String>()
    staged.forEachIndexed { i, f ->
      _ui.update { it.copy(progress = strings.get(R.string.progress_uploading, f.name, i + 1, staged.size)) }
      if (letters.upload(messageId, f) is ApiResult.Failure) failed += f.name
      files.discard(f)
    }
    val thread = chatId ?: chatIdOf(messageId)
    _ui.update {
      it.copy(
        sending = false, progress = null, attachments = emptyList(), sent = true, sentChatId = thread,
        sentNotice = if (failed.isEmpty()) null else strings.get(R.string.error_files_not_uploaded, failed.joinToString()),
      )
    }
  }
}
