package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.domain.PasswordRules.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules are the app's now (API PR #114): a split server never sees the password, so it can apply none. */
class PasswordRulesTest {
  @Test
  fun `ten characters is the floor the network decided on, and under it nothing is anything but weak`() {
    assertEquals(10, PasswordRules.MIN_LENGTH)
    assertEquals(Strength.WEAK, PasswordRules.strength("Tr0ub4d&3")) // nine, however mixed
  }

  @Test
  fun `the meter tells the shortcuts people take from real variety`() {
    assertEquals(Strength.WEAK, PasswordRules.strength("aaaaaaaaaaaa"))
    assertEquals(Strength.WEAK, PasswordRules.strength("1234567890"))
    assertEquals(Strength.WEAK, PasswordRules.strength("abcdefghijkl"))
    val ordered = listOf("password12", "Password12", "P4ssw0rd!2x", "Tomatoes by August, I hope", "k9#Qm2!vLp8@zR4w")
    val scores = ordered.map { PasswordRules.strength(it).ordinal }
    assertEquals("each is at least as strong as the one before: $scores", scores.sorted(), scores)
    assertTrue(PasswordRules.strength("k9#Qm2!vLp8@zR4w") == Strength.STRONG)
  }

  @Test
  fun `a passphrase of several unrelated words is strong, which is the advice the help text gives`() {
    assertEquals(Strength.STRONG, PasswordRules.strength("garden tomato thursday envelope stamp"))
    assertEquals(Strength.GOOD, PasswordRules.strength("garden tomato thursday envelope"))
    assertTrue("non-Latin letters count for what they are", PasswordRules.strength("письма важны сегодня").ordinal >= Strength.GOOD.ordinal)
  }
}
