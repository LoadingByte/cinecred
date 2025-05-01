plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

val twelveMonkeysVersion = "3.12.0"

dependencies {
    implementation("org.eclipse.jgit", "org.eclipse.jgit", "7.2.0.202503040940-r")
    implementation("com.github.weisj", "jsvg", "2.0.0")
    // For writing Windows ICO icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-bmp", twelveMonkeysVersion)
    // For writing macOS ICNS icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-icns", twelveMonkeysVersion)
}
