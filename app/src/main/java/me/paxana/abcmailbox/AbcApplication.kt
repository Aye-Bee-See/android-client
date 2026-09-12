package me.paxana.abcmailbox

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The process-wide entry point. Hilt generates the dependency graph rooted here;
 * `@HiltAndroidApp` is the only annotation the class needs.
 *
 * Backups are disabled in the manifest (`allowBackup=false`): the session token
 * and any cached key material must never leave the device through a cloud backup.
 */
@HiltAndroidApp
class AbcApplication : Application()
