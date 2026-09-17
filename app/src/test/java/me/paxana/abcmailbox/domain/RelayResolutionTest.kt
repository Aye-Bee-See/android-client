package me.paxana.abcmailbox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client-side mirror of the API's relay rules (README, "Relay group"). */
class RelayResolutionTest {

  private fun group(id: Int, status: String? = "active") = Group(id, "Group $id", null, null, null, null, null, emptyMap(), emptyList(), null, "relay", status, emptyList(), emptyList(), null)
  private val rules = MailRules()
  private fun facility(routing: Routing, groups: List<Group>) = Facility(1, "F", emptyList(), null, routing, null, null, Verification(null, null), emptyList(), rules, groups)

  @Test
  fun `one relay group is automatic`() {
    val r = resolveRelay(facility(Routing.DIRECT, listOf(group(7))))
    assertEquals(RelayChoice.Automatic(group(7)), r)
  }

  @Test
  fun `no relay group and direct mail means direct`() {
    assertEquals(RelayChoice.Direct, resolveRelay(facility(Routing.DIRECT, emptyList())))
    assertEquals(RelayChoice.Direct, resolveRelay(null))
  }

  @Test
  fun `no relay group on a relay-only facility is blocked`() {
    assertTrue(resolveRelay(facility(Routing.RELAY_ONLY, emptyList())) is RelayChoice.Blocked)
  }

  @Test
  fun `several groups need a choice, required only for relay-only`() {
    val optional = resolveRelay(facility(Routing.DIRECT_AND_SCAN, listOf(group(1), group(2)))) as RelayChoice.Choose
    assertFalse(optional.required)
    assertEquals(listOf(1, 2), optional.options.map { it.id })
    val required = resolveRelay(facility(Routing.RELAY_ONLY, listOf(group(1), group(2)))) as RelayChoice.Choose
    assertTrue(required.required)
  }

  @Test
  fun `suspended groups are not offered`() {
    val r = resolveRelay(facility(Routing.DIRECT, listOf(group(1, "suspended"), group(2))))
    assertEquals(RelayChoice.Automatic(group(2)), r)
  }

  @Test
  fun `page estimate`() {
    assertEquals(1, estimatePages(0))
    assertEquals(1, estimatePages(3000))
    assertEquals(2, estimatePages(3001))
    assertEquals(4, estimatePages(10_000))
  }
}
