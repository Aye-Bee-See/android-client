package me.paxana.abcmailbox.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR codes for invite-code slips (API PR #116), made with ZXing's encoder: the `core` artifact only, plain Java,
 * no camera. The encoder answers a grid of modules; drawing it is ours, on a Compose canvas here and on a PDF
 * page in [me.paxana.abcmailbox.ui.group.InviteSlipsPdf], so both show the very same code.
 */
object QrCodes {
  /** The quiet zone a reader needs around a QR code, in modules: the specification's four. */
  const val QUIET_ZONE = 4

  /**
   * The modules of a QR code for [text], row by row, `true` for dark, with [quietZone] light modules around it.
   * Error correction M. The quiet zone is part of the grid so that every renderer has it whatever its size: a
   * fixed margin in dp or points is not four modules once the code is small.
   */
  fun matrix(text: String, quietZone: Int = QUIET_ZONE): Array<BooleanArray> {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val side = m.width + 2 * quietZone
    return Array(side) { y -> BooleanArray(side) { x -> val mx = x - quietZone; val my = y - quietZone; mx in 0 until m.width && my in 0 until m.height && m.get(mx, my) } }
  }
}

/** A QR code, always black on white whatever the theme, so that any camera reads it. Square; size it by [modifier]. The quiet zone is in the grid. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
  val matrix = remember(text) { QrCodes.matrix(text) }
  Canvas(
    modifier.aspectRatio(1f).background(Color.White)
      .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
  ) {
    val n = matrix.size
    if (n == 0) return@Canvas
    val cell = size.minDimension / n
    // A hair over one cell, so that anti-aliasing leaves no white seams between dark modules.
    val square = Size(cell + 0.5f, cell + 0.5f)
    for (y in 0 until n) for (x in 0 until n) if (matrix[y][x]) drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = square)
  }
}
