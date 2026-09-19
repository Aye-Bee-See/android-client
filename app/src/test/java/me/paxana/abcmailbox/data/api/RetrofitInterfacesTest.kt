package me.paxana.abcmailbox.data.api

import me.paxana.abcmailbox.di.NetworkModule
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Retrofit reads an interface's annotations only when a method is first called, so a method that lost
 * its `@POST`, or has two `@Body` parameters, compiles and then fails in front of a user. `validateEagerly`
 * makes Retrofit check every method when the interface is created; this test creates them all.
 */
class RetrofitInterfacesTest {
  @Test
  fun `every method of every API interface is one Retrofit can build`() {
    val retrofit = Retrofit.Builder().baseUrl("http://localhost/")
      .addConverterFactory(NetworkModule.json().asConverterFactory("application/json".toMediaType()))
      .validateEagerly(true).build()
    listOf(AuthApi::class, DirectoryApi::class, DirectorySyncApi::class, GroupApi::class, HealthApi::class, LettersApi::class, NotificationsApi::class)
      .forEach { retrofit.create(it.java) }
  }
}
