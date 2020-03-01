dependencies {
    val dagger_version = "2.26"

    kapt("com.google.dagger:dagger-compiler:$dagger_version")

    implementation(kotlin("stdlib-jdk8"))
    implementation("com.charleskorn.kaml:kaml:0.15.0")
    implementation("com.google.cloud:google-cloud-storage:1.103.0")
    implementation("com.google.dagger:dagger:$dagger_version")
    implementation("com.google.guava:guava:28.2-jre")
    implementation("io.github.microutils:kotlin-logging:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.14.0")

    testImplementation("com.google.jimfs:jimfs:1.1")
    testImplementation("io.strikt:strikt-core:0.24.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
}
