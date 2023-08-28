plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

val batikVersion = "1.16"
val twelveMonkeysVersion = "3.9.4"

dependencies {
    implementation("org.eclipse.jgit", "org.eclipse.jgit", "6.6.0.202305301015-r")
    implementation("org.apache.xmlgraphics", "batik-bridge", batikVersion)
    implementation("org.apache.xmlgraphics", "batik-codec", batikVersion)
    // For writing Windows ICO icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-bmp", twelveMonkeysVersion)
    // For writing macOS ICNS icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-icns", twelveMonkeysVersion)
}
