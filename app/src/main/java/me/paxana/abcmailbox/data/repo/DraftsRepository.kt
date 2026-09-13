package me.paxana.abcmailbox.data.repo

import me.paxana.abcmailbox.data.db.DraftDao
import me.paxana.abcmailbox.data.db.DraftEntity
import me.paxana.abcmailbox.data.session.SecretCipher
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class Draft(val body: String, val note: String?, val relayChapter: Int?, val updatedAt: Long)

interface DraftsRepository {
  suspend fun load(userId: Int, prisonerId: Int): Draft?
  suspend fun save(userId: Int, prisonerId: Int, draft: Draft)
  suspend fun delete(userId: Int, prisonerId: Int)
}

/** Drafts are plaintext letters on disk, so they get the same Keystore-backed encryption as the session. */
@Singleton
class DefaultDraftsRepository @Inject constructor(
  private val dao: DraftDao,
  private val cipher: SecretCipher,
) : DraftsRepository {

  private fun seal(text: String): String = Base64.getEncoder().encodeToString(cipher.encrypt(text.toByteArray(Charsets.UTF_8)))
  private fun open(blob: String): String = String(cipher.decrypt(Base64.getDecoder().decode(blob)), Charsets.UTF_8)

  override suspend fun load(userId: Int, prisonerId: Int): Draft? {
    val row = dao.get(userId, prisonerId) ?: return null
    return runCatching {
      Draft(open(row.body), row.note?.let { open(it) }, row.relayChapter, row.updatedAt)
    }.getOrNull() // an undecryptable draft (device restored, key gone) is simply lost
  }

  override suspend fun save(userId: Int, prisonerId: Int, draft: Draft) {
    dao.upsert(DraftEntity(userId, prisonerId, seal(draft.body), draft.note?.let { seal(it) }, draft.relayChapter, draft.updatedAt))
  }

  override suspend fun delete(userId: Int, prisonerId: Int) = dao.delete(userId, prisonerId)
}
