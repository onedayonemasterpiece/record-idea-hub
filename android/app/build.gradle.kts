plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.onedayonemasterpiece.recordideahub"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onedayonemasterpiece.recordideahub"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.1.0-rc4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // GitHub-hosted runners generate a different default debug certificate per run.
            // A distinct beta package lets OpenCode install Android 1.1 alongside the working
            // v1 APK without uninstalling it or risking the urgent review workflow.
            applicationIdSuffix = ".v11"
            // Evidence builds must not invent another incompatible Android debug identity.
            if (providers.gradleProperty("unsignedEvidence").orNull == "true") {
                signingConfig = null
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.cloudflare.realtimekit.android-vad:webrtc:2.0.10-cf.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

kotlin {
    jvmToolchain(17)
}
