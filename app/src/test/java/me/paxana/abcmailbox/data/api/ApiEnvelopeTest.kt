package me.paxana.abcmailbox.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEnvelopeTest {

  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

  @Serializable
  data class Thing(val id: Int, val name: String)

  @Test
  fun `parses a single record`() {
    val text = """{"data":{"id":41,"name":"Ales"},"info":"Prisoner found.","success":true,"status":200,"name":"prisoner read"}"""
    val env = json.decodeFromString<ApiEnvelope<Thing>>(text)
    assertEquals(Thing(41, "Ales"), env.data)
    assertEquals("Prisoner found.", env.info)
    assertEquals("prisoner read", env.name)
  }

  @Test
  fun `parses a list with paging fields`() {
    val text = """{"data":[{"id":1,"name":"a"},{"id":2,"name":"b"}],"total":25,"page":2,"page_size":10,"success":true,"status":200}"""
    val page = json.decodeFromString<ApiEnvelope<List<Thing>>>(text).toPage()
    assertEquals(2, page.items.size)
    assertEquals(25, page.total)
    assertEquals(2, page.page)
    assertEquals(10, page.pageSize)
    assertTrue(page.hasMore)
  }

  @Test
  fun `last page reports no more`() {
    val text = """{"data":[{"id":1,"name":"a"}],"total":21,"page":3,"page_size":10,"success":true,"status":200}"""
    assertEquals(false, json.decodeFromString<ApiEnvelope<List<Thing>>>(text).toPage().hasMore)
  }

  @Test
  fun `parses a validation failure with no data`() {
    val text = """{"success":false,"errors":["Username must be 3 to 16 characters.","Password is required."],"status":400}"""
    val env = json.decodeFromString<ApiEnvelope<Thing>>(text)
    assertNull(env.data)
    assertEquals(2, env.errors?.size)
  }

  @Test
  fun `unknown fields are ignored`() {
    val text = """{"data":{"id":1,"name":"a","surprise":true},"success":true,"status":200,"extra":"field"}"""
    assertEquals(Thing(1, "a"), json.decodeFromString<ApiEnvelope<Thing>>(text).data)
  }
}
