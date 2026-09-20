package me.paxana.abcmailbox.data.push

import android.content.Context
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import me.paxana.abcmailbox.BuildConfig
import me.paxana.abcmailbox.data.activity.ActivityScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Cloud Messaging, started by hand and only on request.
 *
 * The usual set-up (a google-services.json and a Gradle plugin) starts Firebase at every launch and fails
 * the build when the file is missing. Here the project's four values are build settings (see
 * firebase.properties.example), Firebase's own start-up is switched off in the manifest, and nothing of it
 * runs until someone turns instant notifications on. Until a Firebase project exists the values are empty
 * and this reports [PushProvider.Availability.NOT_CONFIGURED].
 *
 * Exercised against a real Firebase project (`abc-mailbox`) on 19 Sep 2026, on an emulator with Google Play
 * services: start-up from the four values, a token, registration, delivery in one to three seconds with the
 * app in the background and with its process killed, and deletion of the token. See docs/PUSH.md.
 */
@Singleton
class FcmPushProvider @Inject constructor(@ApplicationContext private val context: Context) : PushProvider {

  private val configured = listOf(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_SENDER_ID).all { it.isNotBlank() }

  override val availability: PushProvider.Availability
    get() = when {
      !configured -> PushProvider.Availability.NOT_CONFIGURED
      GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS -> PushProvider.Availability.NO_SERVICE
      else -> PushProvider.Availability.READY
    }

  override val deviceLabel: String get() = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().take(80)

  private fun messaging(): FirebaseMessaging {
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(
        context,
        FirebaseOptions.Builder()
          .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApplicationId(BuildConfig.FIREBASE_APP_ID)
          .setApiKey(BuildConfig.FIREBASE_API_KEY).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build(),
      )
    }
    return FirebaseMessaging.getInstance()
  }

  override suspend fun token(): String? = if (availability == PushProvider.Availability.READY) messaging().token.await() else null

  override suspend fun forget() { if (availability == PushProvider.Availability.READY) messaging().deleteToken().await() }
}

/**
 * Receives the ring. The message is the same for every event and says nothing (`{"type":"sync"}`), so there
 * is nothing to read: whatever arrives, look at the feed. The feed is fetched over the app's own connection,
 * worded on the phone, and shown by [me.paxana.abcmailbox.data.activity.ActivityNotifier].
 */
@AndroidEntryPoint
class AbcMessagingService : FirebaseMessagingService() {
  @Inject lateinit var scheduler: ActivityScheduler
  @Inject lateinit var registrar: PushRegistrar

  override fun onMessageReceived(message: RemoteMessage) = scheduler.checkNow()
  override fun onNewToken(token: String) = registrar.onNewToken(token)
}
