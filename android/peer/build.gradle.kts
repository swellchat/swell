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
    mainClass.set("com.jijilabsdigital.swell.peer.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation("org.signal:libsignal-client:0.86.5")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
}
