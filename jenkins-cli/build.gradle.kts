plugins {
    application
}

application {
    mainClassName = "io.spinnaker.spinrel.cli.jenkins.MainKt"
}

dependencies {
    kapt("com.google.dagger:dagger-compiler")
    kaptTest("com.google.dagger:dagger-compiler")

    implementation(kotlin("stdlib-jdk8"))
    implementation(project(":core"))
    implementation(project(":cli-shared"))
    implementation("com.github.ajalt:clikt")
    implementation("com.google.cloud:google-cloud-storage")
    implementation("com.google.dagger:dagger")
    implementation("com.google.guava:guava")
    implementation("io.github.microutils:kotlin-logging")
    implementation("org.eclipse.jgit:org.eclipse.jgit")
    implementation("org.kohsuke:github-api")

    runtimeOnly("org.slf4j:slf4j-simple")

    testImplementation("com.google.jimfs:jimfs")
    testImplementation("com.google.cloud:google-cloud-nio")
    testImplementation("com.squareup.okhttp3:mockwebserver")
    testImplementation("io.mockk:mockk")
    testImplementation("io.strikt:strikt-core")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}
