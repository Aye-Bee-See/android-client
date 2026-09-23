package me.paxana.abcmailbox.data.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ApiCallTest {

  private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

  private fun http(code: Int, body: String) = HttpException(
    Response.error<Any>(code, body.toResponseBody("application/json".toMediaType()))
  )

  @Test
  fun `400 becomes Validation with the errors list`() = runTest {
    val r = apiCall(json) { throw http(400, """{"success":false,"errors":["Too short.","Email required."]}""") }
    val e = (r as ApiResult.Failure).error as AppError.Validation
    assertEquals(listOf("Too short.", "Email required."), e.errors)
    assertEquals("Too short. Email required.", e.userMessage)
  }

  @Test
  fun `403 becomes Forbidden carrying info verbatim`() = runTest {
    val info = "Your group is pending approval; an admin must activate it before you can send letters."
    val r = apiCall(json) { throw http(403, """{"success":false,"info":"$info","status":403}""") }
    assertEquals(AppError.Forbidden(info), (r as ApiResult.Failure).error)
  }

  @Test
  fun `401 becomes Unauthorized`() = runTest {
    val r = apiCall(json) { throw http(401, """{"success":false,"info":"Incorrect username or password."}""") }
    assertTrue((r as ApiResult.Failure).error is AppError.Unauthorized)
  }

  @Test
  fun `a claim token that is gone says whether it expired or was used, and anything else is just gone`() = runTest {
    // Recorded from the API on 21 Sep 2026 (PR #113). It keeps a code for the reason but does not send it yet, so the
    // code is read out of the sentence it builds from it; a `condition` field, the day it arrives, wins.
    val expired = apiCall(json) { throw http(410, """{"success":false,"name":"ClaimTokenError","info":"This claim token has expired. Ask your group for a new one.","status":410,"error":"Claim token is expired."}""") }
    assertEquals(AppError.Gone("This claim token has expired. Ask your group for a new one.", "expired"), (expired as ApiResult.Failure).error)
    assertEquals("used", goneCondition(null, "Claim token is used."))
    assertEquals("expired", goneCondition(null, "Invitation is expired."))
    assertEquals("a field beats a sentence", "used", goneCondition("used", "Claim token is expired."))
    assertEquals(null, goneCondition(null, "This letter was deleted.")); assertEquals(null, goneCondition("  ", null)); assertEquals(null, goneCondition(null, "Claim token is unknown."))
  }

  @Test
  fun `a 409 carries the API's condition beside its name, where it sends one`() = runTest {
    val c = apiCall(json) { throw http(409, """{"success":false,"name":"AccountDeleteError","info":"Error deleting user.","status":409,"error":"This is the only admin account. Make another admin first, or nobody could run the site.","condition":"only_admin"}""") }
    assertEquals(AppError.Conflict("This is the only admin account. Make another admin first, or nobody could run the site.", "AccountDeleteError", "only_admin"), (c as ApiResult.Failure).error)
    // A status move somebody else made first (the brief's review note): known by its sentence until it has a condition.
    val m = apiCall(json) { throw http(409, """{"success":false,"name":"LetterStatusError","info":"Error updating letter status.","status":409,"error":"Letter 41 was changed by someone else meanwhile; nothing was moved."}""") }
    assertTrue(((m as ApiResult.Failure).error as AppError.Conflict).changedMeanwhile)
  }

  @Test
  fun `409 and 410 map to Conflict and Gone`() = runTest {
    val c = apiCall(json) { throw http(409, """{"info":"Letters only move forward."}""") }
    val g = apiCall(json) { throw http(410, """{"info":"This claim token has expired."}""") }
    assertEquals(AppError.Conflict("Letters only move forward."), (c as ApiResult.Failure).error)
    assertEquals(AppError.Gone("This claim token has expired."), (g as ApiResult.Failure).error)
  }

  @Test
  fun `a lifecycle 409 shows the specific sentence from error, not the generic info`() = runTest {
    val r = apiCall(json) { throw http(409, """{"success":false,"name":"LetterStatusError","info":"Error updating letter status.","status":409,"error":"A printed letter cannot move to queued."}""") }
    assertEquals("A printed letter cannot move to queued.", (r as ApiResult.Failure).error.userMessage)
  }

  @Test
  fun `429 becomes RateLimited with the Retry-After seconds`() = runTest {
    val response = Response.error<Any>(
      """{"success":false,"name":"RateLimitError","info":"Too many sign-in attempts. Try again in 15 minute(s).","status":429}""".toResponseBody("application/json".toMediaType()),
      okhttp3.Response.Builder().code(429).message("Too Many Requests").protocol(okhttp3.Protocol.HTTP_1_1)
        .request(okhttp3.Request.Builder().url("http://localhost/auth/login").build()).header("Retry-After", "900").build(),
    )
    val r = apiCall(json) { throw HttpException(response) }
    val e = (r as ApiResult.Failure).error as AppError.RateLimited
    assertEquals(900L, e.retryAfterSeconds)
    assertEquals("Too many sign-in attempts. Try again in 15 minute(s).", e.userMessage)
    assertEquals("Too many attempts. Try again in 15 minute(s).", AppError.RateLimited(null, 900).userMessage)
  }

  @Test
  fun `an unparseable error body still maps by status`() = runTest {
    val r = apiCall(json) { throw http(500, "<html>nope</html>") }
    assertEquals(AppError.Server(500, null), (r as ApiResult.Failure).error)
  }

  @Test
  fun `IOException becomes Network`() = runTest {
    val r = apiCall(json) { throw IOException("unreachable") }
    assertTrue((r as ApiResult.Failure).error is AppError.Network)
  }

  @Test
  fun `success passes the value through`() = runTest {
    assertEquals(ApiResult.Success(7), apiCall(json) { 7 })
  }

  @Test
  fun `the idempotency answers are told apart, a retry still in flight from a key used for another letter`() = runTest {
    val inFlight = apiCall(json) { throw http(409, """{"success":false,"name":"IdempotencyError","info":"A request with this Idempotency-Key is still being processed.","status":409}""") }
    val conflict = (inFlight as ApiResult.Failure).error as AppError.Conflict
    assertTrue("wait and ask again", conflict.isStillProcessing)
    assertFalse((AppError.Conflict("A printed letter cannot move to queued.", "LetterStatusError")).isStillProcessing)

    // 422 is an answer about this request, not a server fault: retrying unchanged would get it again.
    val mismatch = apiCall(json) { throw http(422, """{"success":false,"name":"IdempotencyError","info":"This Idempotency-Key was used for a different letter.","status":422}""") }
    assertEquals(AppError.Validation(listOf("This Idempotency-Key was used for a different letter.")), (mismatch as ApiResult.Failure).error)
  }
}
