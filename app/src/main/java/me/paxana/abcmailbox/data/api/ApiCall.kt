package me.paxana.abcmailbox.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException
import java.io.IOException

/**
 * Runs one Retrofit call and turns the ways it can fail into [AppError].
 * Retrofit throws [HttpException] for any non-2xx status; the error body is
 * still the API envelope, so it is parsed to recover `errors` or `info`.
 */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): ApiResult<T> = try {
  ApiResult.Success(block())
} catch (e: HttpException) {
  ApiResult.Failure(e.toAppError(json))
} catch (e: IOException) {
  ApiResult.Failure(AppError.Network(e))
} catch (e: Exception) {
  ApiResult.Failure(AppError.Unexpected(e))
}

fun HttpException.toAppError(json: Json): AppError {
  val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
  val envelope = body?.let { runCatching { json.decodeFromString<ApiEnvelope<JsonElement>>(it) }.getOrNull() }
  val info = envelope?.info ?: envelope?.error
  return when (code()) {
    400 -> envelope?.errors?.takeIf { it.isNotEmpty() }?.let { AppError.Validation(it) }
      ?: AppError.Validation(listOfNotNull(info ?: "The request was rejected."))
    401 -> AppError.Unauthorized(info)
    403 -> AppError.Forbidden(info ?: "You are not allowed to do that.")
    404 -> AppError.NotFound(info)
    409 -> AppError.Conflict(info)
    410 -> AppError.Gone(info)
    429 -> AppError.RateLimited(info, response()?.headers()?.get("Retry-After")?.toLongOrNull())
    else -> AppError.Server(code(), info)
  }
}
