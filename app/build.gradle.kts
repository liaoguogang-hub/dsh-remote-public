plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Single source of truth for the version. Bump this and nothing else:
//   * the in-APK versionName (shown in Android's app info and in the app's settings sheet)
//   * the dist artifact name (DSH-Remote-v<version>.apk)
// stay in sync by construction —the copy task below derives the file name from it.
val appVersion = "4.18.0"
val appVersionCode = 67

android {
    // The R class lives in `com.dsh.remote`; the applicationId below is what makes this build
    // installable next to the self-use build (same package name would replace it).
    namespace = "com.dsh.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.remote.ngrok"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-sign so the artifact is directly installable without a keystore setup.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // QR pairing. ZXing is used instead of ML Kit on purpose: the bundled ML Kit scanner needs
    // Google Play services, and Huawei/HMS-only phones (a primary target here) have none.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// Publish the release APK under a name derived from versionName, so the artifact can never
// drift from the version the app reports. Runs automatically after every release build.
// The name uses hyphens only: GitHub rewrites spaces in release asset names to dots, which
// makes a published download link silently 404.
val distApk = tasks.register<Copy>("distApk") {
    group = "distribution"
    description = "Copies the release APK to dist/DSH-Remote-v$appVersion.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.file("dist"))
    rename { "DSH-Remote-v$appVersion.apk" }
}

// AGP creates the variant lifecycle tasks late, so the hook is registered in afterEvaluate.
afterEvaluate {
    tasks.named("assembleRelease").configure {
        finalizedBy(distApk)
    }
}





















