// Explicit, because inside a build script `java` resolves to the Gradle extension, not the package.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing material, from `keystore.properties` locally or the environment in CI.
 *
 * Both are gitignored or ephemeral; nothing here is ever committed. When neither is present the
 * result is null and the release build simply goes unsigned, so cloning the repo and running
 * `assembleRelease` still works for anyone who is not cutting an official build.
 *
 * See docs/RELEASING.md for how to generate the keystore and populate the CI secrets.
 */
val releaseSigning: Map<String, String>? = run {
    val properties = rootProject.file("keystore.properties")
    val fromFile = if (properties.exists()) {
        Properties().apply { properties.inputStream().use(::load) }
            .let { loaded ->
                mapOf(
                    "storeFile" to loaded.getProperty("storeFile").orEmpty(),
                    "storePassword" to loaded.getProperty("storePassword").orEmpty(),
                    "keyAlias" to loaded.getProperty("keyAlias").orEmpty(),
                    "keyPassword" to loaded.getProperty("keyPassword").orEmpty(),
                )
            }
    } else {
        mapOf(
            "storeFile" to (System.getenv("KEYSTORE_FILE") ?: "keystore/release.jks"),
            "storePassword" to System.getenv("KEYSTORE_PASSWORD").orEmpty(),
            "keyAlias" to System.getenv("KEY_ALIAS").orEmpty(),
            "keyPassword" to System.getenv("KEY_PASSWORD").orEmpty(),
        )
    }
    // A half-populated config is worse than none: it fails deep inside the signing task with a
    // message that does not mention which value was missing.
    fromFile.takeIf { values ->
        values.values.none { it.isBlank() } && rootProject.file(values.getValue("storeFile")).exists()
    }
}

android {
    namespace = "dev.klaiber.cirrus"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.klaiber.cirrus"
        minSdk = 29
        targetSdk = 37
        versionCode = 7
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
                // v1 (JAR signing) is dead weight above minSdk 24. Both v2 and v3 are left on,
                // though at minSdk 29 apksigner emits v3 only — v3 has been understood since
                // API 28, so a v2 block would go unread.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
}
