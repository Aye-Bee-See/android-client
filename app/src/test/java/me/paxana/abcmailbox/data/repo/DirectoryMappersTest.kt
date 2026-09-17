package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.paxana.abcmailbox.data.api.ApiEnvelope
import me.paxana.abcmailbox.data.api.ChapterDto
import me.paxana.abcmailbox.data.api.PrisonDto
import me.paxana.abcmailbox.data.api.PrisonerDto
import me.paxana.abcmailbox.domain.Routing
import me.paxana.abcmailbox.domain.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Parses fixtures captured from the running API (tools: curl into src/test/resources/directory). */
class DirectoryMappersTest {

  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private fun fixture(name: String) = javaClass.getResourceAsStream("/directory/$name")!!.reader().readText()

  @Test
  fun `prisoner full read maps with its embedded facility`() {
    val p = json.decodeFromString<ApiEnvelope<PrisonerDto>>(fixture("prisoner-full.json")).data!!.toDomain()
    assertEquals("Alex Johnson", p.name)
    assertEquals("Alice Johnson", p.birthName)
    assertEquals(listOf("art", "history"), p.interests)
    assertEquals("In transit, location unconfirmed", p.statusNotice)
    assertTrue(p.featured)
    assertEquals(LocalDate.of(2030, 5, 6), p.releaseDate)
    assertEquals("2030", p.releaseSummary)
    assertEquals("Alpha Prison", p.facility?.name)
    assertEquals(listOf("456 Alpha Street"), p.facility?.addressLines)
    assertEquals(Routing.DIRECT_AND_SCAN, p.facility?.routing)
    assertTrue(p.verification.isStale())
  }

  @Test
  fun `prison full read maps rules, relay groups and prisoners`() {
    val f = json.decodeFromString<ApiEnvelope<PrisonDto>>(fixture("prison-full.json")).data!!.toDomain()
    assertEquals("Alpha Prison", f.name)
    assertEquals(listOf("full_name_and_number", "ink_blue_or_black", "no_polaroids"), f.rules.rules.map { it.tag })
    assertEquals("Blue or black ink only", f.rules.rules[1].label)
    assertEquals(3, f.rules.photoLimit)
    assertEquals(listOf("English", "Spanish"), f.rules.languageNames)
    assertEquals(listOf("Test Chapter", "Relay Test Chapter"), f.relayGroups.map { it.name })
    assertEquals(1, f.prisoners.size)
    assertEquals("JPay, \$0.35 per page, account required", f.scanService)
  }

  @Test
  fun `chapter full read maps services and relay prisons`() {
    val g = json.decodeFromString<ApiEnvelope<ChapterDto>>(fixture("chapter-full.json")).data!!.toDomain()
    assertEquals("Test Chapter", g.name)
    assertEquals("Portland, OR, United States", g.location)
    assertEquals(listOf("letter_collection", "letter_writing_nights", "domestic_mailing"), g.services)
    assertEquals(2, g.relayPrisons.size)
    assertEquals("both", g.networkRole)
    assertTrue(g.socialLinks.isEmpty())
  }

  @Test
  fun `list page parses with paging fields`() {
    val env = json.decodeFromString<ApiEnvelope<List<PrisonerDto>>>(fixture("prisoners-page.json"))
    assertEquals(3, env.data?.size)
    assertEquals(2, env.page)
    assertEquals(3, env.pageSize)
    assertEquals(40, env.total)
  }

  @Test
  fun `list rows carry the facility summary added in API PR 79`() {
    val rows = json.decodeFromString<ApiEnvelope<List<PrisonerDto>>>(fixture("prisoners-page.json")).data!!.map { it.toDomain() }
    rows.forEach { p ->
      val f = p.facility
      assertTrue("row ${p.id} has no facility summary", f != null && f.name.isNotBlank())
      assertEquals(p.facilityId, f?.id)
      assertTrue(f!!.rules.isEmpty) // the summary is light: no rules, no relay groups
    }
  }

  @Test
  fun `address lines come out in postal order and skip blanks`() {
    val obj = buildJsonObject { put("zip", "97201"); put("street", "1 Main St"); put("city", "Portland"); put("note", ""); put("wing", "C") }
    assertEquals(listOf("1 Main St", "Portland", "97201", "C"), obj.toAddressLines())
    assertEquals(emptyList<String>(), null.toAddressLines())
  }

  @Test
  fun `verification staleness is six months`() {
    val now = Instant.parse("2026-09-12T00:00:00Z")
    assertTrue(Verification(null, null).isStale(now))
    assertTrue(Verification(1, now.minus(200, ChronoUnit.DAYS)).isStale(now))
    assertFalse(Verification(1, now.minus(100, ChronoUnit.DAYS)).isStale(now))
  }

  @Test
  fun `unknown routing and bad dates do not crash`() {
    val dto = PrisonDto(id = 1, prisonName = "X", routing = "carrier_pigeon", verifiedAt = "not a date")
    val f = dto.toDomain()
    assertEquals(Routing.UNKNOWN, f.routing)
    assertNull(f.verification.at)
  }
}
