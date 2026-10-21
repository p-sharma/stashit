import com.android.tools.r8.internal.im

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

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
        release {
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

    androidResources {
        noCompress.addAll(listOf("tflite", "task", "bin"))
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}