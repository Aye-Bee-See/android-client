package me.paxana.abcmailbox.ui.group

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Prints a letter with Android's print framework: the text is laid out as HTML
 * in an off-screen WebView, and the system print dialog takes over (Wi-Fi
 * printers, "Save as PDF", and so on). Only the letter itself is printed, since
 * that page is what goes in the envelope; the address and the note to the relay
 * group stay on the screen.
 *
 * `context` must be an Activity: the print service refuses anything else. In
 * Compose, `LocalContext.current` is the Activity.
 */
@android.annotation.SuppressLint("StaticFieldLeak") // `pending` is built from the application context and cleared once printing starts
object PrintLetter {
  // The WebView must outlive this call, until the print adapter has read it; then it is let go.
  private var pending: WebView? = null

  fun print(context: Context, jobName: String, body: String, footer: String? = null) {
    val html = html(body, footer)
    // Laid out off-screen, so it needs no Activity: the application context means the short-lived static
    // reference below can never hold a screen in memory. Only the print service needs the Activity.
    val webView = WebView(context.applicationContext)
    webView.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView, url: String?) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
        pending = null
      }
    }
    pending = webView
    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
  }

  /**
   * The page: the letter, and under a rule the footer (API PR #120): who to write back to, care of which group, and
   * the reference the prisoner is asked to copy. Text only; no QR code or barcode goes inside a prison.
   */
  internal fun html(body: String, footer: String?): String = """
      <html><head><meta charset="utf-8"><style>
        body { font-family: Georgia, 'Times New Roman', serif; font-size: 12pt; line-height: 1.5; margin: 2.2cm; color: #000; }
        p { white-space: pre-wrap; margin: 0; }
        .footer { margin-top: 1.6cm; padding-top: 0.4cm; border-top: 1px solid #000; font-size: 11pt; }
      </style></head><body><p>${escape(body)}</p>${footer?.let { """<p class="footer">${escape(it)}</p>""" } ?: ""}</body></html>
    """.trimIndent()

  internal fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
