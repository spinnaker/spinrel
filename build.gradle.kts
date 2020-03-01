plugins {
    val kotlinVersion = "1.3.61"
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
            dependency("com.charleskorn.kaml:kaml:0.15.0")
            dependency("com.github.ajalt:clikt:2.5.0")
            dependencySet("com.google.dagger:2.26") {
                entry("dagger")
                entry("dagger-compiler")
            }
            dependency("com.google.jimfs:jimfs:1.1")
            dependency("com.google.cloud:google-cloud-nio:0.120.0-alpha")
            dependency("com.google.cloud:google-cloud-storage:1.103.0")
            dependency("io.github.microutils:kotlin-logging:1.7.8")
            dependency("io.mockk:mockk:1.9.3")
            dependency("org.apache.commons:commons-compress:1.20")
            dependency("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.14.0")
            dependencySet("org.slf4j:1.7.30") {
                entry("slf4j-simple")
            }
        }
        imports {
            mavenBom("com.google.guava:guava-bom:28.2-jre")
            mavenBom("io.strikt:strikt-bom:0.24.0")
            mavenBom("org.junit:junit-bom:5.6.0")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }
}
