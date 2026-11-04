import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.jijilabsdigital.swell.mailbox.MainKt")
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=40")
}

dependencies {
    implementation(project(":core")) // Mailbox.kt + Wire.kt only; no libsignal at runtime
}
