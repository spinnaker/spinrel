import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "1.3.61"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    kotlin("kapt") version kotlinVersion
    application
    id("org.jlleitschuh.gradle.ktlint") version "9.1.1"
}

repositories {
    mavenCentral()
    jcenter()
}
dependencies {
    val dagger_version = "2.25.4"

    kapt("com.google.dagger:dagger-compiler:$dagger_version")
    kaptTest("com.google.dagger:dagger-compiler:$dagger_version")

    implementation(kotlin("stdlib-jdk8"))
    implementation("com.charleskorn.kaml:kaml:0.15.0")
    implementation("com.github.ajalt:clikt:2.5.0")
    implementation("com.google.cloud:google-cloud-storage:1.103.0")
    implementation("com.google.dagger:dagger:$dagger_version")
    implementation("com.google.guava:guava:28.2-jre")
    implementation("io.github.microutils:kotlin-logging:1.7.8")
    implementation("org.apache.commons:commons-compress:1.19")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.14.0")

    runtimeOnly("org.slf4j:slf4j-simple:1.7.30")

    testImplementation("com.google.jimfs:jimfs:1.1")
    testImplementation("com.google.cloud:google-cloud-nio:0.120.0-alpha")
    testImplementation("io.mockk:mockk:1.9.3")
    testImplementation("io.strikt:strikt-core:0.23.4")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.5.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.5.2")
}

application {
    mainClassName = "org.spinnaker.spinrel.MainKt"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
