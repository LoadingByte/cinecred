import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.ext.awt.image.GraphicsUtil
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.tools.ant.filters.ReplaceTokens
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream


buildscript {
    val batikVersion = "1.14"
    val twelveMonkeysVersion = "3.7.0"
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.apache.xmlgraphics", "batik-bridge", batikVersion)
        classpath("org.apache.xmlgraphics", "batik-codec", batikVersion)
        // For writing Windows ICO icon files:
        classpath("com.twelvemonkeys.imageio", "imageio-bmp", twelveMonkeysVersion)
        // For writing macOS ICNS icon files:
        classpath("com.twelvemonkeys.imageio", "imageio-icns", twelveMonkeysVersion)
    }
}

plugins {
    kotlin("jvm") version "1.5.30"
}

group = "com.loadingbyte"
version = "1.3.0-SNAPSHOT"

val slf4jVersion = "1.7.32"
val poiVersion = "5.2.2"
val batikVersion = "1.14"
val javacppVersion = "1.5.6"
val ffmpegVersion = "4.4-$javacppVersion"

enum class Platform(val label: String, val slug: String) {
    WINDOWS("Windows", "windows-x86_64"),
    MAC_OS("MacOS", "macosx-x86_64"),
    LINUX("Linux", "linux-x86_64")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx", "kotlinx-collections-immutable", "0.3.4")

    // Log to java.util.logging
    implementation("org.slf4j", "slf4j-jdk14", slf4jVersion)
    // Redirect other logging frameworks to slf4j.
    // Batik & PDFBox use Jakarta Commons Logging. POI uses log4j2.
    implementation("org.slf4j", "jcl-over-slf4j", slf4jVersion)
    implementation("org.apache.logging.log4j", "log4j-to-slf4j", "2.17.2")

    // Spreadsheet Reading and Writing
    implementation("org.apache.poi", "poi", poiVersion)
    implementation("org.apache.poi", "poi-ooxml", poiVersion)
    implementation("com.github.miachm.sods", "SODS", "1.4.0")
    implementation("org.apache.commons", "commons-csv", "1.9.0")

    // SVG Reading and Writing
    implementation("org.apache.xmlgraphics", "batik-bridge", batikVersion)
    implementation("org.apache.xmlgraphics", "batik-svggen", batikVersion)
    // For pictures embedded in the SVG:
    implementation("org.apache.xmlgraphics", "batik-codec", batikVersion)

    // PDF Reading and Writing
    implementation("org.apache.pdfbox", "pdfbox", "2.0.24")
    implementation("de.rototor.pdfbox", "graphics2d", "0.33")

    // Video Encoding
    implementation("org.bytedeco", "javacpp", javacppVersion)
    implementation("org.bytedeco", "ffmpeg", ffmpegVersion)
    for (platform in Platform.values()) {
        implementation("org.bytedeco", "javacpp", javacppVersion, classifier = platform.slug)
        implementation("org.bytedeco", "ffmpeg", ffmpegVersion, classifier = "${platform.slug}-gpl")
    }

    // UI
    implementation("com.miglayout", "miglayout-swing", "11.0")
    implementation("com.formdev", "flatlaf", "2.1")
    implementation("org.commonmark", "commonmark", "0.19.0")

    // Testing
    testImplementation("org.junit.jupiter", "junit-jupiter", "5.8.1")
}

configurations.all {
    // POI:
    // We don't re-evaluate formulas, and as only that code calls Commons Math, we can omit the dependency.
    exclude("org.apache.commons", "commons-math3")
    // This is only required for adding pictures to workbooks via code, which we don't do.
    exclude("commons-codec", "commons-codec")

    // Batik & PDFBox: We replace this commons-logging dependency by the slf4j bridge.
    exclude("commons-logging", "commons-logging")

    // Batik:
    // The Java XML APIs are part of the JDK itself since Java 5.
    exclude("xml-apis", "xml-apis")
    // Batik functions well without the big xalan dependency.
    exclude("xalan", "xalan")
}

tasks.withType<JavaCompile> {
    options.release.set(17)
    options.compilerArgs = listOf("--add-modules", "jdk.incubator.foreign")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "16"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.processResources {
    from("CHANGELOG.md")
    into("licenses") {
        from("LICENSE")
        rename("LICENSE", "Cinecred-LICENSE")
    }
    doLast {
        destinationDir.resolve("version").writeText(version.toString())
    }
}


// Cinecred implements reflective code which accesses these packages.
val addOpens = listOf("java.base/java.lang", "java.desktop/java.awt.font", "java.desktop/sun.font")

// Build the universal JAR containing natives for all supported platforms.
val buildUniversalJar by tasks.registering(Jar::class) {
    group = "Build"
    archiveClassifier.set("universal")
    makeFatJar(Platform.WINDOWS, Platform.MAC_OS, Platform.LINUX)
}


// For each platform, build a JAR containing only natives for that platform.
val buildPlatformJarTasks = Platform.values().asSequence().associateWith { platform ->
    task<Jar>("build${platform.label}Jar") {
        group = "Build"
        archiveClassifier.set(platform.slug)
        makeFatJar(platform)
    }
}


val preparePackagingTasks = Platform.values().map { platform ->
    val pkgDir = buildDir.resolve("packaging").resolve(platform.slug)
    val platformJar = buildPlatformJarTasks[platform]!!
    // Copy all existing files needed for packaging into the packaging folder.
    val copyFiles = task<Copy>("copyFiles${platform.label}") {
        group = "Packaging Preparation"
        dependsOn("processResources")
        dependsOn(platformJar)
        into(pkgDir)
        // Copy the packaging scripts "filter" (fill in) some variables. Note that we
        // don't select the scripts by platform here because that's not worth the effort.
        from("packaging") {
            val tokens = mapOf(
                "VERSION" to version,
                "MAIN_JAR" to platformJar.archiveFileName.get(),
                "JAVA_OPTIONS" to "--add-modules jdk.incubator.foreign --enable-native-access=ALL-UNNAMED " +
                        addOpens.joinToString(" ") { "--add-opens $it=ALL-UNNAMED" },
                "DESCRIPTION" to "Create film credits -- without pain",
                "DESCRIPTION_DE" to "Filmabspänne schmerzfrei erstellen",
                "URL" to "https://loadingbyte.com/cinecred/",
                "VENDOR" to "Felix Mujkanovic",
                "EMAIL" to "hello@loadingbyte.com",
                "LEGAL_PATH_RUNTIME" to mapOf(
                    Platform.WINDOWS to "runtime\\legal",
                    Platform.MAC_OS to "runtime/Contents/Home/legal",
                    Platform.LINUX to "lib/runtime/legal"
                )[platform],
                "LEGAL_PATH_APP" to mapOf(
                    Platform.WINDOWS to "app\\${platformJar.archiveFileName.get()}",
                    Platform.MAC_OS to "app/${platformJar.archiveFileName.get()}",
                    Platform.LINUX to "lib/app/${platformJar.archiveFileName.get()}"
                )[platform]
            )
            filter<ReplaceTokens>("tokens" to tokens)
        }
        into("app") {
            from(platformJar)
            from(sourceSets.main.get().output.resourcesDir!!.resolve("splash.png"))
        }
    }
    // Transcode the logo SVG to the platform-specific icon image format and put it into the packaging folder.
    // For Windows and macOS, additionally create the appropriate installer background images.
    val drawImages = task("drawImages${platform.label}") {
        group = "Packaging Preparation"
        dependsOn("processResources")
        doLast {
            when (platform) {
                Platform.WINDOWS -> {
                    transcodeLogo(pkgDir.resolve("images/icon.ico"), intArrayOf(16, 20, 24, 32, 40, 48, 64, 256))
                    buildImage(pkgDir.resolve("images/banner.bmp"), 493, 58, BufferedImage.TYPE_INT_RGB) { g2 ->
                        g2.color = Color.WHITE
                        g2.fillRect(0, 0, 493, 58)
                        g2.drawImage(rasterizeLogo(48), 438, 5, null)
                    }
                    buildImage(pkgDir.resolve("images/sidebar.bmp"), 493, 312, BufferedImage.TYPE_INT_RGB) { g2 ->
                        g2.color = Color.WHITE
                        g2.fillRect(165, 0, 493, 312)
                        g2.drawImage(rasterizeLogo(100), 32, 28, null)
                        g2.font = titilliumSemi.deriveFont(32f)
                        g2.drawString("Cinecred", 28, 176)
                        g2.font = titilliumBold.deriveFont(20f)
                        g2.drawString(version.toString(), 60, 204)
                    }
                }
                Platform.MAC_OS -> {
                    // Note: Currently, icons smaller than 128 written into an ICNS file by TwelveMonkeys cannot be
                    // properly parsed by macOS. We have to leave out those sizes to avoid glitches.
                    transcodeLogo(pkgDir.resolve("images/icon.icns"), intArrayOf(128, 256, 512, 1024), margin = 0.055)
                    buildImage(pkgDir.resolve("images/background.png"), 182, 180, BufferedImage.TYPE_INT_ARGB) { g2 ->
                        g2.drawImage(rasterizeLogo(80), 51, 0, null)
                        g2.color = Color.WHITE
                        g2.font = titilliumSemi.deriveFont(24f)
                        g2.drawString("Cinecred", 50, 110)
                        g2.font = titilliumBold.deriveFont(16f)
                        g2.drawString(version.toString(), 73, 130)
                    }
                }
                Platform.LINUX -> {
                    val logoFile = sourceSets.main.get().output.resourcesDir!!.resolve("logo.svg")
                    logoFile.copyTo(pkgDir.resolve("images/cinecred.svg"), overwrite = true)
                    transcodeLogo(pkgDir.resolve("images/cinecred.png"), intArrayOf(48))
                }
            }
        }
    }
    // Put it all together.
    task("prepare${platform.label}Packaging") {
        group = "Packaging Preparation"
        dependsOn(copyFiles)
        dependsOn(drawImages)
        doFirst {
            if (!Regex("\\d+\\.\\d+\\.\\d+").matches(version.toString()))
                throw GradleException("Non-release versions cannot be packaged.")
        }
    }
}

// And add one final job that calls all packaging jobs.
task("preparePackaging") {
    group = "Packaging Preparation"
    dependsOn(preparePackagingTasks)
}


fun Jar.makeFatJar(vararg includePlatforms: Platform) {
    // Depend on the build task so that tests must run beforehand.
    dependsOn("build")

    with(tasks.jar.get())

    manifest.attributes(
        "Main-Class" to "com.loadingbyte.cinecred.Main",
        "SplashScreen-Image" to "splash.png",
        "Add-Opens" to addOpens.joinToString(" ")
    )

    val excludePlatforms = Platform.values().filter { it !in includePlatforms }

    for (platform in excludePlatforms)
        exclude("natives/${platform.slug}")

    for (dep in configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts) {
        // Exclude all natives that haven't been explicitly included.
        if (excludePlatforms.any { it.slug in (dep.classifier ?: "") })
            continue
        // Put the files from the dependency into the fat JAR.
        from(zipTree(dep.file)) {
            // Exclude service files for now as they will be merged and included by some custom logic below.
            exclude("META-INF/services/*")
            // Put all licenses and related files into a central "licenses/" directory and rename
            // them such that each file also carries the name of the JAR it originates from.
            filesMatching(listOf("COPYRIGHT", "LICENSE", "NOTICE", "README").map { "**/*$it*" }) {
                path = "licenses/libraries/${dep.name}-${file.nameWithoutExtension}"
            }
            // Note: The "license/" directory is excluded only if the eachFile instruction has completely emptied it
            // (which we hope it does, but let's better be sure).
            exclude("**/module-info.class", "checkstyle/**", "findbugs/**", "license", "META-INF/maven/**", "pmd/**", "**/DEPENDENCIES")
        }
    }

    // Add all service files to the fat JAR, merging them if necessary. This is necessary for POI.
    val tmpServicesDir = temporaryDir.resolve("services")
    into("META-INF/services") {
        from(tmpServicesDir)
    }
    doFirst {
        tmpServicesDir.deleteRecursively()
        tmpServicesDir.mkdirs()
        for (depFile in configurations.runtimeClasspath.get())
            for (serviceFile in zipTree(depFile).matching { include("META-INF/services/*") })
                tmpServicesDir.resolve(serviceFile.name).appendText(serviceFile.readText())
    }
}


fun buildImage(to: File, width: Int, height: Int, imageType: Int, block: (Graphics2D) -> Unit) {
    val image = BufferedImage(width, height, imageType)
    val g2 = image.createGraphics()
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    block(g2)
    g2.dispose()
    to.parentFile.mkdirs()
    ImageIO.write(image, to.extension, to)
}

fun transcodeLogo(to: File, sizes: IntArray, margin: Double = 0.0) {
    val imageType = if (to.extension == "ico") BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_INT_ARGB
    val images = sizes.map { size -> rasterizeLogo(size, margin, imageType) }

    to.delete()
    to.parentFile.mkdirs()
    FileImageOutputStream(to).use { stream ->
        val writer = ImageIO.getImageWritersBySuffix(to.extension).next()
        writer.output = stream
        if (images.size == 1)
            writer.write(images[0])
        else {
            writer.prepareWriteSequence(null)
            for (image in images)
                writer.writeToSequence(IIOImage(image, null, null), null)
            writer.endWriteSequence()
        }
    }
}

fun rasterizeLogo(size: Int, margin: Double = 0.0, imageType: Int = BufferedImage.TYPE_INT_ARGB): BufferedImage {
    val (logo, logoWidth) = logoAndWidth
    val img = BufferedImage(size, size, imageType)
    val g2 = GraphicsUtil.createGraphics(img)
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val transl = margin * size
    val scaling = (size * (1 - 2 * margin)) / logoWidth
    g2.translate(transl, transl)
    g2.scale(scaling, scaling)
    logo.paint(g2)
    g2.dispose()
    return img
}

val logoAndWidth by lazy {
    val logoFile = sourceSets.main.get().output.resourcesDir!!.resolve("logo.svg")
    val doc = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
        .createDocument(null, logoFile.bufferedReader())
    val ctx = BridgeContext(UserAgentAdapter())
    val logo = GVTBuilder().build(ctx, doc)
    val logoWidth = ctx.documentSize.width
    Pair(logo, logoWidth)
}

val titilliumSemi by lazy {
    Font.createFonts(sourceSets.main.get().output.resourcesDir!!.resolve("fonts/Titillium-SemiboldUpright.otf"))[0]
}
val titilliumBold by lazy {
    Font.createFonts(sourceSets.main.get().output.resourcesDir!!.resolve("fonts/Titillium-BoldUpright.otf"))[0]
}
