package me.paxana.abcmailbox.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Runs one Retrofit call and turns the ways it can fail into [AppError].
 * Retrofit throws [HttpException] for any non-2xx status; the error body is
 * still the API envelope, so it is parsed to recover `errors` or `info`.
 */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): ApiResult<T> = try {
  ApiResult.Success(block())
} catch (e: CancellationException) {
  // Not a failure of the call: the coroutine that asked is gone (its screen closed, its worker was stopped). Handed
  // on, so the caller stops too; as an [AppError.Unexpected] it would carry on, and be counted as a server fault.
  throw e
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
    400 -> {
      // The letter is in the wrong shape for the server's mode: nothing the writer can fix in a form.
      if (envelope?.problems?.any { it.code == "wrong_encryption_mode" } == true) return AppError.WrongEncryptionMode
      // The sentences are under `errors`, or, for a refusal raised as a general error, one sentence under `error`.
      val sentences = envelope?.errors?.takeIf { it.isNotEmpty() } ?: listOfNotNull(envelope?.error ?: info ?: "The request was rejected.")
      // `problems[i]` is `sentences[i]`, a documented rule since #133; without that pairing, the sentences alone.
      val problems = envelope?.problems.orEmpty().takeIf { it.size == sentences.size }.orEmpty()
      AppError.Validation(sentences, envelope?.condition, problems.zip(sentences) { p, s -> FieldProblem(p.field, p.code ?: "validation_failed", p.params?.min, p.params?.max, s) })
    }
    401 -> AppError.Unauthorized(info)
    403 -> AppError.Forbidden(info ?: "You are not allowed to do that.", envelope?.name, envelope?.code)
    // Like a 409, a 404 may carry the useful sentence in `error` ("Message 99999 not found") under a general `info`.
    404 -> AppError.NotFound(envelope?.error ?: info, envelope?.condition)
    // Lifecycle refusals put the useful sentence in `error` ("A printed letter cannot move to queued"); `info` is generic.
    409 -> AppError.Conflict(envelope?.error ?: info, envelope?.name, envelope?.condition)
    410 -> AppError.Gone(info, goneCondition(envelope?.condition, envelope?.error))
    // An Idempotency-Key reused for a different request (API PR #97). Retrying unchanged would get the same answer, so it is a refusal, not a server fault.
    422 -> AppError.Validation(listOfNotNull(envelope?.error ?: info ?: "The request was rejected."))
    429 -> AppError.RateLimited(info, response()?.headers()?.get("Retry-After")?.toLongOrNull())
    else -> AppError.Server(code(), info)
  }
}

/**
 * Why something is gone. The API keeps a code for it (`expired`, `used`) but does not put it in the answer yet; what
 * arrives is the sentence it builds from the code, "Claim token is expired." So the code is read back out of that
 * sentence, and a `condition` field wins the day it is sent. Anything unrecognised is null: a plain "gone".
 */
internal fun goneCondition(condition: String?, error: String?): String? =
  condition?.takeIf { it.isNotBlank() } ?: error?.let { Regex("""^(?:Claim token|Invitation) is (expired|used)\.$""").find(it.trim())?.groupValues?.get(1) }
