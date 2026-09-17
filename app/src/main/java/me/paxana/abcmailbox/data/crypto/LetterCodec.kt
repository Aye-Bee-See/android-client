package me.paxana.abcmailbox.data.crypto

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
) {
  private val myId: Int? get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.id

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
        ) to null
      )
    }
    if (letter.asWriterId != null || letter.fromPrisoner) {
      return ApiResult.Failure(AppError.Validation(listOf("On an end-to-end server, letters written by a group need the group's key. That arrives in the next build.")))
    }
    val me = myId ?: return ApiResult.Failure(AppError.Unauthorized("You are signed out."))
    val keyPair = vault.keyPair(me) ?: return ApiResult.Failure(LOCKED)
    val readers = mutableListOf(Reader(Reader.USER, me, Base64.getEncoder().encodeToString(keyPair.publicKey)))
    letter.relayChapter?.let { groupId ->
      when (val r = apiCall(json) { authApi.publicKey(chapter = groupId) }) {
        is ApiResult.Failure -> return r
        is ApiResult.Success -> {
          val pk = r.value.data?.publicKey ?: return ApiResult.Failure(
            AppError.Validation(listOf("That relay group has not set up encryption yet, so it cannot receive letters. Choose another group or ask them to finish setting up."))
          )
          readers += Reader(Reader.CHAPTER, groupId, pk, r.value.data?.keyVersion)
        }
      }
    }
    val enc = engine.encryptLetter(letter.body, letter.relayNote, readers)
    val request = SendMessageRequest(
      prisoner = letter.prisonerId,
      relayChapter = letter.relayChapter,
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
    val key = contentKey(existing) ?: return ApiResult.Failure(LOCKED)
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

  private fun openMine(envelopes: List<EnvelopeDto>?): ByteArray? {
    val me = myId ?: return null
    val keyPair = vault.keyPair(me) ?: return null
    val mine = envelopes?.firstOrNull { it.readerType == Reader.USER && it.readerId == me } ?: return null
    return runCatching { engine.openEnvelope(mine.wrappedKey, keyPair) }.getOrNull()
  }

  fun encryptFile(bytes: ByteArray, contentKey: ByteArray): Pair<ByteArray, String> = engine.encryptFile(bytes, contentKey)
  fun decryptFile(ciphertext: ByteArray, nonce: String, contentKey: ByteArray): ByteArray = engine.decryptFile(ciphertext, nonce, contentKey)

  companion object {
    val LOCKED = AppError.Forbidden("Your letters are locked on this device. Unlock them with your password first.")
  }
}
