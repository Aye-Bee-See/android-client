package me.paxana.abcmailbox.data.activity

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
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import me.paxana.abcmailbox.MainActivity
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.domain.Activity
import me.paxana.abcmailbox.text.Strings
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Looks at the feed. Run every few hours, and at once when the push doorbell rings. */
@HiltWorker
class ActivitySyncWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val activity: ActivityRepository,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result { activity.sync(); return Result.success() }
}

interface ActivityScheduler {
  /** Keeps the periodic check alive. Safe to call on every launch. */
  fun keepChecking()
  /** The doorbell rang (or something else suggests news): look now. */
  fun checkNow()
}

@Singleton
class WorkManagerActivityScheduler @Inject constructor(@ApplicationContext private val context: Context) : ActivityScheduler {
  private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

  override fun keepChecking() {
    // Replies take weeks and a status change is not urgent, so a few times a day is plenty without push,
    // and costs almost nothing. With push turned on this is only the safety net for a missed ring.
    val request = PeriodicWorkRequestBuilder<ActivitySyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("activity-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
  }

  override fun checkNow() {
    // Expedited: a high-priority push gives the app a short window to run; this asks to use it.
    val request = OneTimeWorkRequestBuilder<ActivitySyncWorker>().setConstraints(online).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
    WorkManager.getInstance(context).enqueueUniqueWork("activity-now", ExistingWorkPolicy.REPLACE, request)
  }
}

@Singleton
class AndroidActivityNotifier @Inject constructor(@ApplicationContext private val context: Context, private val strings: Strings) : ActivityNotifier {

  /**
   * Makes the channels. Called when the app starts, not only when the first notification is due, so that the
   * three of them are already listed in Android's settings for anyone who goes looking. Creating a channel
   * that exists changes nothing except its name and description, which is what a change of language needs.
   */
  override fun prepare() {
    val manager = NotificationManagerCompat.from(context)
    // One channel per kind of news, so that Android's own settings let a person keep the banner for replies and
    // silence the rest. A reply is what people are waiting for, so it is the only one that interrupts.
    // (Importance is fixed when a channel is first created; only its owner can change it afterwards. The first
    // version had a single channel at default importance: a status-bar icon and no banner, which on a quiet
    // phone is easy to miss entirely. It is deleted here.)
    manager.deleteNotificationChannel("activity")
    listOf(
      Triple(REPLIES, R.string.channel_replies to R.string.channel_replies_description, NotificationManager.IMPORTANCE_HIGH),
      Triple(PROGRESS, R.string.channel_progress to R.string.channel_progress_description, NotificationManager.IMPORTANCE_DEFAULT),
      Triple(QUEUE, R.string.channel_queue to R.string.channel_queue_description, NotificationManager.IMPORTANCE_DEFAULT),
    ).forEach { (id, words, importance) ->
      manager.createNotificationChannel(NotificationChannel(id, strings.get(words.first), importance).apply { description = strings.get(words.second) })
    }
  }

  override fun show(fresh: List<Activity>) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    prepare()
    // A batch goes out on the channel of its most important entry.
    val channel = when {
      fresh.any { it.kind == Activity.Kind.REPLY } -> REPLIES
      fresh.any { it.kind == Activity.Kind.QUEUED_FOR_GROUP } -> QUEUE
      else -> PROGRESS
    }
    val lines = fresh.map { it.sentence(strings) }.distinct()
    val title = if (fresh.size == 1) strings.get(R.string.app_name) else strings.plural(R.plurals.activity_summary, fresh.size)
    val text = lines.first() + if (lines.size > 1) " " + strings.plural(R.plurals.activity_and_more, lines.size - 1) else ""
    val style = NotificationCompat.InboxStyle().also { s -> lines.take(5).forEach(s::addLine) }

    // Everything about one conversation: open it. Otherwise the Inbox, where all of it is.
    val chat = fresh.mapNotNull { it.chatId }.distinct().singleOrNull().takeIf { fresh.all { a -> a.kind != Activity.Kind.QUEUED_FOR_GROUP } }
    val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .setAction(Intent.ACTION_VIEW).setData((if (chat != null) "$THREAD_LINK/$chat" else INBOX_LINK).toUri())
    val notification = NotificationCompat.Builder(context, channel)
      .setSmallIcon(R.drawable.ic_stat_letter)
      .setContentTitle(title).setContentText(text).setStyle(if (lines.size > 1) style else NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
      .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setCategory(if (channel == REPLIES) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
      .build()
    @Suppress("MissingPermission") // checked above
    manager.notify(ID, notification)
  }

  override fun clear() = NotificationManagerCompat.from(context).cancel(ID)

  companion object {
    private const val REPLIES = "activity-replies"
    private const val PROGRESS = "activity-progress"
    private const val QUEUE = "activity-queue"
    private const val ID = 10
    /** Handled inside the app only (an explicit intent to MainActivity); no other app can open these. */
    const val THREAD_LINK = "abcmailbox://open/thread"
    const val INBOX_LINK = "abcmailbox://open/inbox"
  }
}
