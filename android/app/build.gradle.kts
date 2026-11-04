plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jijilabsdigital.swell"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jijilabsdigital.swell"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        // Jiji Labs mailbox (onion only). Set after deploy; users can change it in a later version.
        buildConfigField("String", "MAILBOX_ONION", "\"${project.findProperty("swell.mailbox") ?: ""}\"")
        // Report inbox "<mailbox onion>:<queue id hex>:<x25519 public key hex>" (tools: peer report-setup).
        buildConfigField("String", "REPORT_INBOX", "\"${project.findProperty("swell.reportInbox") ?: ""}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 shrinks the app's own code; native-backed libraries are kept whole (proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // libsignal-client ships desktop natives as jar resources; Android uses the AAR's jniLibs.
        jniLibs.excludes += setOf("**/libsignal_jni_testing.so")
        resources.excludes += setOf("libsignal_jni*.dylib", "libsignal_jni*.so", "signal_jni*.dll")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":core"))
    implementation("org.signal:libsignal-android:0.86.5")
    implementation("info.guardianproject:tor-android:0.4.9.14")
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("com.google.zxing:core:3.5.3")
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric pulls fragment 1.2.x, whose FragmentActivity breaks activity-result launchers (16-bit requestCode crash).
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.material:material-icons-extended")
}
