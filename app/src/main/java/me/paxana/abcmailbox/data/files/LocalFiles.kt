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
  suspend fun stage(uri: Uri): StagedFile
  fun discard(staged: StagedFile)
  fun downloadTarget(attachmentId: Int, name: String): File
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

  override fun discard(staged: StagedFile) {
    staged.file.delete()
  }

  override fun downloadTarget(attachmentId: Int, name: String): File = File(downloads, "${attachmentId}_${name.replace(File.separatorChar, '_')}")
}
