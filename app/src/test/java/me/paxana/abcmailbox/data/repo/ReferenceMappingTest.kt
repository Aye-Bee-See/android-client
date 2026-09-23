package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.MessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** API PR #120 on a message row: the reference, the letter a reply answers, and the footer with a full read. */
class ReferenceMappingTest {
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

  @Test
  fun `the reference and the footer come off an outgoing letter, and the letter answered off a reply`() {
    val sent = json.decodeFromString<MessageDto>("""{"id":41,"chat":9,"sender":"user","prisoner":3,"user":4,"status":"mailed","messageText":"Hi","replyReference":"4827-1935-6","repliesTo":null,"footer":{"name":"James Hollow","anonymous":false,"careOf":{"id":1,"name":"PDX ABC"},"reference":"4827-1935-6","replySheetAllowed":false}}""").toDomain()
    assertEquals("4827-1935-6", sent.replyReference); assertNull(sent.repliesToId)
    assertEquals("PDX ABC", sent.footer?.careOfName); assertEquals("4827-1935-6", sent.footer?.reference)

    val reply = json.decodeFromString<MessageDto>("""{"id":42,"chat":9,"sender":"prisoner","prisoner":3,"user":4,"messageText":"Hello back","replyReference":null,"repliesTo":41}""").toDomain()
    assertEquals(41, reply.repliesToId); assertNull(reply.replyReference); assertNull(reply.footer)

    val bare = json.decodeFromString<MessageDto>("""{"id":44,"chat":9,"sender":"user","prisoner":3,"user":4,"messageText":"Hi","replyReference":"547635946","footer":{"name":"Sam Hollow","anonymous":false,"careOf":{"id":1,"name":"Test Chapter"},"reference":"547635946","replySheetAllowed":false}}""").toDomain()
    assertEquals("the digits come bare from the API and are shown as printed", "5476-3594-6", bare.replyReference); assertEquals("5476-3594-6", bare.footer?.reference)

    val older = json.decodeFromString<MessageDto>("""{"id":43,"chat":9,"sender":"user","prisoner":3,"user":4,"messageText":"Hi"}""").toDomain()
    assertNull("an older API: nothing to show", older.replyReference); assertNull(older.footer)
  }
}
