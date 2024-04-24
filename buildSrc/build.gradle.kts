plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

val twelveMonkeysVersion = "3.9.4"

dependencies {
    implementation("org.eclipse.jgit", "org.eclipse.jgit", "6.6.0.202305301015-r")
    implementation("com.github.weisj", "jsvg", "1.4.0")
    // For writing Windows ICO icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-bmp", twelveMonkeysVersion)
    // For writing macOS ICNS icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-icns", twelveMonkeysVersion)
}
