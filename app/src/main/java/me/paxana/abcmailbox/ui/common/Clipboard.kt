package me.paxana.abcmailbox.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/** Ten minutes, as the iOS app gives its pasteboard copy. */
private const val SECRET_CLIP_MILLIS = 10 * 60_000L

/**
 * Copies a secret (a recovery code, a claim token, an authenticator key). Marked sensitive, so Android 13+ keeps it out
 * of the clipboard preview and the keyboard's history, and cleared again after ten minutes if it is still there (the
 * iOS app's copy is local to the device and expires the same way; Android has no such options, so this is the nearest).
 */
fun Context.copySecret(label: String, text: String) {
  val clipboard = getSystemService(ClipboardManager::class.java) ?: return
  val clip = ClipData.newPlainText(label, text)
  if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
  clipboard.setPrimaryClip(clip)
  Handler(Looper.getMainLooper()).postDelayed({
    // Only our own clip: whatever the person copied since is theirs to keep.
    if (clipboard.primaryClipDescription?.label == label) runCatching { if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }
  }, SECRET_CLIP_MILLIS)
}
