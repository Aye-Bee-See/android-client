package me.paxana.abcmailbox.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.paxana.abcmailbox.data.crypto.CryptoEngine
import me.paxana.abcmailbox.data.crypto.DefaultEncryptionModeRepository
import me.paxana.abcmailbox.data.crypto.DefaultKeyVault
import me.paxana.abcmailbox.data.crypto.EncryptionModeRepository
import me.paxana.abcmailbox.data.crypto.KeyVault
import me.paxana.abcmailbox.data.crypto.SodiumCryptoEngine
import me.paxana.abcmailbox.data.db.AppDatabase
import me.paxana.abcmailbox.data.files.LocalFiles
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.db.DraftDao
import me.paxana.abcmailbox.data.repo.DefaultDirectoryRepository
import me.paxana.abcmailbox.data.repo.DefaultDraftsRepository
import me.paxana.abcmailbox.data.repo.DefaultGroupRepository
import me.paxana.abcmailbox.data.repo.GroupRepository
import me.paxana.abcmailbox.data.repo.DefaultLettersRepository
import me.paxana.abcmailbox.data.repo.DraftsRepository
import me.paxana.abcmailbox.data.repo.LettersRepository
import me.paxana.abcmailbox.data.repo.DirectoryRepository
import me.paxana.abcmailbox.data.session.DefaultSessionRepository
import me.paxana.abcmailbox.data.session.KeystoreSecretCipher
import me.paxana.abcmailbox.data.session.SecretCipher
import me.paxana.abcmailbox.data.session.DataStoreSessionStore
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionStore
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

  @Provides
  @Singleton
  fun database(@ApplicationContext context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "abcmailbox.db").build()

  @Provides
  fun draftDao(db: AppDatabase): DraftDao = db.drafts()
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

  @Binds
  abstract fun lettersRepository(impl: DefaultLettersRepository): LettersRepository

  @Binds
  abstract fun groupRepository(impl: DefaultGroupRepository): GroupRepository

  @Binds
  abstract fun draftsRepository(impl: DefaultDraftsRepository): DraftsRepository

  @Binds
  abstract fun localFiles(impl: LocalFiles): LocalFilesContract

  @Binds
  abstract fun sessionStore(impl: DataStoreSessionStore): SessionStore

  @Binds
  abstract fun encryptionMode(impl: DefaultEncryptionModeRepository): EncryptionModeRepository

  @Binds
  abstract fun keyVault(impl: DefaultKeyVault): KeyVault

  @Binds
  abstract fun cryptoEngine(impl: SodiumCryptoEngine): CryptoEngine
}
