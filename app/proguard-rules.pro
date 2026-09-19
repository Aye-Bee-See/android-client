# R8 keep rules. Most libraries here (Retrofit, OkHttp, kotlinx.serialization, Room, Hilt, Compose)
# ship their own rules inside their artifacts; only what they cannot know about is listed.

# libsodium through JNA. JNA finds native functions by the *names* of the methods on a Kotlin/Java
# interface, and reads structure fields by reflection, so none of it may be renamed or removed.
# Without these, encryption fails at runtime in a release build and works in debug: the worst kind of bug.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keep class com.ionspin.kotlin.crypto.** { *; }
# JNA's desktop-only parts refer to AWT, which Android does not have.
-dontwarn java.awt.**

# Navigation's type-safe routes are @Serializable classes looked up by name when a back stack is restored.
-keep class me.paxana.abcmailbox.ui.nav.** { *; }

# WorkManager remembers a queued job by its worker's class name, in its own database, across app updates.
# If R8 gave the class a different short name in the next release, a letter queued by the old version
# would have nothing to send it. The name stays as written.
-keepnames class me.paxana.abcmailbox.data.outbox.OutboxWorker

# Readable stack traces from the field: keep line numbers, hide original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
