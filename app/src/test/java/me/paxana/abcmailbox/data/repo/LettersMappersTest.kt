package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiEnvelope
import me.paxana.abcmailbox.data.api.ChatDto
import me.paxana.abcmailbox.data.api.MessageDto
import me.paxana.abcmailbox.domain.LetterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LettersMappersTest {

  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private fun fixture(name: String) = javaClass.getResourceAsStream("/letters/$name")!!.reader().readText()

  @Test
  fun `inbox rows with full=true carry the prisoner and the last message`() {
    val rows = json.decodeFromString<ApiEnvelope<List<ChatDto>>>(fixture("chats-full.json")).data!!.map { it.toDomain() }
    val t = rows.first()
    assertEquals("Jane Smith", t.prisoner?.name)
    assertEquals(false, t.lastMessage?.fromPrisoner)
    assertEquals(LetterStatus.QUEUED, t.lastMessage?.status)
    assertEquals(1, t.letters.size)
    assertTrue(t.letters.first().canEdit)
  }

  @Test
  fun `a printed letter with history and relay group maps and is not editable`() {
    val l = json.decodeFromString<ApiEnvelope<MessageDto>>(fixture("message-full.json")).data!!.toDomain()
    assertEquals(LetterStatus.PRINTED, l.status)
    assertFalse(l.canEdit)
    assertEquals("Relay Test Chapter", l.relayGroupName)
    assertEquals(2, l.relayGroupId)
    assertEquals(listOf(null, LetterStatus.QUEUED), l.history.map { it.from })
    assertEquals(listOf(LetterStatus.QUEUED, LetterStatus.PRINTED), l.history.map { it.to })
    assertEquals("probe", l.body)
    assertNull(l.relayNote)
  }

  @Test
  fun `an e2e message with no messageText maps to an empty body for now`() {
    val dto = MessageDto(id = 9, sender = "user", prisoner = 1, status = "queued", ciphertext = "…", nonce = "…")
    assertEquals("", dto.toDomain().body)
  }
}
