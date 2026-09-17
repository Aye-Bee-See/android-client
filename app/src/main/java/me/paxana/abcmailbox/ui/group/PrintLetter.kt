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
object PrintLetter {
  // The WebView must outlive this call, until the print adapter has read it.
  private var pending: WebView? = null

  fun print(context: Context, jobName: String, body: String) {
    val html = """
      <html><head><meta charset="utf-8"><style>
        body { font-family: Georgia, 'Times New Roman', serif; font-size: 12pt; line-height: 1.5; margin: 2.2cm; color: #000; }
        p { white-space: pre-wrap; margin: 0; }
      </style></head><body><p>${escape(body)}</p></body></html>
    """.trimIndent()
    val webView = WebView(context)
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

  internal fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
