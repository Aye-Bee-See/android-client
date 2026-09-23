package me.paxana.abcmailbox.ui.common

import me.paxana.abcmailbox.domain.InviteCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZXing's encoder is plain Java, so the grid a slip is drawn from can be checked on the development machine. */
class QrCodesTest {
  @Test
  fun `a join link encodes to a square grid with a finder pattern in its corner, the same every time`() {
    val m = QrCodes.matrix(InviteCode.link("7Q4M2XKD9HBT"), quietZone = 0)
    assertTrue("a QR code is 21 to 177 modules a side, got ${m.size}", m.size in 21..177)
    assertTrue("square", m.all { it.size == m.size })
    assertTrue("without a quiet zone the top-left finder pattern starts at the very corner", (0..6).all { m[0][it] } && (0..6).all { m[it][0] })
    assertTrue(m.contentDeepEquals(QrCodes.matrix(InviteCode.link("7Q4M2XKD9HBT"), quietZone = 0)))

    // What the renderers draw: the same code inside four light modules on every side.
    val q = QrCodes.matrix(InviteCode.link("7Q4M2XKD9HBT"))
    assertEquals(m.size + 8, q.size)
    assertTrue("the quiet zone is light", q.first().none { it } && q.last().none { it } && q.all { !it.first() && !it.last() })
    assertTrue((0..6).all { q[4][4 + it] } && (0..6).all { q[4 + it][4] })
  }

  @Test
  fun `the grid drawn on a slip reads back as the join link`() {
    val link = InviteCode.link("7Q4M2XKD9HBT")
    val m = QrCodes.matrix(link)
    // Drawn as the screen and the slip draw it: the grid as it is, four pixels a module and nothing added, then read with ZXing's own reader.
    val scale = 4; val side = m.size * scale
    val pixels = IntArray(side * side) { i ->
      val x = i % side; val y = i / side
      if (m[y / scale][x / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(side, side, pixels)))
    assertEquals(link, com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text)
  }
}
