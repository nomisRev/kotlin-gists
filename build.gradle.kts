import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.21"
    id("app.cash.sqldelight") version "2.0.0-alpha02"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
    }
}

val arrow = "1.1.2"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.arrow-kt:arrow-core:$arrow")
    implementation("io.arrow-kt:arrow-optics:$arrow")
    implementation("io.arrow-kt:arrow-fx-coroutines:$arrow")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1")

    implementation("su.litvak.chromecast:api-v2:0.11.3")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.5.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.5.0")

//    implementation("app.cash.sqldelight:jdbc-driver:2.0.0-alpha02")
    implementation("org.jetbrains.exposed:exposed-core:0.37.3")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.37.3")
}
