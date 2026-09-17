// Pure JVM Kotlin module. It has no Android dependency on purpose: every
// cryptographic operation must be testable on the development machine, and
// keeping android.* imports out of this module makes that a compile-time rule.
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  api(libs.libsodium.bindings)
  // Sodium.deriveKey calls the binding's JNA interface directly (see the comment there), which needs
  // JNA's types at compile time only; at runtime the binding brings the right JNA for each platform.
  compileOnly(libs.jna)
  testImplementation(libs.jna)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
