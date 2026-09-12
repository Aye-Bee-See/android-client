package me.paxana.abcmailbox.data.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The token as OkHttp needs it (synchronously, from any thread) and a channel
 * for "that token was refused". Written by [SessionRepository], read by
 * [me.paxana.abcmailbox.data.api.SessionInterceptor].
 */
@Singleton
class SessionCache @Inject constructor() {

  @Volatile
  var token: String? = null

  // replay = 1 keeps the last refused token for a subscriber that arrives after
  // the event (for example during startup). Consumers compare it with the
  // current token, so an old report can never sign out a newer session.
  private val _unauthorized = MutableSharedFlow<String>(replay = 1)

  /** Emits the token that was refused, so a stale report about an old token can be ignored. */
  val unauthorized: SharedFlow<String> = _unauthorized.asSharedFlow()

  fun reportUnauthorized(refusedToken: String) {
    _unauthorized.tryEmit(refusedToken)
  }
}
