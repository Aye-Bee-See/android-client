package me.paxana.abcmailbox.data.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import me.paxana.abcmailbox.di.NetworkModule
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks the app against a running API, not against our idea of it.
 *
 *   python3 tools/capture-contract.py /tmp/contract
 *   ABC_CONTRACT_DIR=/tmp/contract ./gradlew :app:testDebugUnitTest --tests '*ContractCheck*' --rerun
 *
 * Skipped unless ABC_CONTRACT_DIR is set, so ordinary test runs need no server. For every captured
 * response it (1) decodes it with the DTO the app really uses and the app's real Json settings, which
 * catches a changed type; and (2) lists every field a DTO declares that appears in no captured
 * response. The app's parser ignores unknown keys and defaults missing ones, so a field the API
 * renamed would not crash anything, it would quietly become a blank on a screen: list (2) is how
 * that gets noticed. Some entries are expected (request-only fields, fields of the other encryption
 * mode, data the dev database does not have); the known ones are in [expectedAbsent].
 */
@OptIn(ExperimentalSerializationApi::class)
class ContractCheckTest {
  private val json = NetworkModule.json()

  private fun <T> enveloped(s: KSerializer<T>) = ApiEnvelope.serializer(s)
  private fun <T> listOf(s: KSerializer<T>) = ApiEnvelope.serializer(ListSerializer(s))

  /** Capture name (without the mode prefix) to the type Retrofit decodes that response into. */
  private val contracts: Map<String, KSerializer<*>> = mapOf(
    "health" to HealthDto.serializer(),
    "login.user1" to enveloped(LoginData.serializer()), "login.member1" to enveloped(LoginData.serializer()),
    "user" to enveloped(UserDto.serializer()),
    "keys.writer" to enveloped(KeyBundleDto.serializer()), "keys.member" to enveloped(KeyBundleDto.serializer()),
    "publicKey.user" to enveloped(PublicKeyDto.serializer()), "publicKey.chapter" to enveloped(PublicKeyDto.serializer()),
    "recoverStart" to enveloped(RecoverStartDto.serializer()), "claimInfo" to enveloped(ClaimInfoDto.serializer()),
    "prisoners" to listOf(PrisonerDto.serializer()), "prisoners.featured" to listOf(PrisonerDto.serializer()), "prisoner" to enveloped(PrisonerDto.serializer()),
    "prisons" to listOf(PrisonDto.serializer()), "prison" to enveloped(PrisonDto.serializer()), "mailRules" to enveloped(MailRuleVocabularyDto.serializer()),
    "chapters" to listOf(ChapterDto.serializer()), "chapter" to enveloped(ChapterDto.serializer()),
    "chats" to listOf(ChatDto.serializer()), "chats.member" to listOf(ChatDto.serializer()), "chat" to enveloped(ChatDto.serializer()), "chatByPrisoner" to enveloped(ChatDto.serializer()),
    "message" to enveloped(MessageDto.serializer()), "queue" to listOf(MessageDto.serializer()), "attachments" to listOf(AttachmentDto.serializer()),
    "retention" to enveloped(RetentionDto.serializer()),
    "notifications" to listOf(NotificationDto.serializer()),
    "writers" to listOf(WriterDto.serializer()), "issueToken" to enveloped(IssuedTokenDto.serializer()), "memberKeys" to enveloped(MemberKeysDto.serializer()),
  )

  /** Declared fields that no capture is expected to contain, with the reason. Anything else absent deserves a look. */
  private val expectedAbsent = mapOf(
    "ApiEnvelope.errors" to "only on a 400", "ApiEnvelope.error" to "only on a 409",
    "AttachmentDto.nonce" to "end-to-end attachments only; the dev data has none",
    "EnvelopeDto.keyVersion" to "sent by the app, never returned (plan, ask 11)",
    "ApiEnvelope.unread" to "only on the notification feed",
  )

  @Test
  fun `every captured response decodes, and every field the app reads is still being sent`() {
    val dir = System.getenv("ABC_CONTRACT_DIR")?.let(::File)
    assumeTrue("set ABC_CONTRACT_DIR to a directory made by tools/capture-contract.py", dir?.isDirectory == true)
    val files = dir!!.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
    assumeTrue("no captures in $dir", files.isNotEmpty())

    val declared = sortedSetOf<String>(); val present = mutableSetOf<String>()
    val failures = mutableListOf<String>(); val unmapped = mutableListOf<String>()
    for (file in files) {
      val name = file.nameWithoutExtension.substringAfter('.') // drop "server." / "e2e."
      val serializer = contracts[name] ?: run { unmapped += file.name; continue }
      val text = file.readText()
      runCatching { json.decodeFromString(serializer, text) }.onFailure { failures += "${file.name}: ${it.message?.lineSequence()?.first()}" }
      walk(serializer.descriptor, json.parseToJsonElement(text), declared, present)
    }

    val absent = declared - present
    val report = buildString {
      appendLine("Contract check: ${files.size} captured responses, ${declared.size} fields declared by the app's DTOs.")
      appendLine(if (failures.isEmpty()) "Decoding: all responses decode with the app's types." else "DECODING FAILURES:\n" + failures.joinToString("\n") { "  $it" })
      if (unmapped.isNotEmpty()) appendLine("No contract registered for: $unmapped")
      appendLine("Declared by the app but in no captured response (${absent.size}):")
      absent.forEach { appendLine("  $it" + (expectedAbsent[it]?.let { why -> "   [expected: $why]" } ?: "")) }
    }
    println(report)
    File("build/contract-report.txt").apply { parentFile.mkdirs() }.writeText(report)
    assertTrue(report, failures.isEmpty())
  }

  /** Records `Type.field` for every declared field, and which of them appear as a key (null or not) in any capture. */
  private fun walk(d: SerialDescriptor, e: JsonElement, declared: MutableSet<String>, present: MutableSet<String>, depth: Int = 0) {
    if (depth > 12) return
    when (d.kind) {
      StructureKind.CLASS, StructureKind.OBJECT -> {
        val type = d.serialName.substringAfterLast('.').removeSuffix("?")
        for (i in 0 until d.elementsCount) {
          val key = "$type.${d.getElementName(i)}"
          declared += key
          val value = (e as? JsonObject)?.get(d.getElementName(i))
          // A key sent as null still proves the API knows the field by this name; only a missing key is suspicious.
          if (value != null) present += key
          walk(d.getElementDescriptor(i), value ?: JsonNull, declared, present, depth + 1) // still record what is declared beneath
        }
      }
      StructureKind.LIST -> {
        val items = (e as? JsonArray).orEmpty()
        if (items.isEmpty()) walk(d.getElementDescriptor(0), JsonNull, declared, present, depth + 1)
        else items.forEach { walk(d.getElementDescriptor(0), it, declared, present, depth + 1) }
      }
      else -> Unit // primitives, maps, and raw JsonElement fields have nothing declared inside them
    }
  }
}
