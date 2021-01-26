import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.ext.awt.image.GraphicsUtil
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.tools.ant.filters.ReplaceTokens
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream


buildscript {
    val batikVersion = "1.13"
    val twelveMonkeysVersion = "3.6.2"
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.apache.xmlgraphics", "batik-bridge", batikVersion)
        classpath("org.apache.xmlgraphics", "batik-codec", batikVersion)
        // For writing Windows ICO icon files:
        classpath("com.twelvemonkeys.imageio", "imageio-bmp", twelveMonkeysVersion)
        // For writing Mac OS ICNS icon files:
        classpath("com.twelvemonkeys.imageio", "imageio-icns", twelveMonkeysVersion)
    }
}

plugins {
    kotlin("jvm") version "1.4.21"
}

group = "com.loadingbyte"
version = "0.1.0-SNAPSHOT"

val slf4jVersion = "1.7.30"
val batikVersion = "1.13"
val javacppVersion = "1.5.4"
// Note: At the time of writing, the latest version of FFmpeg which is available though javacpp (4.3.1-1.5.4) crashes
// upon libx264 encoding on Mac OS. When upgrading FFmpeg, you must check that H.264 now again works on Mac OS!
val ffmpegVersion = "4.2.2-1.5.3"

enum class Platform(val label: String, val slug: String) {
    WINDOWS("Windows", "windows-x86_64"),
    MAC_OS("MacOS", "macosx-x86_64"),
    LINUX("Linux", "linux-x86_64")
}

repositories {
    mavenCentral()
    // For kotlinx immutable collections:
    jcenter()
}

dependencies {
    implementation("org.jetbrains.kotlinx", "kotlinx-collections-immutable-jvm", "0.3.3")

    // Logging
    implementation("org.slf4j", "slf4j-simple", slf4jVersion)
    // Batik & PDFBox use JCL; this bridge redirects JCL to slf4j:
    implementation("org.slf4j", "jcl-over-slf4j", slf4jVersion)

    // CSV Parsing
    implementation("org.apache.commons", "commons-csv", "1.8")

    // SVG Parsing and Writing
    implementation("org.apache.xmlgraphics", "batik-bridge", batikVersion)
    implementation("org.apache.xmlgraphics", "batik-svggen", batikVersion)
    // For pictures embedded in the SVG:
    implementation("org.apache.xmlgraphics", "batik-codec", batikVersion)

    // PDF Reading and Writing
    implementation("org.apache.pdfbox", "pdfbox", "2.0.22")
    implementation("de.rototor.pdfbox", "graphics2d", "0.30")

    // Video Encoding
    implementation("org.bytedeco", "javacpp", javacppVersion)
    implementation("org.bytedeco", "ffmpeg", ffmpegVersion)
    for (platform in Platform.values()) {
        implementation("org.bytedeco", "javacpp", javacppVersion, classifier = platform.slug)
        implementation("org.bytedeco", "ffmpeg", ffmpegVersion, classifier = platform.slug)
    }

    // UI
    implementation("com.miglayout", "miglayout-swing", "5.2")
    implementation("com.formdev", "flatlaf", "0.46")
}

configurations.all {
    // Do not let Batik & PDFBox add the commons-logging dependency; instead, we use the slf4j bridge.
    exclude("commons-logging", "commons-logging")
    // The Java XML APIs are part of the JDK itself since Java 5.
    exclude("xml-apis", "xml-apis")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

val jar: Jar by tasks
jar.apply {
    from("LICENSE")
}


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
        from("LICENSE")
        // Copy the packaging scripts "filter" (fill in) some variables. Note that we
        // don't select the scripts by platform here because that's not worth the effort.
        from("packaging") {
            val tokens = mapOf(
                "VERSION" to version,
                "MAIN_JAR" to platformJar.archiveFileName.get()
            )
            filter<ReplaceTokens>("tokens" to tokens)
        }
        into("input") {
            from(platformJar)
            from(sourceSets.main.get().output.resourcesDir!!.resolve("splash.png"))
        }
    }
    // Transcode the logo SVG to the platform-specific icon image format and put it into the packaging folder.
    // For Mac OS, additionally create an installer background image from the logo.
    val transcodeLogo = task("transcodeLogo${platform.label}") {
        group = "Packaging Preparation"
        dependsOn("processResources")
        doLast {
            when (platform) {
                Platform.WINDOWS ->
                    transcodeLogo(pkgDir.resolve("icon.ico"), intArrayOf(16, 20, 24, 32, 40, 48, 64, 256))
                Platform.MAC_OS -> {
                    // Note: Currently, icons smaller than 128 written into an ICNS file by TwelveMonkeys cannot be
                    // properly parsed by Mac OS. We have to leave out those sizes to avoid glitches.
                    transcodeLogo(pkgDir.resolve("icon.icns"), intArrayOf(128, 256, 512, 1024), margin = 0.055)
                    val bgFile1 = pkgDir.resolve("resources/Cinecred-background.png")
                    val bgFile2 = pkgDir.resolve("resources/Cinecred-background-darkAqua.png")
                    transcodeLogo(bgFile1, intArrayOf(180), margin = 0.13)
                    bgFile1.copyTo(bgFile2, overwrite = true)
                }
                Platform.LINUX ->
                    transcodeLogo(pkgDir.resolve("icon.png"), intArrayOf(48))
            }
        }
    }
    // On Windows, we need to provide wget.
    val downloadWgetWindows = if (platform != Platform.WINDOWS) null else task("downloadWgetWindows") {
        doLast {
            pkgDir.mkdirs() // If we don't do this, ant throws an error.
            val url = "https://eternallybored.org/misc/wget/1.20.3/64/wget.exe"
            ant.withGroovyBuilder {
                "get"("src" to url, "dest" to pkgDir.resolve("wget.exe"))
            }
        }
    }
    // Put it all together.
    task("prepare${platform.label}Packaging") {
        group = "Packaging Preparation"
        dependsOn(copyFiles)
        dependsOn(transcodeLogo)
        downloadWgetWindows?.let { dependsOn(it) }
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

    with(jar)

    manifest.attributes(
        "Main-Class" to "com.loadingbyte.cinecred.MainKt",
        "SplashScreen-Image" to "splash.png"
    )

    val excludePlatforms = Platform.values().filter { it !in includePlatforms }
    for (dep in configurations.runtimeClasspath.get()) {
        // Exclude all natives that haven't been explicitly included.
        if (excludePlatforms.any { it.slug in dep.name })
            continue
        // Put the files from the dependency into the fat JAR.
        from(if (dep.isDirectory) dep else zipTree(dep)) {
            // Put all licenses and related files into a central "licenses/" directory and rename
            // them such that each file also carries the name of the JAR it originates from.
            eachFile {
                val filename = file.name
                when {
                    "DEPENDENCIES" in filename -> path = "licenses/libs/${dep.name}-${filename}"
                    "COPYRIGHT" in filename -> path = "licenses/libs/${dep.name}-${filename}"
                    "LICENSE" in filename -> path = "licenses/libs/${dep.name}-${filename}"
                    "NOTICE" in filename -> path = "licenses/libs/${dep.name}-${filename}"
                    "README" in filename -> path = "licenses/libs/${dep.name}-${filename}"
                }
            }
            // Note: The "license/" directory is excluded only if the eachFile instruction has completely emptied it
            // (which we hope it does, but let's better be sure).
            exclude("**/module-info.class", "checkstyle/**", "findbugs/**", "license", "META-INF/maven/**", "pmd/**")
        }
    }
}


fun transcodeLogo(to: File, sizes: IntArray, margin: Double = 0.0) {
    val (logo, logoWidth) = logoAndWidth
    val fileExt = to.name.substringAfterLast('.')
    val imageType = if (fileExt == "ico") BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_INT_ARGB

    val images = sizes.map { size ->
        val img = BufferedImage(size, size, imageType)
        val g2 = GraphicsUtil.createGraphics(img)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val transl = margin * size
        val scaling = (size * (1 - 2 * margin)) / logoWidth
        g2.translate(transl, transl)
        g2.scale(scaling, scaling)
        logo.paint(g2)
        g2.dispose()
        img
    }

    to.delete()
    to.parentFile.mkdirs()
    FileImageOutputStream(to).use { stream ->
        val writer = ImageIO.getImageWritersBySuffix(fileExt).next()
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

val logoAndWidth by lazy {
    val logoFile = sourceSets.main.get().output.resourcesDir!!.resolve("logo.svg")
    val doc = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
        .createDocument(null, logoFile.bufferedReader())
    val ctx = BridgeContext(UserAgentAdapter())
    val logo = GVTBuilder().build(ctx, doc)
    val logoWidth = ctx.documentSize.width
    Pair(logo, logoWidth)
}