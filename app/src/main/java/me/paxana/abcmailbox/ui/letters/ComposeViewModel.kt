package me.paxana.abcmailbox.ui.letters

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
  val sentChatId: Int? = null,
) {
  val characters: Int get() = body.length
  val pages: Int get() = estimatePages(characters)
  val relayIsBlocked: Boolean get() = relay is RelayChoice.Blocked
  val needsRelayChoice: Boolean get() = !recordingReply && (relay as? RelayChoice.Choose)?.required == true && selectedRelay == null
  private val mailRules: MailRules get() = facility?.rules ?: MailRules()
  /** What the facility's rules mean for this letter; recomputed as the writer types. */
  val advice: List<ComposeAdvice> get() = composeAdvice(mailRules, pages, attachments.count { it.mimeType.startsWith("image/") })
  /** Where pictures are refused only a PDF may be attached (API guidance for `no_photos`). */
  val allowedAttachmentTypes: Array<String> get() = if (mailRules.forbidsPhotos) arrayOf("application/pdf") else ATTACHMENT_MIME_TYPES
  // A recorded reply is not mailed anywhere, so the facility's routing cannot block it.
  val canSend: Boolean get() = !loading && !sending && (recordingReply || (!relayIsBlocked && !needsRelayChoice)) && (body.isNotBlank() || attachments.isNotEmpty())
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
  sessions: SessionRepository,
  private val route: ComposeRoute,
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
    savedStateHandle: SavedStateHandle,
  ) : this(letters, directory, drafts, files, sessions, savedStateHandle.toRoute<ComposeRoute>())
  private val userId: Int? = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.id

  private val isStaff: Boolean = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.isStaff == true
  // Drafts belong to a writer's own letters; a group's letters for others are not drafted on this phone.
  private val usesDrafts: Boolean = route.editMessageId == null && route.writerId == null && route.replyForUserId == null && !isStaff

  private val _ui = MutableStateFlow(
    ComposeUiState(
      editing = route.editMessageId != null,
      recordingReply = route.replyForUserId != null,
      writingAs = when {
        route.replyForUserId != null -> null
        route.writerName != null -> route.writerName
        isStaff && route.editMessageId == null -> "Anonymous writer"
        else -> null
      },
    )
  )
  val ui: StateFlow<ComposeUiState> = _ui.asStateFlow()

  init {
    viewModelScope.launch { load() }
    // Autosave: whenever the text changes, wait for a pause, then persist.
    viewModelScope.launch {
      _ui.map { Triple(it.body, it.note, it.selectedRelay) }
        .distinctUntilChanged()
        .drop(1)
        .debounce(600)
        .collect { (body, note, relay) ->
          val uid = userId ?: return@collect
          if (!usesDrafts || _ui.value.loading || _ui.value.sentChatId != null) return@collect
          if (body.isBlank() && note.isBlank()) drafts.delete(uid, route.prisonerId)
          else drafts.save(uid, route.prisonerId, Draft(body, note.ifBlank { null }, relay, System.currentTimeMillis()))
        }
    }
  }

  private suspend fun load() {
    val prisoner = (directory.prisoner(route.prisonerId) as? ApiResult.Success)?.value
    val facility = prisoner?.facilityId?.let { (directory.facility(it) as? ApiResult.Success)?.value } ?: prisoner?.facility
    val relay = resolveRelay(facility)
    var body = ""
    var note = ""
    var selected: Int? = (relay as? RelayChoice.Automatic)?.group?.id
    var restored = false
    route.editMessageId?.let { id ->
      (letters.letter(id) as? ApiResult.Success)?.value?.let { l ->
        body = l.body; note = l.relayNote.orEmpty(); selected = l.relayGroupId ?: selected
      }
    } ?: userId?.takeIf { usesDrafts }?.let { uid ->
      drafts.load(uid, route.prisonerId)?.let { d ->
        body = d.body; note = d.note.orEmpty(); selected = d.relayChapter ?: selected; restored = true
      }
    }
    _ui.update {
      it.copy(
        prisoner = prisoner, facility = facility, relay = relay, selectedRelay = selected,
        body = body, note = note, showNote = note.isNotBlank(), loading = false, draftRestored = restored,
        error = if (prisoner == null) "Could not load this prisoner." else null,
      )
    }
  }

  fun onBodyChange(v: String) = _ui.update { it.copy(body = v, error = null) }
  fun onNoteChange(v: String) = _ui.update { it.copy(note = v, error = null) }
  fun onToggleNote() = _ui.update { it.copy(showNote = !it.showNote) }
  fun onSelectRelay(id: Int?) = _ui.update { it.copy(selectedRelay = id, error = null) }
  fun draftNoticeShown() = _ui.update { it.copy(draftRestored = false) }

  fun attach(uri: Uri) {
    viewModelScope.launch {
      val staged = runCatching { files.stage(uri) }.getOrElse {
        _ui.update { s -> s.copy(error = "Could not read that file.") }; return@launch
      }
      when {
        staged.mimeType.startsWith("image/") && _ui.value.facility?.rules?.forbidsPhotos == true -> { files.discard(staged); _ui.update { it.copy(error = "This facility refuses pictures, so an image cannot be attached. A PDF can.") } }
        staged.mimeType !in ATTACHMENT_MIME_TYPES -> { files.discard(staged); _ui.update { it.copy(error = "Only PDF, JPEG, PNG, or WebP files can be attached.") } }
        staged.size > MAX_ATTACHMENT_BYTES -> { files.discard(staged); _ui.update { it.copy(error = "That file is over 20 MB.") } }
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
      shot.size > MAX_ATTACHMENT_BYTES -> { files.discard(shot); _ui.update { it.copy(error = "That photo is over 20 MB. Try a lower camera resolution.") } }
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
    _ui.update { it.copy(sending = true, error = null, progress = if (it.editing) "Saving…" else "Sending…") }
    viewModelScope.launch {
      if (route.editMessageId != null) {
        when (val r = letters.edit(LetterEdit(route.editMessageId, s.body, s.note.ifBlank { null }, relayChapter))) {
          is ApiResult.Failure -> _ui.update { it.copy(sending = false, progress = null, error = r.error.orGeneric("Could not save the letter.")) }
          is ApiResult.Success -> uploadThen(route.editMessageId, s.attachments, chatIdOf(route.editMessageId))
        }
        return@launch
      }
      val letter = NewLetter(
        prisonerId = route.prisonerId, body = s.body, relayNote = s.note.ifBlank { null }, relayChapter = relayChapter,
        asWriterId = route.replyForUserId ?: route.writerId, fromPrisoner = route.replyForUserId != null,
      )
      when (val r = letters.send(letter)) {
        is ApiResult.Failure -> _ui.update { it.copy(sending = false, progress = null, error = r.error.orGeneric("Could not send the letter.")) }
        is ApiResult.Success -> {
          if (usesDrafts) userId?.let { drafts.delete(it, route.prisonerId) }
          uploadThen(r.value.id, s.attachments, r.value.threadId)
        }
      }
    }
  }

  private suspend fun chatIdOf(messageId: Int): Int? = (letters.letter(messageId) as? ApiResult.Success)?.value?.threadId

  /** The letter exists; attach files one by one. A failed upload is reported but the letter stays sent. */
  private suspend fun uploadThen(messageId: Int, staged: List<StagedFile>, chatId: Int?) {
    val failed = mutableListOf<String>()
    staged.forEachIndexed { i, f ->
      _ui.update { it.copy(progress = "Uploading ${f.name} (${i + 1} of ${staged.size})…") }
      when (letters.upload(messageId, f)) {
        is ApiResult.Success -> files.discard(f)
        is ApiResult.Failure -> failed += f.name
      }
    }
    _ui.update {
      it.copy(
        sending = false, progress = null,
        error = if (failed.isEmpty()) null else "The letter was sent, but these files did not upload: ${failed.joinToString()}.",
        sentChatId = chatId ?: (letters.letter(messageId) as? ApiResult.Success)?.value?.threadId,
      )
    }
  }
}
