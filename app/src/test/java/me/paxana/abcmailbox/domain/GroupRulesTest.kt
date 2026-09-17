package me.paxana.abcmailbox.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRulesTest {
  private val independent = ThreadWriter(2, "user1", managedByGroupId = null, anonymousForGroupId = null)
  private val managedByOne = ThreadWriter(43, "Rosa L.", managedByGroupId = 1, anonymousForGroupId = null)
  private val anonymousOfOne = ThreadWriter(46, "Anonymous writer", managedByGroupId = 1, anonymousForGroupId = 1)

  @Test
  fun `a writer may always change their own queued letters`() {
    assertTrue(mayChangeLetters(viewerIsStaff = false, viewerGroupId = null, writer = independent))
    assertTrue(mayChangeLetters(viewerIsStaff = false, viewerGroupId = null, writer = null))
  }

  @Test
  fun `a group changes letters only for writers it writes for, not ones it relays or was shared`() {
    assertTrue(mayChangeLetters(true, 1, managedByOne))
    assertTrue(mayChangeLetters(true, 1, anonymousOfOne))
    assertFalse("the relay group reads and prints an independent writer's letter; it does not edit it", mayChangeLetters(true, 1, independent))
    assertFalse("a partner group shown another group's writer", mayChangeLetters(true, 3, managedByOne))
    assertFalse(mayChangeLetters(true, null, managedByOne))
    assertFalse(mayChangeLetters(true, 1, null))
  }
}
