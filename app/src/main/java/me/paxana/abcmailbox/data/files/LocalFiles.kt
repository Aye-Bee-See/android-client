package me.paxana.abcmailbox.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A file the user picked, copied into our cache so we can upload it later. */
data class StagedFile(val file: File, val name: String, val mimeType: String, val size: Long)

/** The file operations the repositories and ViewModels need; an interface so JVM tests can fake them. */
interface LocalFilesContract {
  /** A fresh file for the camera app to write into, and the content Uri to hand it. */
  fun newCameraTarget(): Pair<File, Uri>
  /** Turns a finished camera shot into an attachment. */
  fun stageCameraShot(file: File): StagedFile
  suspend fun stage(uri: Uri): StagedFile
  fun discard(staged: StagedFile)
  fun downloadTarget(attachmentId: Int, name: String): File
  /** Where a queued letter's file waits, encrypted. Not the cache: Android may empty that at any time. */
  fun newOutboxFile(): File = error("not used")
  /** A scratch file to decrypt a queued attachment into, just before it is uploaded. */
  fun newStagingFile(name: String): File = error("not used")
  fun emptyOutboxFolder() {}
  /**
   * Everything letter-related in the cache: attachments opened for reading (these are in the clear: a photo
   * of somebody's reply), files picked for a letter not yet sent, camera shots. None of it is per account, and
   * all of it can be fetched or picked again, so after an account is deleted the whole lot goes.
   */
  fun emptyCaches() {}
}

/**
 * Files on this device: staging a picked document for upload and keeping
 * downloaded attachments. The document picker hands back a content `Uri`
 * whose read permission is temporary, so the bytes are copied immediately.
 */
@Singleton
class LocalFiles @Inject constructor(@ApplicationContext private val context: Context) : LocalFilesContract {

  private val staging get() = File(context.cacheDir, "staging").apply { mkdirs() }
  val downloads get() = File(context.cacheDir, "attachments").apply { mkdirs() }

  override suspend fun stage(uri: Uri): StagedFile = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = "attachment"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) c.getString(0)?.let { name = it }
    }
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val target = File(staging, "${System.nanoTime()}_$name")
    resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
    StagedFile(target, name, mime, target.length())
  }

  override fun newCameraTarget(): Pair<File, Uri> {
    val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "reply-${System.currentTimeMillis()}.jpg")
    return file to androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
  }

  override fun stageCameraShot(file: File) = StagedFile(file, file.name, "image/jpeg", file.length())

  override fun discard(staged: StagedFile) {
    staged.file.delete()
  }

  override fun emptyOutboxFolder() { File(context.filesDir, "outbox").listFiles()?.forEach { it.delete() } }
  override fun emptyCaches() { listOf("staging", "attachments", "camera").forEach { File(context.cacheDir, it).deleteRecursively() } }
  override fun newOutboxFile(): File = File(File(context.filesDir, "outbox").apply { mkdirs() }, "${java.util.UUID.randomUUID()}.bin")
  override fun newStagingFile(name: String): File = File(staging, "${System.nanoTime()}_${name.replace(File.separatorChar, '_')}")

  override fun downloadTarget(attachmentId: Int, name: String): File = File(downloads, "${attachmentId}_${name.replace(File.separatorChar, '_')}")
}
