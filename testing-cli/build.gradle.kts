plugins {
    application
}

application {
    mainClassName = "io.spinnaker.spinrel.cli.testing.MainKt"
}

dependencies {
    val dagger_version = "2.26"

    kapt("com.google.dagger:dagger-compiler:$dagger_version")

    implementation(project(":core"))
    implementation(project(":cli-shared"))
    implementation("com.github.ajalt:clikt:2.5.0")
    implementation("com.google.dagger:dagger:$dagger_version")

    runtimeOnly("org.slf4j:slf4j-simple:1.7.30")
}
