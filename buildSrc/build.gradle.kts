plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

val batikVersion = "1.14"
val twelveMonkeysVersion = "3.7.0"

dependencies {
    implementation("org.apache.xmlgraphics", "batik-bridge", batikVersion)
    implementation("org.apache.xmlgraphics", "batik-codec", batikVersion)
    // For writing Windows ICO icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-bmp", twelveMonkeysVersion)
    // For writing macOS ICNS icon files:
    implementation("com.twelvemonkeys.imageio", "imageio-icns", twelveMonkeysVersion)
}
