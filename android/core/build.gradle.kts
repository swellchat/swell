import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Android gets libsignal through libsignal-android; the JVM peer through libsignal-client.
    compileOnly("org.signal:libsignal-client:0.86.5")
    // Tor control (client auth, ADD_ONION); the app gets it through tor-android, the peer directly.
    compileOnly("info.guardianproject:jtorctl:0.4.5.7")
    testImplementation("org.signal:libsignal-client:0.86.5")
    testImplementation(kotlin("test-junit"))
}
