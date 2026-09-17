package me.paxana.abcmailbox.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class GroupKeysTest {
  companion object { @JvmStatic @BeforeClass fun init() = Sodium.initialize() }

  @Test
  fun `a public key can be recomputed from its private key`() {
    val kp = Sodium.keypair()
    assertArrayEquals(kp.publicKey, Sodium.publicKeyOf(kp.privateKey))
  }

  @Test
  fun `a group key made for the first member opens for them, and can be handed to a second`() {
    val first = Sodium.keypair(); val second = Sodium.keypair()
    val group = GroupKeys.createSealedTo(Sodium.toBase64(first.publicKey))
    val opened = GroupKeys.open(group.sealedPrivateKey, first, group.publicKey)
    assertArrayEquals(group.keyPair.privateKey, opened.privateKey)

    val forSecond = GroupKeys.sealPrivateKey(opened.privateKey, Sodium.toBase64(second.publicKey))
    assertArrayEquals(group.keyPair.privateKey, GroupKeys.open(forSecond, second, group.publicKey).privateKey)
    assertThrows(CannotOpenException::class.java) { GroupKeys.open(forSecond, first, group.publicKey) }
  }

  @Test
  fun `a key that does not match the published public key is refused`() {
    val member = Sodium.keypair()
    val group = GroupKeys.createSealedTo(Sodium.toBase64(member.publicKey))
    val someoneElse = Sodium.toBase64(Sodium.keypair().publicKey)
    assertThrows(KeyMismatchException::class.java) { GroupKeys.open(group.sealedPrivateKey, member, someoneElse) }
  }

  @Test
  fun `the whole custody chain, member to group to writer to claim token, ends at a letter`() {
    val member = Sodium.keypair()
    val group = GroupKeys.createSealedTo(Sodium.toBase64(member.publicKey))
    val writer = GroupKeys.createSealedTo(group.publicKey)

    // The group writes for the writer: envelopes to the writer and to itself.
    val letter = LetterCipher.encrypt("Dear Jane", "Please use the blue paper", listOf(
      Reader(Reader.USER, 47, writer.publicKey), Reader(Reader.CHAPTER, 1, group.publicKey, keyVersion = 1)))

    // A member reads it both ways: with the group key, and with the writer's key held in custody.
    val groupKey = GroupKeys.open(group.sealedPrivateKey, member, group.publicKey)
    val writerKey = GroupKeys.open(writer.sealedPrivateKey, groupKey, writer.publicKey)
    for ((envelope, key) in listOf(letter.envelopes[1] to groupKey, letter.envelopes[0] to writerKey)) {
      val contentKey = LetterCipher.openEnvelope(envelope.wrappedKey, key)
      assertEquals("Dear Jane", LetterCipher.decryptText(letter.body.ciphertext, letter.body.nonce, contentKey))
    }

    // Hand-off: the token wraps the same private key, and the server is given only the hash.
    val claim = GroupKeys.claimToken(writerKey.privateKey)
    assertTrue(SecretCodes.isWellFormed(claim.token))
    assertEquals(SecretCodes.hashHex(claim.token.lowercase().chunked(4).joinToString("-")), claim.tokenHash)
    val claimed = AccountKeys.unlockWithCode(writer.publicKey, claim.wrapped.wrapped, claim.token.lowercase(), claim.wrapped.salt, claim.wrapped.params)
    assertArrayEquals(writer.keyPair.privateKey, claimed.privateKey)
  }
}
