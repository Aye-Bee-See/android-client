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

/** Counts copies, so that the clearing of an earlier one never touches a later one under the same label. Main thread only. */
private var copies = 0

/**
 * Copies a secret (a recovery code, a claim token, an authenticator key). Marked sensitive, so Android 13+ keeps it out
 * of the clipboard preview and the keyboard's history, and cleared again after ten minutes if it is still ours (the
 * iOS app's copy is local to the device and expires the same way; Android has no such options, so this is the nearest).
 *
 * The ten minutes are kept by this process: if Android ends it first, the clip stays like any other until something
 * replaces it. Nothing better is on offer, since Android 10 an app not in the foreground may not read the clipboard,
 * so a job woken later could not tell our clip from whatever the person has copied since, and must not clear blind.
 */
fun Context.copySecret(label: String, text: String) {
  val clipboard = getSystemService(ClipboardManager::class.java) ?: return
  val clip = ClipData.newPlainText(label, text)
  if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
  clipboard.setPrimaryClip(clip)
  val mine = ++copies
  Handler(Looper.getMainLooper()).postDelayed({
    // Only this very copy: a later secret under the same label, or whatever the person copied since, is theirs to keep.
    if (mine == copies && clipboard.primaryClipDescription?.label == label) runCatching { if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }
  }, SECRET_CLIP_MILLIS)
}
