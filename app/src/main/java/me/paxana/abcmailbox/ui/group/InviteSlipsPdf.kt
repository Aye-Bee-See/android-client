package me.paxana.abcmailbox.ui.group

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.withTranslation
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.domain.InviteCode
import me.paxana.abcmailbox.domain.IssuedInvites
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.common.QrCodes
import me.paxana.abcmailbox.ui.common.longDate
import java.io.File
import java.io.FileOutputStream

/**
 * The slips for a batch of invite codes (API PR #116), as one PDF: eight to an A4 page, each with the group's
 * name, the code in its groups of four, the date it stops working, a line on what to do with it, and a QR code
 * that opens the join link. The same file is what the print dialog prints and what "Share as PDF" sends, so a
 * group that saved the PDF has exactly what it would have printed.
 *
 * Drawn with Android's own PDF writer rather than a WebView (which prints the letters), because the QR modules
 * are ours to draw and a PDF file is wanted for sharing, which the print framework alone does not give.
 */
object InviteSlipsPdf {
  // A4 in PostScript points, the unit PdfDocument uses.
  private const val PAGE_W = 595
  private const val PAGE_H = 842
  private const val MARGIN = 30f
  private const val GAP = 14f
  private const val COLUMNS = 2
  private const val ROWS = 4
  const val PER_PAGE = COLUMNS * ROWS

  /** Writes the PDF into the app's cache (under `invites/`, which the FileProvider serves) and answers the file. */
  fun write(context: Context, issued: IssuedInvites, strings: Strings): File {
    val dir = File(context.cacheDir, "invites").apply { mkdirs() }
    val file = File(dir, "invite-codes-${issued.batch}.pdf")
    val pdf = PdfDocument()
    try {
      val slipW = (PAGE_W - 2 * MARGIN - (COLUMNS - 1) * GAP) / COLUMNS
      val slipH = (PAGE_H - 2 * MARGIN - (ROWS - 1) * GAP) / ROWS
      issued.codes.chunked(PER_PAGE).forEachIndexed { pageIndex, codes ->
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex + 1).create())
        codes.forEachIndexed { i, code ->
          val left = MARGIN + (i % COLUMNS) * (slipW + GAP)
          val top = MARGIN + (i / COLUMNS) * (slipH + GAP)
          drawSlip(page.canvas, RectF(left, top, left + slipW, top + slipH), code, issued, strings)
        }
        pdf.finishPage(page)
      }
      FileOutputStream(file).use { pdf.writeTo(it) }
    } finally {
      pdf.close()
    }
    return file
  }

  private fun drawSlip(canvas: Canvas, box: RectF, code: String, issued: IssuedInvites, strings: Strings) {
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.rgb(150, 150, 150) }
    canvas.drawRoundRect(box, 6f, 6f, border)
    val pad = 11f
    val left = box.left + pad
    val width = (box.width() - 2 * pad).toInt()

    // Stacked: who is inviting, then the code across the slip's width, then the date and the how-to beside the QR.
    var y = box.top + pad
    y = drawText(canvas, strings.get(R.string.slip_inviting, issued.groupName), left, y, width, 9.5f, bold = true, maxLines = 2)
    y += 5f
    val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 17f; color = Color.BLACK }
    canvas.drawText(InviteCode.pretty(code), left, y + 15f, codePaint)
    y += 24f

    // The QR code, bottom right: the join link with the code, black on the page's white, the link in words under it.
    val qrSide = (box.bottom - pad - 9f - y).coerceAtMost(88f)
    val qrLeft = box.right - pad - qrSide
    val matrix = QrCodes.matrix(InviteCode.link(code))
    val cell = qrSide / matrix.size
    val dark = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    for (row in matrix.indices) for (col in matrix[row].indices) if (matrix[row][col]) {
      val x = qrLeft + col * cell; val yy = y + row * cell
      canvas.drawRect(x, yy, x + cell + 0.3f, yy + cell + 0.3f, dark)
    }
    val linkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 6.5f; color = Color.rgb(70, 70, 70); textAlign = Paint.Align.CENTER }
    canvas.drawText(InviteCode.LINK_HOST + InviteCode.LINK_PATH, qrLeft + qrSide / 2, y + qrSide + 8f, linkPaint)

    // Beside it: the date, and what to do with the slip, cut to the room there is.
    val textWidth = (qrLeft - 8f - left).toInt()
    issued.expiresAt?.let { y = drawText(canvas, strings.get(R.string.invite_batch_use_by, it.longDate()), left, y, textWidth, 9f, bold = false, maxLines = 2) }
    y += 4f
    val room = ((box.bottom - pad - y) / (7.5f * 1.15f)).toInt().coerceAtLeast(1)
    drawText(canvas, strings.get(R.string.slip_how), left, y, textWidth, 7.5f, bold = false, color = Color.rgb(70, 70, 70), maxLines = room)
  }

  /** Wrapped text from ([x], [top]); answers the y just below it. */
  private fun drawText(canvas: Canvas, text: String, x: Float, top: Float, width: Int, size: Float, bold: Boolean, color: Int = Color.BLACK, maxLines: Int = Int.MAX_VALUE): Float {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f)
      .setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
    canvas.withTranslation(x, top) { layout.draw(this) }
    return top + layout.height
  }

  /** The system print dialog (Wi-Fi printers, "Save as PDF") over the written file. `context` must be an Activity. */
  fun print(context: Context, file: File, jobName: String) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    manager.print(jobName, PdfFileAdapter(file, jobName), PrintAttributes.Builder().build())
  }

  /** Hands the PDF to any app that takes one (a printer's app, a file store, a messenger). */
  fun share(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "application/pdf"
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, title)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
  }

  /** A print adapter over a finished PDF: nothing to lay out, the pages are what they are. */
  private class PdfFileAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
      if (cancel.isCanceled) { callback.onLayoutCancelled(); return }
      callback.onLayoutFinished(PrintDocumentInfo.Builder("$name.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
      try {
        file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) } }
        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
      } catch (e: Exception) {
        callback.onWriteFailed(e.message)
      }
    }
  }
}
