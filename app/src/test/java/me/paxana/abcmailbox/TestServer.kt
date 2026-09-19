package me.paxana.abcmailbox

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

/**
 * The next request the fake server received, or a failure after five seconds. `takeRequest()` with
 * no timeout waits for ever, so a change that stops a request being made (19 Sep 2026: a Retrofit
 * method that lost its @POST, which only fails when the call is built) hung the whole suite, and
 * would have hung CI for its full half hour, instead of failing one test with a reason.
 */
fun MockWebServer.next(): RecordedRequest = takeRequest(5, TimeUnit.SECONDS) ?: error("no request reached the fake server within 5 seconds: the call failed before the network")
