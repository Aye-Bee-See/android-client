package me.paxana.abcmailbox.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
  /** The modules of a QR code for [text], row by row, `true` for dark. Error correction M; no quiet zone, the caller adds its own margin. */
  fun matrix(text: String): Array<BooleanArray> {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
  }
}

/** A QR code, always black on white whatever the theme, so that any camera reads it. Square; size it by [modifier]. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
  val matrix = remember(text) { QrCodes.matrix(text) }
  Canvas(
    modifier.aspectRatio(1f).background(Color.White).padding(8.dp)
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
