package me.paxana.abcmailbox.data.session

import kotlinx.serialization.Serializable
import me.paxana.abcmailbox.data.api.LoginData

/** What the app keeps about a signed-in account. Stored encrypted; see [SessionStore]. */
@Serializable
data class Session(
  val token: String,
  val expiresAtMillis: Long,
  val user: SessionUser,
  /**
   * How this session proves its password from now on. `true`: signed in the way accounts from before the split
   * scheme sign in, by the person's explicit choice, and every later proof goes the same way. `false`: the way the
   * server names for the account. `null`: saved by a release from before this field existed, so which way it signed
   * in was not recorded (DataStore keeps sessions across updates); the repository settles it the first time it
   * matters, from the account's own key and without sending anything. A sign-in always writes `true` or `false`.
   */
  val olderAccount: Boolean? = null,
)

@Serializable
data class SessionUser(
  val id: Int,
  val username: String,
  val name: String?,
  val email: String?,
  val role: String,
  val chapterId: Int?,
  /** The name the letters are signed with (API PR #120); null until one is chosen. */
  val penName: String? = null,
) {
  val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: username
  val isStaff: Boolean get() = role == Role.CHAPTER || role == Role.ADMIN
}

object Role {
  const val USER = "user"
  const val CHAPTER = "chapter"
  const val ADMIN = "admin"
  const val BANNED = "banned"
}

fun LoginData.toSession() = Session(
  token = token.token,
  expiresAtMillis = token.expires,
  user = SessionUser(
    id = user.id,
    username = user.username,
    name = user.name,
    email = user.email,
    role = user.role,
    chapterId = user.chapterId,
    penName = user.penName,
  ),
)

/** The three states a screen can be in with respect to sign-in. */
sealed interface SessionState {
  /** The stored session has not been read yet; show nothing rather than a sign-in prompt. */
  data object Loading : SessionState
  data object SignedOut : SessionState
  data class SignedIn(val session: Session) : SessionState
}
