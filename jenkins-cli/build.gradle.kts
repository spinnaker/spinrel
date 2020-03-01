plugins {
    application
}

application {
    mainClassName = "io.spinnaker.spinrel.cli.jenkins.MainKt"
}

dependencies {
    val dagger_version = "2.26"

    kapt("com.google.dagger:dagger-compiler:$dagger_version")
    kaptTest("com.google.dagger:dagger-compiler:$dagger_version")

    implementation(project(":core"))
    implementation(project(":cli-shared"))
    implementation("com.github.ajalt:clikt:2.5.0")
    implementation("com.google.cloud:google-cloud-storage:1.103.0")
    implementation("com.google.dagger:dagger:$dagger_version")
    implementation("com.google.guava:guava:28.2-jre")
    implementation("io.github.microutils:kotlin-logging:1.7.8")
    implementation("org.apache.commons:commons-compress:1.20")

    runtimeOnly("org.slf4j:slf4j-simple:1.7.30")

    testImplementation("com.google.jimfs:jimfs:1.1")
    testImplementation("com.google.cloud:google-cloud-nio:0.120.0-alpha")
    testImplementation("io.mockk:mockk:1.9.3")
    testImplementation("io.strikt:strikt-core:0.24.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
}
