plugins {
    application
}

application {
    mainClassName = "io.spinnaker.spinrel.cli.testing.MainKt"
}

dependencies {
    kapt("com.google.dagger:dagger-compiler")

    implementation(project(":core"))
    implementation(project(":cli-shared"))
    implementation("com.github.ajalt.clikt:clikt")
    implementation("com.google.dagger:dagger")
    implementation("io.github.microutils:kotlin-logging")

    runtimeOnly("org.slf4j:slf4j-simple")
}
