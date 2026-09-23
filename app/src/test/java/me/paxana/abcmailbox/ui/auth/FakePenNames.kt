package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.PenNameRepository
import me.paxana.abcmailbox.domain.PenName
import me.paxana.abcmailbox.domain.PenNameCheck
import me.paxana.abcmailbox.domain.PenNames

/** A scripted [PenNameRepository]: every name is free unless listed in [taken]; [checkError] fails the check. */
class FakePenNames : PenNameRepository {
  val taken = mutableSetOf<String>()
  var checkError: AppError? = null
  val checks = mutableListOf<String>()
  var names = PenNames(null, emptyList())
  var setTo: String? = null
  var setError: AppError? = null
  override suspend fun check(name: String): ApiResult<PenNameCheck> {
    val n = PenName.normalise(name); checks += n
    checkError?.let { return ApiResult.Failure(it) }
    return ApiResult.Success(if (n.lowercase() in taken) PenNameCheck(n, false, "That pen name is taken.", PenName.hasTwoParts(n)) else PenNameCheck(n, true, null, PenName.hasTwoParts(n)))
  }
  override suspend fun names() = ApiResult.Success(names)
  override suspend fun set(name: String): ApiResult<String> { setError?.let { return ApiResult.Failure(it) }; setTo = PenName.normalise(name); return ApiResult.Success(setTo!!) }
}
