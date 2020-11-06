dependencies {

    kapt("com.google.dagger:dagger-compiler")

    implementation(project(":core"))
    implementation("com.github.ajalt.clikt:clikt")
    implementation("com.google.dagger:dagger")
}
