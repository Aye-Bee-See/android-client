package me.paxana.abcmailbox.data.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
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
  fun `409 and 410 map to Conflict and Gone`() = runTest {
    val c = apiCall(json) { throw http(409, """{"info":"Letters only move forward."}""") }
    val g = apiCall(json) { throw http(410, """{"info":"This claim token has expired."}""") }
    assertEquals(AppError.Conflict("Letters only move forward."), (c as ApiResult.Failure).error)
    assertEquals(AppError.Gone("This claim token has expired."), (g as ApiResult.Failure).error)
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
}
