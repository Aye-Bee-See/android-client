package me.paxana.abcmailbox

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

/**
 * The next request the fake server received, or a failure after five seconds. `takeRequest()` with
 * no timeout waits for ever, so a change that stops a request being made (19 Sep 2026: a Retrofit
 * method that lost its @POST, which only fails when the call is built) hung the whole suite, and
 * would have hung CI for its full half hour, instead of failing one test with a reason.
 *
 * Skips the sign-in handshake (`GET /auth/login-params`, API PR #114): it precedes every sign-in and
 * is answered by [SchemeDispatcher], and no test's request sequence is about it.
 */
fun MockWebServer.next(): RecordedRequest {
  while (true) {
    val r = takeRequest(5, TimeUnit.SECONDS) ?: error("no request reached the fake server within 5 seconds: the call failed before the network")
    if (r.path?.startsWith("/auth/login-params") != true) return r
  }
}

/** Requests other than the sign-in handshake. */
val MockWebServer.apiRequestCount: Int get() = requestCount - ((dispatcher as? SchemeDispatcher)?.handshakes ?: 0)

/**
 * Answers the sign-in handshake by itself and everything else from a queue, as the server's own dispatcher does.
 * The default answer is an older API's: 404, which the app reads as "every account is plain". [split] answers
 * with an account's salt and recipe, so the app derives an auth key and sends that instead of the password.
 * Tests that install it queue responses with [MockWebServer.queue] (the server's `enqueue` only feeds its own).
 */
class SchemeDispatcher(private val params: MockResponse = olderApi) : Dispatcher() {
  // Its own queue: the legacy QueueDispatcher in this OkHttp is a shim whose dispatch() must not be called directly.
  private val queue = java.util.concurrent.LinkedBlockingQueue<MockResponse>()
  var handshakes = 0
  fun enqueue(response: MockResponse) { queue.add(response) }
  override fun dispatch(request: RecordedRequest): MockResponse =
    if (request.path?.startsWith("/auth/login-params") == true) { handshakes++; params }
    // A request nothing was queued for is a test's mistake; say so in the answer rather than hang.
    else queue.poll(2, TimeUnit.SECONDS) ?: MockResponse().setResponseCode(599).setBody("""{"success":false,"info":"the test queued no response for ${request.method} ${request.path}"}""")

  companion object {
    val olderApi = MockResponse().setResponseCode(404).setBody("""{"success":false,"name":"NotFoundError","info":"Cannot GET /auth/login-params","status":404}""")
    fun split(salt: String = "SALT") = MockResponse().setBody("""{"data":{"scheme":"split","kdfSalt":"$salt","kdfParams":{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}},"success":true,"status":200}""")
    fun plain(salt: String = "FAKESALT") = MockResponse().setBody("""{"data":{"scheme":"plain","kdfSalt":"$salt","kdfParams":{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}},"success":true,"status":200}""")
  }
}

/** Queues a response on a [SchemeDispatcher]; a plain server queues with its own `enqueue`. */
fun MockWebServer.queue(response: MockResponse) = (dispatcher as SchemeDispatcher).enqueue(response)
