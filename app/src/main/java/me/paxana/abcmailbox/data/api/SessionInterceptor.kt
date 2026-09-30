package me.paxana.abcmailbox.data.api

import me.paxana.abcmailbox.data.session.SessionCache
import okhttp3.Interceptor
import okhttp3.Response
import java.net.HttpURLConnection

/**
 * Two jobs on every request:
 *
 * 1. attach `Authorization: Bearer <token>` when a session exists;
 * 2. if a request that carried a token comes back 401, tell the session layer.
 *    The token was revoked, expired, or the account was banned. A 401 on a
 *    request with no token (a wrong password at login) is not a session event.
 *
 * This class must not depend on the session repository: the repository needs
 * Retrofit, Retrofit needs this interceptor, and a cycle would follow. The
 * small [SessionCache] is the shared piece both sides can hold.
 */
class SessionInterceptor(private val cache: SessionCache) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    // Sign-in, claim, and recovery are public. Sending a token there is pointless, and
    // worse, their 401 ("wrong password") would be mistaken for "your session was
    // revoked": checking the current password before a password change would sign
    // the user out on a typo.
    val path = chain.request().url.encodedPath
    // The offline directory download is anonymous on purpose; see [DirectorySyncApi].
    val anonymous = chain.request().tag(Anonymous::class.java) != null
    val token = if (anonymous || PUBLIC_AUTH_PATHS.any { path.endsWith(it) }) null else cache.token
    val request = if (token != null) {
      chain.request().newBuilder().header("Authorization", "Bearer $token").build()
    } else {
      chain.request()
    }
    val response = chain.proceed(request)
    if (token != null && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
      cache.reportUnauthorized(token)
    }
    // API #175: two-factor sign-in is required of this account and not set up. Every request but setting it up is
    // refused so, and it can start mid-session; the code is read from a copy of the body, which the caller still gets.
    if (token != null && response.code == HttpURLConnection.HTTP_FORBIDDEN && runCatching { response.peekBody(4096).string() }.getOrNull()?.contains(SETUP_REQUIRED) == true) {
      cache.reportTwoFactorSetupRequired(token)
    }
    return response
  }

  private companion object {
    // The second step of a two-factor sign-in is public too: the challenge is its credential, and its 401 is "too late".
    val PUBLIC_AUTH_PATHS = listOf("/auth/login", "/auth/login/two-factor", "/auth/claim", "/auth/recover")
    const val SETUP_REQUIRED = "two_factor_required.setup_required"
  }
}
