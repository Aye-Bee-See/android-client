# Decisions

Short records of choices that are not obvious from the code. Newest first.

## 2026-09-12: minSdk 26

**Context.** The scaffold chose 25 (Android 7.1). API 26 adds `java.time` and `java.util.Base64`, both used by the session and crypto code; below 26 they need backports or `android.util` equivalents.

**Decision.** `minSdk = 26`. Devices below Android 8.0 are a negligible share in 2026, and the libsodium binding's own floor is not lower.

**Consequences.** None expected. Revisit only if a partner group reports users on older phones.

## 2026-09-12: ViewModels depend on a `SessionRepository` interface

**Context.** The first draft injected the concrete repository into `LoginViewModel`, which made the ViewModel untestable without DataStore, Retrofit, and the Keystore.

**Decision.** A small interface (`state`, `login`, `logout`) with `DefaultSessionRepository` bound in Hilt. Tests use a scripted fake. The same shape will be used for every repository that a ViewModel depends on.

## 2026-09-12: build on AGP 9.3.2 / Gradle 9.7 / Kotlin 2.4.20, opting out of AGP 9's built-in Kotlin

**Context.** The scaffold shipped with AGP 8.11.2, the newest version the installed Android Studio (Narwhal 2025.1.1, build 251) can open. Every attempt to keep that ceiling failed in turn: KSP 2.3.x requires AGP 8.12; Hilt 2.59.1+ requires AGP 9.0 and Hilt 2.59 references an AGP class that 8.11 lacks; and finally the current AndroidX core (1.19.0) requires AGP 9.1 while OkHttp 5.5 requires compileSdk 37. Staying on AGP 8 would have meant pinning nearly every library to mid-2025 releases.

**Decision.** AGP 9.3.2 (the newest inside Kotlin 2.4.20's stated range), Gradle 9.7.0, Kotlin 2.4.20, KSP 2.3.12 (built against Kotlin 2.3.20; no KSP exists for 2.4 yet, and it works), Hilt 2.60.1, compileSdk 37. AGP downloaded the Android 37 platform and Build-Tools 36 itself on first build, because the SDK licence files were already accepted.

`gradle.properties` sets `android.builtInKotlin=false` and `android.newDsl=false`. AGP 9 bundles its own Kotlin compiler and a reworked DSL; the opt-out keeps the conventional `kotlin-android` plugin and the `android { }` block every reference still shows. AGP 9.4 still honours the opt-out. Migrating to built-in Kotlin is a separate, deliberate task.

**Consequences.** The project cannot be opened in Android Studio Narwhal 2025.1.1; it needs a 2026 release (the IDE will say so and offer the update). Command-line builds are unaffected. When Studio is updated, revisit AGP 9.4 and the built-in Kotlin migration together.

**Rejected.** Pinning to AGP 8.11 (see context). Adopting built-in Kotlin immediately (unfamiliar DSL for a returning developer, and compiler-plugin support for serialization and Compose under it was not verified).

## 2026-09-12: libsodium binding is `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings`

**Context.** The API's end-to-end mode uses libsodium primitives (X25519 sealed boxes, XChaCha20-Poly1305 AEAD, Argon2id). The client must use the same library family so ciphertext interoperates with the web client and the server's migration tooling. Google Play requires native libraries in apps targeting Android 15+ to be aligned to 16 KB pages.

**Measured.** `llvm-readelf -l` on the `.so` files inside each AAR, looking at the `LOAD` segment alignment:

| Artifact | Version | Released | arm64-v8a | armeabi-v7a | x86 | x86_64 |
| --- | --- | --- | --- | --- | --- | --- |
| `com.goterl:lazysodium-android` | 5.1.0 | 2022 | 0x1000 | 0x1000 | 0x1000 | 0x1000 |
| `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings-android` | 0.9.5 | 2025-11-22 | 0x4000 | 0x4000 | 0x4000 | 0x4000 |

0x1000 is 4 KB and fails the requirement; 0x4000 is 16 KB and passes.

**Decision.** Use the ionspin bindings. They load libsodium through JNA, expose the functions we need by their libsodium names, and publish a `-jvm` artifact, so the crypto module's tests run on the development machine without an emulator.

**Consequences.** JNA adds a small native dependency of its own (also 16 KB aligned in current releases; verify when bumping). The library's API is a Kotlin `LibsodiumInterface`; wrap it behind our own `Sodium` interface so a future swap (for example an NDK build with a thin JNI layer, the fallback) touches one file.

**Rejected.** lazysodium-android (alignment, no release since 2022). Building libsodium with the NDK ourselves (a day of work, and a build to maintain, for no gain while a maintained binding exists). BouncyCastle (has X25519 and ChaCha20-Poly1305 but no XChaCha20 or sealed boxes, so we would be assembling primitives by hand).
