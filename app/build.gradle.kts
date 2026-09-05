import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing is optional and kept out of git: if keystore.properties
// is absent (e.g. on a fresh clone or in CI), the release build type simply
// stays unsigned rather than failing the build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val releaseKeystoreFile =
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(keystoreProperties::load)
        val missingProperties =
            listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
                .filter { keystoreProperties.getProperty(it).isNullOrBlank() }
        if (missingProperties.isNotEmpty()) {
            throw GradleException(
                "Invalid keystore.properties: missing ${missingProperties.joinToString()}. " +
                    "See DEVELOPER.md#signing-a-release-build-optional."
            )
        }

        rootProject.file(keystoreProperties.getProperty("storeFile")).also {
            if (!it.isFile) {
                throw GradleException(
                    "Release signing keystore does not exist: ${it.absolutePath}. " +
                        "Regenerate the local signing setup described in " +
                        "DEVELOPER.md#signing-a-release-build-optional."
                )
            }
        }
    } else {
        null
    }

android {
    namespace = "com.chmouel.liseur"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chmouel.liseur"
        minSdk = 26
        targetSdk = 37
        versionCode = 31
        versionName = "0.14.0"
    }

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v3 records the signing lineage, which is what makes
                // rotating this key possible later without breaking updates.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
        // A build that installs *beside* the real app rather than over
        // it. The debug build carries the production applicationId, so
        // pushing a work in progress to a phone replaces a library —
        // reading positions, highlights, notes — that cannot be rebuilt.
        // The suffix gives this one its own package name, and with it
        // its own database, DataStore and SAF grant, so the installed
        // app is never touched.
        //
        // Only the id and the label differ: it is the debug build in
        // every other respect, debug-signed and unminified, so what is
        // tested here behaves the way `make run` behaves on the
        // emulator. `release` is deliberately not its base — F-Droid
        // rebuilds that one byte for byte, and nothing here should be
        // able to reach it.
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by the Readium toolkit.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // The only native code in the app arrives prebuilt inside
            // library AARs. Left alone it is already stripped; letting
            // AGP strip it again ties the bytes to whichever NDK the
            // build machine happens to have, which is exactly the kind
            // of environment leak that breaks reproducible builds.
            keepDebugSymbols += "**/*.so"
        }
    }

    dependenciesInfo {
        // AGP's dependency manifest is encrypted with a Google key, so
        // nobody but Play can read it and F-Droid cannot reproduce it.
        // Dropping it keeps the APK byte-identical across rebuilds,
        // which is what lets F-Droid publish our own signed binary.
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            // The calibre-web clients log with android.util.Log, which is a
            // stub in android.jar; without this every call throws.
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all { test ->
                // The suites are independent (each brings its own
                // MockWebServer and in-memory Room), so run them across
                // the cores instead of one after another.
                test.maxParallelForks =
                    (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            }
        }
    }
}

// F-Droid's buildserver runs Gradle with auto-download of toolchains
// switched off, so asking for a toolchain it does not already have fails
// the build. Pinning the JVM target instead needs no toolchain lookup and
// matches the source/target compatibility set in compileOptions above, so
// the F-Droid recipe can build the app as-is rather than patching this
// file from a prebuild step.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The migration tests read the exported schemas through the asset
// manager, which under Robolectric means the variant's merged assets.
// Debug only: release builds must not carry a hundred kilobytes of JSON
// describing databases nobody will ever open.
android.sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.reorderable)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)

    // Already inside readium-navigator, which uses it to lift footnotes out
    // of a chapter. Declared here because the reader parses notes of its own
    // that Readium does not recognise, and a transitive dependency is not a
    // promise.
    implementation(libs.jsoup)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    // The app uses the platform's org.json; unit tests need a real implementation
    // of it because android.jar's is a stub. Test-only, never shipped.
    testImplementation(libs.json)
    // Lets the calibre-web clients be tested against a real socket, which is
    // the only way to cover redirects, failed logins and refused deletes.
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // Gives the sync coordinator's tests a clock they control, which is
    // the only way to pin down what happens when two requests overlap.
    testImplementation(libs.coroutines.test)
    // Runs the real Room database on the JVM, so the reading-position
    // state machine is tested by the same SQL that ships. CI runs unit
    // tests only, so an androidTest source set would never be exercised.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Replays every exported schema against the next migration, so an
    // upgrade cannot quietly drop a column full of reading positions.
    testImplementation(libs.room.testing)
}
