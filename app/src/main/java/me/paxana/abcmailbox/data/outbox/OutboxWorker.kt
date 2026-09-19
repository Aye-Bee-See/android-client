package me.paxana.abcmailbox.data.outbox

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import me.paxana.abcmailbox.MainActivity
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.repo.FlushOutcome
import me.paxana.abcmailbox.data.repo.OutboxRepository
import me.paxana.abcmailbox.data.repo.OutboxScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager is Android's answer to "do this when you can, even if my app is gone by then".
 * The request below says *when there is a network*; the system keeps it across app restarts and
 * reboots and wakes this worker when the condition holds. `@HiltWorker` lets Hilt supply the
 * repository (see AbcApplication for the factory that makes that work).
 */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val outbox: OutboxRepository,
  private val notifier: OutboxNotifier,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val outcome = outbox.flush()
    notifier.report(outcome)
    // `retry` hands the job back with a growing delay (30 s, 1 min, 2 min, … capped by the system at five hours).
    return if (outcome.stillWaiting > 0) Result.retry() else Result.success()
  }
}

@Singleton
class WorkManagerOutboxScheduler @Inject constructor(@ApplicationContext private val context: Context) : OutboxScheduler {
  override fun schedule() {
    val request = OneTimeWorkRequestBuilder<OutboxWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .build()
    // Append, not keep: a letter queued while a run is in progress was not in that run's list, and must get its own.
    WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }
  private companion object { const val UNIQUE_NAME = "outbox" }
}

/**
 * Tells the writer what became of letters sent while they were not looking. The words are
 * deliberately bare: a notification shows on a lock screen, and who someone writes to in prison
 * is nobody else's business. Names and reasons are inside the app.
 */
@Singleton
class OutboxNotifier @Inject constructor(@ApplicationContext private val context: Context) {

  fun report(outcome: FlushOutcome) {
    if (outcome.sent == 0 && outcome.refused == 0) return
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL, context.getString(R.string.outbox_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply { description = context.getString(R.string.outbox_channel_description) }
    )
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    val text = listOfNotNull(
      outcome.sent.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.outbox_notification_sent, it, it) },
      outcome.refused.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.outbox_notification_refused, it, it) },
    ).joinToString(" ")
    val notification = NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(R.drawable.ic_stat_letter)
      .setContentTitle(context.getString(R.string.app_name))
      .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(open).setAutoCancel(true)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .build()
    @Suppress("MissingPermission") // checked above
    manager.notify(if (outcome.refused > 0) ID_REFUSED else ID_SENT, notification)
  }

  private companion object { const val CHANNEL = "outbox"; const val ID_SENT = 1; const val ID_REFUSED = 2 }
}
