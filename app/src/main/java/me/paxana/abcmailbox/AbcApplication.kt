package me.paxana.abcmailbox

import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import me.paxana.abcmailbox.data.activity.ActivityNotifier
import me.paxana.abcmailbox.data.dev.DevServerRepository
import javax.inject.Inject

/**
 * The process-wide entry point. Hilt generates the dependency graph rooted here;
 * `@HiltAndroidApp` is the only annotation the class needs.
 *
 * Backups are disabled in the manifest (`allowBackup=false`): the session token
 * and any cached key material must never leave the device through a cloud backup.
 */
@HiltAndroidApp
class AbcApplication : Application(), Configuration.Provider {
  /** Injected only so the stored server override is read before the first request goes out. */
  @Inject lateinit var devServer: DevServerRepository

  /**
   * WorkManager normally creates workers itself, with a constructor it knows. Ours needs the
   * outbox repository, so WorkManager is told to ask Hilt instead; for that, its automatic
   * start-up is switched off in the manifest and it reads this configuration on first use.
   */
  @Inject lateinit var workerFactory: HiltWorkerFactory
  @Inject lateinit var notifier: ActivityNotifier
  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

  override fun onCreate() {
    super.onCreate() // Hilt fills the @Inject fields in here, so they are only usable after this line
    notifier.prepare()
  }
}
