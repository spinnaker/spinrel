plugins {
    val kotlinVersion = "1.4.10"
    kotlin("jvm") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
    kotlin("kapt") version kotlinVersion apply false
    id("org.jlleitschuh.gradle.ktlint") version "9.4.1" apply false
    id("io.spring.dependency-management") version "1.0.10.RELEASE"
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
            dependency("com.charleskorn.kaml:kaml:0.25.0")
            dependency("com.github.ajalt.clikt:clikt:3.0.1")
            dependencySet("com.google.dagger:2.29.1") {
                entry("dagger")
                entry("dagger-compiler")
            }
            dependency("com.google.jimfs:jimfs:1.1")
            dependency("com.google.cloud:google-cloud-nio:0.121.2")
            dependency("com.google.cloud:google-cloud-storage:1.110.0")
            dependency("org.kohsuke:github-api:1.116")
            dependency("com.squareup.retrofit2:retrofit:2.9.0")
            dependency("io.github.microutils:kotlin-logging:2.0.3")
            dependency("io.mockk:mockk:1.10.2")
            dependency("org.apache.commons:commons-compress:1.20")
            dependency("org.eclipse.jgit:org.eclipse.jgit:5.9.0.202009080501-r")
            dependency("org.jetbrains.kotlinx:kotlinx-serialization-core:1.0.1")
            // Kotlinx serialization adds a transitive dependency on kotlin-reflect 1.3, so we need to pin this to a
            // newer version (even though we don't directly depend on it)
            dependency("org.jetbrains.kotlin:kotlin-reflect:1.4.10")
            dependency("org.slf4j:slf4j-simple:1.7.30")
        }
        imports {
            mavenBom("com.google.guava:guava-bom:30.0-jre")
            mavenBom("com.squareup.okhttp3:okhttp-bom:4.9.0")
            mavenBom("io.strikt:strikt-bom:0.28.0")
            mavenBom("org.junit:junit-bom:5.7.0")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        // The Jenkins server on which this runs only has Java 8 installed.
        kotlinOptions.jvmTarget = "1.8"
    }
}
