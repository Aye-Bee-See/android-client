package me.paxana.abcmailbox.ui.group

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.ReferenceRepository
import me.paxana.abcmailbox.domain.ReferenceLookup
import me.paxana.abcmailbox.domain.ReferencedPrisoner
import me.paxana.abcmailbox.domain.ReferencedWriter
import me.paxana.abcmailbox.domain.WriterMatch
import me.paxana.abcmailbox.text.TestStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordReplyViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  private class FakeRefs : ReferenceRepository {
    val lookups = mutableListOf<String>(); val searches = mutableListOf<String>()
    var lookupError: AppError? = null
    override suspend fun lookup(number: String): ApiResult<ReferenceLookup> {
      lookups += number; lookupError?.let { return ApiResult.Failure(it) }
      return ApiResult.Success(ReferenceLookup("4827-1935-6", null, null, 9, ReferencedWriter(4, "James Hollow", "Jim", false), ReferencedPrisoner(3, "Alex"), "PDX ABC"))
    }
    override suspend fun writers(name: String): ApiResult<List<WriterMatch>> { searches += name; return ApiResult.Success(listOf(WriterMatch(4, "James Hollow", false, "Jim H", false))) }
  }

  @Test
  fun `the shape is refused on the phone, then the number is looked up and the reply can be recorded`() = runTest {
    val refs = FakeRefs()
    val vm = RecordReplyViewModel(refs, TestStrings())
    vm.onNumber("4827-1935"); vm.lookUp(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("That is 8 digits; a reference has 9.", vm.ui.value.error); assertTrue(refs.lookups.isEmpty())
    vm.onNumber("4827 1935 6"); vm.lookUp(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("4827 1935 6"), refs.lookups)
    assertEquals("James Hollow", vm.ui.value.found?.writer?.displayName); assertEquals("4827-1935-6", vm.ui.value.number)
  }

  @Test
  fun `a wrong check digit is called a typo, and a stranger's number is not told apart from an unissued one`() = runTest {
    val typo = RecordReplyViewModel(FakeRefs().apply { lookupError = AppError.Validation(listOf("server words"), "checksum") }, TestStrings())
    typo.onNumber("4827-1935-5"); typo.lookUp(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(typo.ui.value.numberRefused); assertEquals("That number has a mistake in it. Check it against the letter.", typo.ui.value.error); assertNull(typo.ui.value.found)

    val unknown = RecordReplyViewModel(FakeRefs().apply { lookupError = AppError.NotFound("nope", "unknown") }, TestStrings())
    unknown.onNumber("4827-1935-6"); unknown.lookUp(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("That is not a number on a letter this group mailed. Check it against the letter.", unknown.ui.value.error)
  }

  @Test
  fun `a name is searched a moment after typing stops, from two characters`() = runTest {
    val refs = FakeRefs()
    val vm = RecordReplyViewModel(refs, TestStrings())
    vm.onName("j"); dispatcher.scheduler.advanceUntilIdle(); assertTrue(refs.searches.isEmpty())
    vm.onName("ji"); vm.onName("jim"); dispatcher.scheduler.advanceTimeBy(RecordReplyViewModel.SEARCH_DEBOUNCE_MS + 1); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("one search, for what was there when typing stopped", listOf("jim"), refs.searches)
    assertEquals("Jim H", vm.ui.value.matches?.single()?.matchedName); assertFalse(vm.ui.value.searching)
  }
}
