import com.android.tools.r8.internal.im

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Release reads the deployed caption service URL from -PcaptionServiceUrl or local.properties,
// so the Cloud Run URL stays out of source control.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseCaptionServiceUrl: String = providers.gradleProperty("captionServiceUrl").orNull
    ?: localProperties.getProperty("captionServiceUrl")
    ?: ""

android {
    namespace = "com.ps.stashit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ps.stashit"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 10.0.2.2 is the host machine as seen from the Android emulator.
            buildConfigField("String", "CAPTION_SERVICE_BASE_URL", "\"http://10.0.2.2:8000/\"")
        }
        release {
            buildConfigField("String", "CAPTION_SERVICE_BASE_URL", "\"$releaseCaptionServiceUrl\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    androidResources {
        noCompress.addAll(listOf("tflite", "task", "bin"))
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Only packaging needs the real URL; release unit tests and lint build without it.
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }.configureEach {
    val url = releaseCaptionServiceUrl
    doFirst {
        require(url.startsWith("https://") && url.endsWith("/")) {
            "Set captionServiceUrl (https://..., ending in '/') in local.properties or pass -PcaptionServiceUrl"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.material.icon)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.http)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson.convertor)
    implementation(libs.retrofit.logging.interceptor)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // room-testing's schema reader needs 1.8.x; lifecycle alone resolves 1.7.3, and AGP pins the test APK to the app's version.
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.viewmodel)

    implementation(libs.media.pipe.task.text)
    implementation(libs.media.pipe.task.genai)
    implementation(libs.local.agent.rag)

    implementation(libs.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}