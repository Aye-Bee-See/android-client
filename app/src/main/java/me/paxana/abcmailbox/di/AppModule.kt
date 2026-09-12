package me.paxana.abcmailbox.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.paxana.abcmailbox.data.repo.DefaultDirectoryRepository
import me.paxana.abcmailbox.data.repo.DirectoryRepository
import me.paxana.abcmailbox.data.session.DefaultSessionRepository
import me.paxana.abcmailbox.data.session.KeystoreSecretCipher
import me.paxana.abcmailbox.data.session.SecretCipher
import me.paxana.abcmailbox.data.session.SessionRepository
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the coroutine scope that lives as long as the process. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

  @Provides
  @Singleton
  @ApplicationScope
  fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Provides
  @Singleton
  fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("abcmailbox") }
}

/** Interface-to-implementation bindings; `@Binds` generates no code beyond the mapping. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
  @Binds
  abstract fun secretCipher(impl: KeystoreSecretCipher): SecretCipher

  @Binds
  abstract fun sessionRepository(impl: DefaultSessionRepository): SessionRepository

  @Binds
  abstract fun directoryRepository(impl: DefaultDirectoryRepository): DirectoryRepository
}
