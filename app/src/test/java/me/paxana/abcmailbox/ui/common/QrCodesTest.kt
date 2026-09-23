package me.paxana.abcmailbox.ui.common

import me.paxana.abcmailbox.domain.InviteCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZXing's encoder is plain Java, so the grid a slip is drawn from can be checked on the development machine. */
class QrCodesTest {
  @Test
  fun `a join link encodes to a square grid with a finder pattern in its corner, the same every time`() {
    val m = QrCodes.matrix(InviteCode.link("7Q4M2XKD9HBT"))
    assertTrue("a QR code is 21 to 177 modules a side, got ${m.size}", m.size in 21..177)
    assertTrue("square", m.all { it.size == m.size })
    assertTrue("no quiet zone: the top-left finder pattern starts at the very corner", (0..6).all { m[0][it] } && (0..6).all { m[it][0] })
    assertTrue(m.contentDeepEquals(QrCodes.matrix(InviteCode.link("7Q4M2XKD9HBT"))))
  }
}
