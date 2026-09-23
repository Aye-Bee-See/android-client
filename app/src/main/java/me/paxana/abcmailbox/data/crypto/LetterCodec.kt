package me.paxana.abcmailbox.data.crypto

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.crypto.Reader
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.EnvelopeDto
import me.paxana.abcmailbox.data.api.LastMessageDto
import me.paxana.abcmailbox.data.api.MessageDto
import me.paxana.abcmailbox.data.api.SendMessageRequest
import me.paxana.abcmailbox.data.api.UpdateMessageRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.repo.LetterEdit
import me.paxana.abcmailbox.data.repo.NewLetter
import me.paxana.abcmailbox.data.repo.toDomain
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.Letter
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns letters into what the API wants and back. In server mode that is a
 * pass-through of `messageText`; in end-to-end mode the body and note are
 * encrypted on the device under a fresh content key, the key is sealed to
 * each reader, and incoming letters are opened with the caller's envelope.
 * Repositories use this and never know which mode is active.
 */
@Singleton
class LetterCodec @Inject constructor(
  private val modes: EncryptionModeRepository,
  private val engine: CryptoEngine,
  private val vault: KeyVault,
  private val sessions: SessionRepository,
  private val authApi: AuthApi,
  private val json: Json,
  private val keyring: GroupKeyring,
  private val strings: Strings,
) {
  /** "Your letters are locked on this device": the same error wherever it arises, so callers can recognise it. */
  val locked: AppError get() = lockedError(strings)

  // Not called `me`: that would shadow the `me.paxana…` package root inside this class.
  private val viewer get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user
  private val myId: Int? get() = viewer?.id

  /**
   * Call before decoding anything. For a group member on an end-to-end server it
   * loads the group key and the custody keys once, so [incoming] and [preview]
   * can stay plain functions; for everyone else it returns at once.
   */
  suspend fun ready() { if (viewer?.isStaff == true) keyring.load() }

  /** After a 409 KeyVersionError: some group rotated its key, possibly ours, so open it again before re-sealing. */
  suspend fun refreshKeys() { if (viewer?.isStaff == true) keyring.load(force = true) }

  suspend fun isEndToEnd(): Boolean = modes.current() == EncryptionMode.E2E

  /** The request for a new letter; in end-to-end mode also the content key, for encrypting its attachments. */
  suspend fun outgoing(letter: NewLetter): ApiResult<Pair<SendMessageRequest, ByteArray?>> {
    val sender = if (letter.fromPrisoner) "prisoner" else "user"
    if (!isEndToEnd()) {
      return ApiResult.Success(
        SendMessageRequest(
          messageText = letter.body, prisoner = letter.prisonerId, sender = sender, user = letter.asWriterId,
          // A reply is not relayed anywhere, so it carries no relay group or note.
          relayChapter = letter.relayChapter.takeIf { !letter.fromPrisoner }, relayNote = letter.relayNote?.takeIf { it.isNotBlank() && !letter.fromPrisoner },
          resendOf = letter.resendOf, reference = letter.reference,
        ) to null
      )
    }
    val user = viewer ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    val readers = mutableListOf<Reader>()
    if (user.isStaff) {
      // A group member writes as the group: anonymously, for a writer it manages, or recording a reply.
      val group = when (val s = keyring.load()) {
        is GroupKeyState.Ready -> s.key
        else -> return ApiResult.Failure(s.asError())
      }
      letter.asWriterId?.let { writerId ->
        when (val r = publicKeyOf(user = writerId)) {
          is ApiResult.Failure -> return r
          // A reply may be recorded for a writer who has no key yet (API PR #95): it is sealed to the group alone,
          // and a member's phone adds the writer's envelope once they have a key (see GroupKeyring). A letter
          // written *for* someone is different: it is theirs, and needs their key.
          is ApiResult.Success -> when (val key = r.value.first) {
            null -> if (!letter.fromPrisoner) return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_writer_no_key))))
            else -> readers += Reader(Reader.USER, writerId, key)
          }
        }
      }
      // The group keeps its own envelope wherever the server permits one: as the manager of the writer
      // (which includes its anonymous writer), or as a relay group of the facility.
      val groupMayRead = letter.asWriterId == null || keyring.writerKey(letter.asWriterId) != null || letter.groupRelaysFacility || letter.relayChapter == group.groupId
      if (groupMayRead) readers += Reader(Reader.CHAPTER, group.groupId, group.publicKey, group.version)
      if (readers.isEmpty()) return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_writer_no_key))))
    } else {
      val keyPair = vault.keyPair(user.id) ?: return ApiResult.Failure(locked)
      readers += Reader(Reader.USER, user.id, Base64.getEncoder().encodeToString(keyPair.publicKey))
    }
    letter.relayChapter?.takeIf { groupId -> !letter.fromPrisoner && readers.none { it.type == Reader.CHAPTER && it.id == groupId } }?.let { groupId ->
      when (val r = publicKeyOf(chapter = groupId)) {
        is ApiResult.Failure -> return r
        is ApiResult.Success -> readers += Reader(Reader.CHAPTER, groupId, r.value.first ?: return ApiResult.Failure(
          AppError.Validation(listOf(strings.get(R.string.error_relay_no_key)))
        ), r.value.second)
      }
    }
    val enc = engine.encryptLetter(letter.body, letter.relayNote?.takeIf { it.isNotBlank() && !letter.fromPrisoner }, readers)
    val request = SendMessageRequest(
      prisoner = letter.prisonerId,
      sender = sender,
      user = letter.asWriterId,
      relayChapter = letter.relayChapter.takeIf { !letter.fromPrisoner },
      resendOf = letter.resendOf,
      reference = letter.reference,
      ciphertext = enc.body.ciphertext,
      nonce = enc.body.nonce,
      relayNoteCiphertext = enc.relayNote?.ciphertext,
      relayNoteNonce = enc.relayNote?.nonce,
      envelopes = enc.envelopes.map { EnvelopeDto(it.readerType, it.readerId, it.wrappedKey, it.keyVersion) },
    )
    return ApiResult.Success(request to enc.contentKey)
  }

  /** An edit re-encrypts under the letter's existing content key, so its envelopes stay valid. */
  suspend fun edit(edit: LetterEdit, existing: MessageDto): ApiResult<UpdateMessageRequest> {
    if (existing.ciphertext == null) {
      return ApiResult.Success(UpdateMessageRequest(id = edit.messageId, messageText = edit.body, relayNote = edit.relayNote, relayChapter = edit.relayChapter))
    }
    val key = contentKey(existing) ?: return ApiResult.Failure(locked)
    val body = engine.encryptText(edit.body, key)
    val note = edit.relayNote?.takeIf { it.isNotBlank() }?.let { engine.encryptText(it, key) }
    // The reader set is fixed after sending in end-to-end mode, so the relay group is not sent.
    return ApiResult.Success(UpdateMessageRequest(id = edit.messageId, ciphertext = body.ciphertext, nonce = body.nonce, relayNoteCiphertext = note?.ciphertext, relayNoteNonce = note?.nonce))
  }

  fun incoming(dto: MessageDto): Letter {
    val base = dto.toDomain()
    if (dto.ciphertext == null || dto.nonce == null) return base
    val key = contentKey(dto) ?: return base.copy(locked = true)
    return runCatching {
      base.copy(
        body = engine.decryptText(dto.ciphertext, dto.nonce, key),
        relayNote = if (dto.relayNoteCiphertext != null && dto.relayNoteNonce != null) engine.decryptText(dto.relayNoteCiphertext, dto.relayNoteNonce, key) else null,
      )
    }.getOrElse { base.copy(locked = true) }
  }

  fun preview(dto: LastMessageDto): String? {
    if (dto.ciphertext == null || dto.nonce == null) return dto.messageText?.takeIf { it.isNotBlank() }
    val key = openMine(dto.envelopes) ?: return null
    return runCatching { engine.decryptText(dto.ciphertext, dto.nonce, key) }.getOrNull()
  }

  /** The letter's content key, from the envelope sealed to this account. Null when locked or not a reader. */
  fun contentKey(dto: MessageDto): ByteArray? = openMine(dto.envelopes)

  /**
   * A writer has one way in: the envelope sealed to them. A group member has up to three:
   * their own, the group's, and those of writers whose keys the group holds in custody.
   */
  private fun openMine(envelopes: List<EnvelopeDto>?): ByteArray? {
    val user = viewer ?: return null
    if (envelopes.isNullOrEmpty()) return null
    val group = if (user.isStaff) keyring.groupKey() else null
    for (e in envelopes) {
      val keyPair = when {
        e.readerType == Reader.USER && e.readerId == user.id -> vault.keyPair(user.id)
        e.readerType == Reader.CHAPTER && e.readerId == group?.groupId -> group.keyPair
        e.readerType == Reader.USER && group != null -> keyring.writerKey(e.readerId)
        else -> null
      } ?: continue
      runCatching { engine.openEnvelope(e.wrappedKey, keyPair) }.getOrNull()?.let { return it }
    }
    return null
  }

  /** Base64 public key and, for groups, the key version. A rotation makes a cached copy wrong, so this always asks. */
  private suspend fun publicKeyOf(user: Int? = null, chapter: Int? = null): ApiResult<Pair<String?, Int?>> =
    when (val r = apiCall(json) { authApi.publicKey(user = user, chapter = chapter) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> ApiResult.Success(r.value.data?.publicKey to r.value.data?.keyVersion)
    }

  /**
   * Forwarding: one more envelope for a partner relay group, sealed from the content key this
   * reader already holds. The letter itself is never re-encrypted.
   */
  suspend fun envelopeFor(dto: MessageDto, groupId: Int): ApiResult<EnvelopeDto> {
    ready()
    val key = contentKey(dto) ?: return ApiResult.Failure(locked)
    return when (val r = publicKeyOf(chapter = groupId)) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> {
        val publicKey = r.value.first ?: return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_partner_no_key))))
        engine.sealContentKey(key, Reader(Reader.CHAPTER, groupId, publicKey, r.value.second)).let { ApiResult.Success(EnvelopeDto(it.readerType, it.readerId, it.wrappedKey, it.keyVersion)) }
      }
    }
  }

  fun encryptFile(bytes: ByteArray, contentKey: ByteArray): Pair<ByteArray, String> = engine.encryptFile(bytes, contentKey)
  fun decryptFile(ciphertext: ByteArray, nonce: String, contentKey: ByteArray): ByteArray = engine.decryptFile(ciphertext, nonce, contentKey)

  /** A sentence for each reason a group member cannot use the group key yet. */
  private fun GroupKeyState.asError(): AppError = when (this) {
    is GroupKeyState.Failed -> error
    GroupKeyState.Locked -> locked
    is GroupKeyState.NotSetUp -> AppError.Forbidden(strings.get(R.string.error_group_key_not_set_up))
    is GroupKeyState.GroupNotActive -> AppError.Forbidden(strings.get(R.string.group_not_active_text))
    is GroupKeyState.NotHeld -> AppError.Forbidden(strings.get(R.string.error_group_key_not_held))
    else -> AppError.Unexpected(IllegalStateException("The group key is not available."))
  }

}

/** Built the same way everywhere (it is a data class, so two of these are equal), in the user's language. */
fun lockedError(strings: Strings): AppError = AppError.Forbidden(strings.get(R.string.error_letters_locked))
