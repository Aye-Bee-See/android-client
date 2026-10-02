package me.paxana.abcmailbox.data.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the cache holds in the clear goes when nobody is signed in: letters and replies opened for reading (decrypted
 * downloads), files staged for a letter, camera shots, printed invite slips. Signing out keeps what is sealed (unsent
 * letters, drafts) for the person's return; it does not keep readable copies of their correspondence on a phone that
 * may now be somebody else's to look through. The same when the server ends the session, and at every start signed out.
 *
 * A class of its own, started from the Application: the session repository cannot call the files (nothing in it may
 * depend on what depends on it), so this watches instead, as the group keyring does.
 */
@Singleton
class CacheSweeper @Inject constructor(
  private val sessions: SessionRepository,
  private val files: LocalFilesContract,
  @ApplicationScope private val scope: CoroutineScope,
) {
  fun start() {
    // On the application scope's own thread (not Main): a handful of files, deleted where the scope runs.
    scope.launch { sessions.state.filter { it is SessionState.SignedOut }.collect { runCatching { files.emptyCaches() } } }
  }
}
