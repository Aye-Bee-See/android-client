package me.paxana.abcmailbox.data.api

/**
 * Everything that can go wrong talking to the API, in the vocabulary the
 * screens need. The brief's rule: a 400 carries `errors` (a list of sentences);
 * every other failure carries `info` (one sentence), and a 403's `info` says
 * exactly why, so it is shown verbatim.
 */
sealed class AppError : Exception() {
  /** The server rejected the input; each entry is a complete sentence. */
  data class Validation(val errors: List<String>) : AppError()

  /** No token, a bad token, or (on login) wrong credentials. */
  data class Unauthorized(val info: String?) : AppError()

  /** The caller is known but not allowed; `info` explains what to do. */
  data class Forbidden(val info: String) : AppError()

  data class NotFound(val info: String?) : AppError()

  /** A lifecycle or state conflict (409), for example moving a letter backwards. */
  /** [name] is the API's error name (`KeyVersionError`, `LetterStatusError`, `IdempotencyError`), for the few callers that must tell them apart. */
  data class Conflict(val info: String?, val name: String? = null, /** A code beside the sentence, where the API has one (PR #117): `only_admin`, `group_owner`, `last_key_holder`, `anonymous`… */ val condition: String? = null) : AppError() {
    /** The same Idempotency-Key is being processed right now (a retry racing the original): wait a second and ask again. */
    val isStillProcessing: Boolean get() = name == "IdempotencyError"
    /** A status move that another volunteer (or a double tap) made first: refresh, do not show red. Read from the sentence until the API has a condition for it (PLAN.md, ask 28). */
    val changedMeanwhile: Boolean get() = name == "LetterStatusError" && info?.contains("someone else", ignoreCase = true) == true
  }

  /** A used or expired claim token (410). */
  /**
   * `condition` says why, where the server says so: a claim token that is `expired` sends the person to their group
   * for a new one; one that is `used` means somebody has the account already, which is a different conversation.
   */
  data class Gone(val info: String?, val condition: String? = null) : AppError()

  /** 429: too many sign-in, claim, or recovery attempts. `retryAfterSeconds` comes from the `Retry-After` header. */
  data class RateLimited(val info: String?, val retryAfterSeconds: Long?) : AppError()

  data class Server(val status: Int, val info: String?) : AppError()

  /** Could not reach the server at all. */
  data class Network(override val cause: Throwable) : AppError()

  data class Unexpected(override val cause: Throwable) : AppError()

  /** The sentence to show a person, when one exists. */
  val userMessage: String?
    get() = when (this) {
      is Validation -> errors.joinToString(" ")
      is Unauthorized -> info
      is Forbidden -> info
      is NotFound -> info
      is Conflict -> info
      is Gone -> info
      is RateLimited -> info ?: retryAfterSeconds?.let { "Too many attempts. Try again in ${(it + 59) / 60} minute(s)." } ?: "Too many attempts. Try again later."
      is Server -> info
      is Network -> null
      is Unexpected -> null
    }
}

/** A typed outcome so callers handle failure explicitly instead of catching exceptions. */
sealed interface ApiResult<out T> {
  data class Success<T>(val value: T) : ApiResult<T>
  data class Failure(val error: AppError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
  is ApiResult.Success -> ApiResult.Success(transform(value))
  is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.value
fun <T> ApiResult<T>.errorOrNull(): AppError? = (this as? ApiResult.Failure)?.error
