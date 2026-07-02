plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nsn8.vued"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.nsn8.vued"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SENTRY_DSN", "\"${System.getenv("VUED_HOST_SENTRY_DSN") ?: "https://cdd4abcaf4d2dc9696e575a51be4b59b@o4511363432841216.ingest.us.sentry.io/4511664076947456"}\"")
        buildConfigField("String", "SENTRY_ENVIRONMENT", "\"${System.getenv("SENTRY_ENVIRONMENT") ?: System.getenv("VUED_ENVIRONMENT") ?: "development"}\"")
        buildConfigField("String", "SENTRY_RELEASE", "\"${System.getenv("SENTRY_RELEASE") ?: "vued-host@${versionName}+${versionCode}"}\"")
        buildConfigField("Boolean", "SENTRY_TEST_ERROR", "${System.getenv("VUED_HOST_SENTRY_TEST_ERROR") == "1"}")
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.sentry.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
