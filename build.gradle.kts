plugins {
    val kotlinVersion = "1.3.72"
    kotlin("jvm") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
    kotlin("kapt") version kotlinVersion apply false
    id("org.jlleitschuh.gradle.ktlint") version "9.2.1" apply false
    id("io.spring.dependency-management") version "1.0.9.RELEASE"
}

subprojects {
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.kapt")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    repositories {
        mavenCentral()
        jcenter()
    }

    dependencyManagement {
        dependencies {
            dependency("com.charleskorn.kaml:kaml:0.17.0")
            dependency("com.github.ajalt:clikt:2.6.0")
            dependencySet("com.google.dagger:2.27") {
                entry("dagger")
                entry("dagger-compiler")
            }
            dependency("com.google.jimfs:jimfs:1.1")
            dependency("com.google.cloud:google-cloud-nio:0.120.0-alpha")
            dependency("com.google.cloud:google-cloud-storage:1.107.0")
            dependency("com.squareup.retrofit2:retrofit:2.8.1")
            dependency("io.github.microutils:kotlin-logging:1.7.9")
            dependency("io.mockk:mockk:1.10.0")
            dependency("org.apache.commons:commons-compress:1.20")
            dependency("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.20.0")
            dependency("org.slf4j:slf4j-simple:1.7.30")
        }
        imports {
            mavenBom("com.google.guava:guava-bom:29.0-jre")
            mavenBom("com.squareup.okhttp3:okhttp-bom:4.5.0")
            mavenBom("io.strikt:strikt-bom:0.25.0")
            mavenBom("org.junit:junit-bom:5.6.2")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }
}
