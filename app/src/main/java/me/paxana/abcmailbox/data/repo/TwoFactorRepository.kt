package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.TwoFactorCodeRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.data.session.SessionRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Where the account stands with two-factor sign-in (API #173), and whether it may be switched off (#175). */
data class TwoFactorStatus(
  val enabled: Boolean, val enabledAt: Instant?, val recoveryCodesLeft: Int,
  /** Required of this account: it cannot be switched off, and must be set up before anything else works. */
  val required: Boolean, val requiredBecause: List<String>,
)

/** What an authenticator app needs: the `otpauth://` link (shown as a QR code, or opened on this phone) and its secret. */
data class TwoFactorSetup(val secret: String, val otpauthUri: String)

/**
 * The account's own two-factor settings: set up with a secret and a first code, which answers the recovery codes once;
 * a new set of codes; switching off, which takes a code too, since a session alone is not enough.
 */
interface TwoFactorRepository {
  suspend fun status(): ApiResult<TwoFactorStatus>
  suspend fun setup(): ApiResult<TwoFactorSetup>
  /** Switches it on. Answers the recovery codes: the only time the server says them. */
  suspend fun confirm(code: String): ApiResult<List<String>>
  suspend fun newRecoveryCodes(code: String): ApiResult<List<String>>
  /** [recovery]: the code is one of the recovery codes, for a phone that is lost. */
  suspend fun switchOff(code: String, recovery: Boolean): ApiResult<Unit>
}

@Singleton
class DefaultTwoFactorRepository @Inject constructor(private val api: AuthApi, private val sessions: SessionRepository, private val json: Json) : TwoFactorRepository {
  override suspend fun status(): ApiResult<TwoFactorStatus> = apiCall(json) { api.twoFactor() }.map { env ->
    val d = checkNotNull(env.data) { "two-factor status had no data" }
    // The server has the last word on a requirement: one no longer in force stops sending the app to set-up.
    if (!d.required || d.enabled) sessions.twoFactorSetUp()
    TwoFactorStatus(d.enabled, d.enabledAt.toInstantOrNull(), d.recoveryCodesLeft, d.required, d.requiredBecause)
  }

  override suspend fun setup(): ApiResult<TwoFactorSetup> = apiCall(json) { api.twoFactorSetup() }.map { env ->
    val d = checkNotNull(env.data) { "two-factor setup had no data" }
    TwoFactorSetup(d.secret, d.otpauthUri)
  }

  override suspend fun confirm(code: String): ApiResult<List<String>> =
    apiCall(json) { api.twoFactorConfirm(TwoFactorCodeRequest(code = digits(code))) }.map { env -> env.data?.recoveryCodes.orEmpty() }.also { if (it is ApiResult.Success) sessions.twoFactorSetUp() }

  override suspend fun newRecoveryCodes(code: String): ApiResult<List<String>> =
    apiCall(json) { api.twoFactorNewCodes(TwoFactorCodeRequest(code = digits(code))) }.map { env -> env.data?.recoveryCodes.orEmpty() }

  override suspend fun switchOff(code: String, recovery: Boolean): ApiResult<Unit> =
    apiCall(json) { api.twoFactorOff(if (recovery) TwoFactorCodeRequest(recoveryCode = code.trim()) else TwoFactorCodeRequest(code = digits(code))) }.map { }

  /** An authenticator shows "123 456"; the server wants the six digits. A recovery code is sent as typed: the server folds it. */
  private fun digits(code: String) = code.filter(Char::isDigit)
}
