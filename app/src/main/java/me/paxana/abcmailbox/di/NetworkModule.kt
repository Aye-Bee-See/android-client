package me.paxana.abcmailbox.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.BuildConfig
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.SessionInterceptor
import me.paxana.abcmailbox.data.session.SessionCache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * How the network stack is assembled. Hilt calls these once and shares the
 * results (`@Singleton`); anything with an `@Inject constructor` can then ask
 * for an [AuthApi] or a [Json] without knowing how they were built.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

  @Provides
  @Singleton
  fun json(): Json = Json {
    // The API may add fields; an unknown key must never crash a release build.
    ignoreUnknownKeys = true
    // A JSON null for a non-null Kotlin field with a default falls back to the default.
    coerceInputValues = true
    // Omit nulls when encoding, so optional request fields are simply absent.
    explicitNulls = false
  }

  @Provides
  @Singleton
  fun okHttp(cache: SessionCache): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor(SessionInterceptor(cache))
    .apply {
      if (BuildConfig.DEBUG) {
        // BASIC logs method, URL, and status; never headers (the token) or bodies (letters).
        addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
      }
    }
    .build()

  @Provides
  @Singleton
  fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
    .baseUrl(BuildConfig.API_BASE_URL)
    .client(client)
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()

  @Provides
  @Singleton
  fun authApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)
}
