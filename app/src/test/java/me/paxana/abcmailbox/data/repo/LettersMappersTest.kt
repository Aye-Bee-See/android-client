package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.decodeFromString
import me.paxana.abcmailbox.domain.HeldReason
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
    assertTrue(!t.prisoner?.name.isNullOrBlank())
    assertEquals(false, t.lastMessage?.fromPrisoner)
    assertEquals(LetterStatus.QUEUED, t.lastMessage?.status)
    assertEquals(1, t.letters.size)
    assertTrue(t.letters.first().canEdit)
  }

  @Test
  fun `chat rows carry the facility summary and embedded messages name their relay group (API PR 82)`() {
    val t = json.decodeFromString<ApiEnvelope<List<ChatDto>>>(fixture("chats-full.json")).data!!.first().toDomain()
    assertEquals("Test Prison", t.prisoner?.facility?.name)
    val relayed = t.letters.first { it.relayGroupId != null }
    assertEquals("Test Chapter", relayed.relayGroupName)
  }

  @Test
  fun `an inbox row says how many of its letters are held and whether the writer can lift the hold (API PR 117)`() {
    val row = json.decodeFromString<ChatDto>("""{"id":41,"user":2,"prisoner":1,"heldCount":2,"heldReasons":["choose_relay","prisoner_free"]}""").toDomain()
    assertEquals(2, row.heldCount); assertEquals(listOf(HeldReason.CHOOSE_RELAY, HeldReason.PRISONER_FREE), row.heldReasons); assertTrue(row.waitsForWriter)
    val group = json.decodeFromString<ChatDto>("""{"id":41,"user":2,"prisoner":1,"heldCount":1,"heldReasons":["prisoner_free"]}""").toDomain()
    assertFalse("freed: the group decides, not the writer", group.waitsForWriter)
    val older = json.decodeFromString<ChatDto>("""{"id":41,"user":2,"prisoner":1}""").toDomain()
    assertEquals(0, older.heldCount); assertFalse(older.waitsForWriter)
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

  @Test
  fun `a declined letter carries why, the facility's rule and the group's note, its history speaks the decline's vocabulary, and it can be sent again (API 170)`() {
    val raw = """{"id":41,"chat":41,"sender":"user","prisoner":3,"status":"declined","relayChapter":2,"keep":false,"user":1,
      "declineReason":"facility_rule","declineRule":"handwritten_only","declineNote":"This facility only takes handwritten letters.",
      "status_history":[{"id":3,"fromStatus":"queued","toStatus":"declined","changedBy":2,"createdAt":"2026-09-30T10:00:00.000Z","reason":"facility_rule","rule":"handwritten_only","note":"This facility only takes handwritten letters."}],
      "messageText":"probe"}"""
    val l = json.decodeFromString<MessageDto>(raw).toDomain()
    assertEquals(LetterStatus.DECLINED, l.status)
    assertEquals(me.paxana.abcmailbox.domain.DeclineReason.FACILITY_RULE, l.declineReason)
    assertEquals("handwritten_only", l.declineRule); assertEquals("This facility only takes handwritten letters.", l.declineNote)
    val row = l.history.single()
    assertEquals(me.paxana.abcmailbox.domain.DeclineReason.FACILITY_RULE, row.declineReason); assertEquals("handwritten_only", row.rule)
    assertNull("facility_rule is not a return reason, and must not read as an unknown one", row.reason)
    assertEquals(java.time.Instant.parse("2026-09-30T10:00:00Z"), l.declinedAt)
    assertTrue(l.canSendAgain); assertFalse(l.canDecline); assertNull(l.returnReason)

    // A reason a later API adds is still a decline, said without a why.
    assertEquals(me.paxana.abcmailbox.domain.DeclineReason.OTHER, json.decodeFromString<MessageDto>(raw.replace("\"facility_rule\"", "\"duplicate\"")).toDomain().declineReason)
  }
}
