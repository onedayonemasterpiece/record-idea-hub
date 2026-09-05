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
        versionCode = 6
        versionName = "1.1.0-rc5-readback"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Preserve the installed RC4 package and its queue. This suffix does NOT establish
            // signing compatibility; the installed certificate must match for any update.
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
