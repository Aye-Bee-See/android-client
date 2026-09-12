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
    val token = cache.token
    val request = if (token != null) {
      chain.request().newBuilder().header("Authorization", "Bearer $token").build()
    } else {
      chain.request()
    }
    val response = chain.proceed(request)
    if (token != null && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
      cache.reportUnauthorized(token)
    }
    return response
  }
}
