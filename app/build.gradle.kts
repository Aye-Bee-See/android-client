import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
  alias(libs.plugins.room)
}

android {
  namespace = "me.paxana.abcmailbox"
  compileSdk = 37

  defaultConfig {
    applicationId = "me.paxana.abcmailbox"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Release signing reads an untracked file (see keystore.properties.example) or, on CI, environment
  // variables. Neither the keystore nor its passwords ever belong in the repository. Without them the
  // release build still assembles, unsigned, which is enough to check that it builds and shrinks.
  val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
  }
  fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
  val releaseStoreFile = signingValue("storeFile", "ABC_KEYSTORE_FILE")

  signingConfigs {
    if (releaseStoreFile != null) create("release") {
      storeFile = rootProject.file(releaseStoreFile)
      storePassword = signingValue("storePassword", "ABC_KEYSTORE_PASSWORD")
      keyAlias = signingValue("keyAlias", "ABC_KEY_ALIAS")
      keyPassword = signingValue("keyPassword", "ABC_KEY_PASSWORD")
    }
  }

  buildTypes {
    debug {
      // 10.0.2.2 is the emulator's alias for the host machine's localhost.
      buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3000/\"")
      // The hidden server dialog on the Account tab (five taps on the build line).
      buildConfigField("boolean", "DEV_TOOLS", "true")
    }
    release {
      // R8 removes unused code, renames what is left, and optimises; resource shrinking then drops
      // resources nothing refers to. Keep rules for the reflective parts are in proguard-rules.pro.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Placeholder until there is a deployed API; override with -PapiBaseUrl=https://… for a real one.
      val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: "https://api.abcmailbox.net/"
      buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
      buildConfigField("boolean", "DEV_TOOLS", "false")
      signingConfig = signingConfigs.findByName("release")
    }
    // What testers get before there is a domain: shrunk and optimised exactly like release (so it
    // proves the release build works), but with the developer tools of debug, plain HTTP allowed so
    // it can reach a laptop on the same Wi-Fi, and its own application id so it installs beside the others.
    create("internal") {
      initWith(getByName("release"))
      applicationIdSuffix = ".internal"
      versionNameSuffix = "-internal"
      buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3000/\"")
      buildConfigField("boolean", "DEV_TOOLS", "true")
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += "release"
    }
  }

  // The internal build shares debug's resources (the network security config that allows plain HTTP)
  // rather than keeping a copy that could drift.
  sourceSets.getByName("internal").res.srcDir("src/debug/res")

  // Processor architectures that Android 8+ devices and emulators actually have. A library ships
  // native code for mips and armeabi too, which no supported device can run.
  defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  // Android 13+ lets people choose a language per app (Settings > System > Languages > App languages). The
  // system needs a list of what the app speaks; this generates it from the values-xx folders, using
  // src/main/res/resources.properties to know which language the unqualified `values` folder is.
  androidResources { generateLocaleConfig = true }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

room {
  // Room writes a JSON description of each schema version here; commit them so
  // migrations can be tested against real history.
  schemaDirectory("$projectDir/schemas")
}

dependencies {
  implementation(project(":crypto"))

  // Compose UI
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // AndroidX
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.paging.runtime)
  implementation(libs.androidx.paging.compose)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.hilt.work)
  ksp(libs.androidx.hilt.compiler)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.biometric)

  // Dependency injection
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.hilt.navigation.compose)

  // Networking, serialization, images
  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.kotlinx.serialization)
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)

  // Unit tests (run on the JVM)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.turbine)
  testImplementation(libs.okhttp.mockwebserver)

  // Instrumented tests (run on a device or emulator)
  androidTestImplementation(libs.androidx.junit)
  // MigrationTestHelper: opens the database at an old version from the exported schema files, then upgrades it.
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
