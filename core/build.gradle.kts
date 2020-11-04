dependencies {
    kapt("com.google.dagger:dagger-compiler")
    kaptTest("com.google.dagger:dagger-compiler")

    // Everything provided by a Dagger module is part of our API
    api("com.squareup.okhttp3:okhttp")

    implementation(kotlin("stdlib-jdk8"))
    implementation("com.charleskorn.kaml:kaml")
    implementation("com.google.cloud:google-cloud-storage")
    implementation("com.google.dagger:dagger")
    implementation("com.google.guava:guava")
    implementation("com.squareup.okhttp3:logging-interceptor")
    implementation("com.squareup.retrofit2:retrofit")
    implementation("io.github.microutils:kotlin-logging")
    implementation("org.apache.commons:commons-compress")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")

    testImplementation("com.google.cloud:google-cloud-nio")
    testImplementation("com.google.jimfs:jimfs")
    testImplementation("com.squareup.okhttp3:mockwebserver")
    testImplementation("io.mockk:mockk")
    testImplementation("io.strikt:strikt-core")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}
