plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

val twelveMonkeysVersion = "3.11.0"

dependencies {
    implementation("org.eclipse.jgit", "org.eclipse.jgit", "6.10.0.202406032230-r")
    implementation("com.github.weisj", "jsvg", "1.6.0")
    // For writing Windows ICO icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-bmp", twelveMonkeysVersion)
    // For writing macOS ICNS icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-icns", twelveMonkeysVersion)
}
