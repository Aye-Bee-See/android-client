package me.paxana.abcmailbox.data.session

class FakeSchemeMemory(vararg known: String) : SchemeMemory {
  val split = known.map { it.lowercase() }.toMutableSet()
  override suspend fun isKnownSplit(username: String) = username.trim().lowercase() in split
  override suspend fun rememberSplit(username: String) { split += username.trim().lowercase() }
}
