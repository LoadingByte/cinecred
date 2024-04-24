import com.loadingbyte.cinecred.*
import com.loadingbyte.cinecred.Platform
import org.apache.tools.ant.filters.ReplaceTokens
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Year
import java.util.*


plugins {
    kotlin("jvm") version "1.9.20"
}

group = "com.loadingbyte"
version = "1.6.0-SNAPSHOT"

val jdkVersion = 21
val slf4jVersion = "2.0.7"
val poiVersion = "5.2.3"
val batikVersion = "1.16"
val javacppVersion = "1.5.8"
val ffmpegVersion = "5.1.2-$javacppVersion"
val flatlafVersion = "3.2"

// Versions of custom-built native libraries; upon updating, rebuild them following MAINTENANCE.md:
val harfBuzzVersion = "7.1.0"
val zimgVersion = "release-3.0.4"

val javaProperties = Properties().apply { file("java.properties").reader().use(::load) }
val mainClass = javaProperties.getProperty("mainClass")!!
val addModules = javaProperties.getProperty("addModules").split(' ')
val addOpens = javaProperties.getProperty("addOpens").split(' ')
val splashScreen = javaProperties.getProperty("splashScreen")!!
val javaOptions = javaProperties.getProperty("javaOptions")!!

val copyright = "Copyright \u00A9 ${Year.now().value} Felix Mujkanovic, licensed under the GPLv3 or any later version"


sourceSets {
    register("demo") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}


java {
    toolchain.languageVersion = JavaLanguageVersion.of(jdkVersion)
}

val natives = Platform.values().associateWith { platform ->
    configurations.create("${platform.label}Natives") { isTransitive = false }
}

val demoImplementation by configurations.getting { extendsFrom(configurations.implementation.get()) }
val demoRuntimeOnly by configurations.getting { extendsFrom(configurations.runtimeOnly.get()) }

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx", "kotlinx-collections-immutable", "0.3.5")

    // Log to java.util.logging
    implementation("org.slf4j", "slf4j-jdk14", slf4jVersion)
    // Redirect other logging frameworks to slf4j.
    // Batik & PDFBox use Jakarta Commons Logging. POI uses log4j2.
    implementation("org.slf4j", "jcl-over-slf4j", slf4jVersion)
    implementation("org.apache.logging.log4j", "log4j-to-slf4j", "2.20.0")

    // Spreadsheet IO
    implementation("org.apache.poi", "poi", poiVersion)
    implementation("org.apache.poi", "poi-ooxml", poiVersion)
    implementation("com.github.miachm.sods", "SODS", "1.6.1")
    implementation("org.apache.commons", "commons-csv", "1.10.0")

    // Spreadsheet Services
    implementation("com.googlecode.plist", "dd-plist", "1.27")
    implementation("com.google.oauth-client", "google-oauth-client-jetty", "1.34.1")
    implementation("com.google.apis", "google-api-services-sheets", "v4-rev20230227-2.0.0")

    // Raster Image IO
    implementation("com.twelvemonkeys.imageio", "imageio-tga", "3.9.4")

    // SVG IO
    implementation("org.apache.xmlgraphics", "batik-bridge", batikVersion)
    implementation("org.apache.xmlgraphics", "batik-svggen", batikVersion)
    // For pictures embedded in the SVG:
    implementation("org.apache.xmlgraphics", "batik-codec", batikVersion)

    // PDF IO
    implementation("org.apache.pdfbox", "pdfbox", "2.0.28")
    implementation("de.rototor.pdfbox", "graphics2d", "0.42")

    // Video IO
    implementation("org.bytedeco", "javacpp", javacppVersion)
    implementation("org.bytedeco", "ffmpeg", ffmpegVersion)
    for (platform in Platform.values()) {
        natives.getValue(platform)("org.bytedeco", "javacpp", javacppVersion, classifier = platform.slugJavacpp)
        natives.getValue(platform)("org.bytedeco", "ffmpeg", ffmpegVersion, classifier = "${platform.slugJavacpp}-gpl")
    }

    // UI
    implementation("com.miglayout", "miglayout-swing", "11.1")
    implementation("com.formdev", "flatlaf", flatlafVersion)
    for (pl in listOf(Platform.WINDOWS, Platform.LINUX))
        natives.getValue(pl)("com.formdev", "flatlaf", flatlafVersion, classifier = pl.slug, ext = pl.os.nativesExt)
    implementation("com.github.weisj", "jsvg", "1.4.0")
    implementation("org.commonmark", "commonmark", "0.21.0")

    // Testing
    testImplementation("org.junit.jupiter", "junit-jupiter", "5.9.3")
}

configurations.configureEach {
    // POI:
    // We don't re-evaluate formulas, and as only that code calls Commons Math, we can omit the dependency.
    exclude("org.apache.commons", "commons-math3")
    // This is only required for adding pictures to workbooks via code, which we don't do.
    exclude("commons-codec", "commons-codec")

    // Google Client: This dependency is totally empty and only serves to avoid some conflict not relevant to us.
    exclude("com.google.guava", "listenablefuture")

    // Batik & PDFBox: We replace this commons-logging dependency by the slf4j bridge.
    exclude("commons-logging", "commons-logging")

    // Batik:
    // The Java XML APIs are part of the JDK itself since Java 5.
    exclude("xml-apis", "xml-apis")
    // Batik functions well without the big xalan dependency.
    exclude("xalan", "xalan")
}


tasks.withType<JavaCompile>().configureEach {
    options.release = jdkVersion
    options.compilerArgs = listOf("--enable-preview", "--add-modules", addModules.joinToString(","))
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = jdkVersion.toString()
}

tasks.test {
    useJUnitPlatform()
}


val writeVersionFile by tasks.registering(WriteFile::class) {
    text = version.toString()
    outputFile = layout.buildDirectory.file("generated/version/version")
}

val writeCopyrightFile by tasks.registering(WriteFile::class) {
    text = copyright
    outputFile = layout.buildDirectory.file("generated/copyright/copyright")
}

val drawSplash by tasks.registering(DrawSplash::class) {
    version = project.version.toString()
    logoFile = srcMainResources.file("logo.svg")
    reguFontFile = srcMainResources.file("fonts/Titillium-RegularUpright.otf")
    semiFontFile = srcMainResources.file("fonts/Titillium-SemiboldUpright.otf")
    outputFile = layout.buildDirectory.file("generated/splash/splash.png")
}

val collectPOMLicenses by tasks.registering(CollectPOMLicenses::class) {
    artifactIds =
        configurations.runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }.map { it.map { a -> a.id } }
    outputDir = layout.buildDirectory.dir("generated/licenses")
}

tasks.processResources {
    from(writeVersionFile)
    from(writeCopyrightFile)
    from(drawSplash)
    from("CHANGELOG.md")
    into("licenses") {
        from("LICENSE")
        rename("LICENSE", "Cinecred-LICENSE")
    }
    // Collect all licenses (and related files) from the dependencies.
    // Rename these files such that each one carries the name of the JAR it originated from.
    for (artifact in configurations.runtimeClasspath.get().incoming.artifacts.resolvedArtifacts.get()) {
        val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: continue
        from(zipTree(artifact.file)) {
            include(listOf("COPYRIGHT", "LICENSE", "NOTICE", "README").map { "**/*$it*" })
            eachFile { path = "licenses/libraries/${id.module}-${file.nameWithoutExtension}" }
            includeEmptyDirs = false
        }
    }
    into("licenses/libraries") {
        from(collectPOMLicenses)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}


val platformNativesTasks = Platform.values().associateWith { platform ->
    tasks.register<Sync>("${platform.label}Natives") {
        // Collect all natives for the platform in a single directory
        from(srcMainNatives(platform))
        for (file in natives.getValue(platform))
            if (file.extension == platform.os.nativesExt)
                from(file)
            else
                from(zipTree(file)) {
                    include("**/*.${platform.os.nativesExt}*")
                    exclude("**/*avdevice*", "**/*avfilter*", "**/*postproc*")
                }
        into(layout.buildDirectory.dir("natives/${platform.slug}"))
        eachFile { path = name }
        includeEmptyDirs = false
    }
}


for (platform in Platform.values()) {
    val platformNatives = platformNativesTasks.getValue(platform)
    val mainClass_ = mainClass
    val jvmArgs_ = listOf(
        "-Djava.library.path=${platformNatives.get().destinationDir}",
        "-splash:${tasks.processResources.get().destinationDir}/$splashScreen",
        "--add-modules", addModules.joinToString(",")
    ) + addOpens.flatMap { listOf("--add-opens", "$it=ALL-UNNAMED") } + javaOptions.split(" ")
    tasks.register<JavaExec>("runOn${platform.label.capitalized()}") {
        group = "Execution"
        description = "Runs the program on ${platform.label.capitalized()}."
        dependsOn(platformNatives)
        classpath(sourceSets.main.map { it.runtimeClasspath })
        mainClass = mainClass_
        jvmArgs = jvmArgs_
    }
    tasks.register<JavaExec>("runDemoOn${platform.label.capitalized()}") {
        group = "Execution"
        description = "Runs the demo on ${platform.label.capitalized()}."
        dependsOn(platformNatives)
        classpath(sourceSets.named("demo").map { it.runtimeClasspath })
        mainClass = "com.loadingbyte.cinecred.DemoMain"
        jvmArgs = jvmArgs_ + listOf("--add-opens", "java.desktop/javax.swing=ALL-UNNAMED")
    }
}


val drawOSImagesTasks = Platform.OS.values().associateWith { os ->
    // Draw the images that are needed for the OS.
    tasks.register<DrawImages>("draw${os.slug.capitalized()}Images") {
        version = project.version.toString()
        forOS = os
        logoFile = srcMainResources.file("logo.svg")
        semiFontFile = srcMainResources.file("fonts/Titillium-SemiboldUpright.otf")
        boldFontFile = srcMainResources.file("fonts/Titillium-BoldUpright.otf")
        outputDir = layout.buildDirectory.dir("generated/packaging/${os.slug}")
    }
}


val preparePlatformPackagingTasks = Platform.values().map { platform ->
    // Collect all files needed for packaging in a folder.
    tasks.register<Sync>("prepare${platform.label.capitalized()}Packaging") {
        doFirst {
            if (!Regex("\\d+\\.\\d+\\.\\d+").matches(version.toString()))
                throw GradleException("Non-release versions cannot be packaged.")
        }
        group = "Packaging Preparation"
        description = "Prepares files for building a ${platform.label.capitalized()} package on that platform."
        into(layout.buildDirectory.dir("packaging/${platform.slug}"))
        // Copy the packaging scripts and fill in some variables. Note that we
        // don't select the scripts by platform here because that's not worth the effort.
        from("packaging") {
            val mainJarName = tasks.jar.get().archiveFileName.get()
            val tokens = mapOf(
                "VERSION" to version,
                "MAIN_JAR" to mainJarName,
                "MAIN_CLASS" to mainClass,
                "JAVA_OPTIONS" to "-Djava.library.path=\$APPDIR" +
                        " --add-modules ${addModules.joinToString(",")} " +
                        addOpens.joinToString(" ") { "--add-opens $it=ALL-UNNAMED" } +
                        " -splash:\$APPDIR/$splashScreen $javaOptions",
                "OS" to platform.os.slug,
                "ARCH" to platform.arch.slug,
                "ARCH_TEMURIN" to platform.arch.slugTemurin,
                "ARCH_WIX" to platform.arch.slugWix,
                "ARCH_DEBIAN" to platform.arch.slugDebian,
                "DESCRIPTION" to mainTranslations.get().getValue("").getProperty("slogan")!!,
                "URL" to "https://cinecred.com",
                "VENDOR" to "Felix Mujkanovic",
                "EMAIL" to "felix@cinecred.com",
                "COPYRIGHT" to copyright,
                "LINUX_SHORTCUT_COMMENTS" to mainTranslations.get().entries.mapNotNull { (locale, prop) ->
                    prop.getProperty("slogan")?.let { "Comment" + (if (locale.isEmpty()) "" else "[$locale]") + "=$it" }
                }.joinToString("\n"),
                "LEGAL_PATH_RUNTIME" to when (platform.os) {
                    Platform.OS.WINDOWS -> "runtime\\legal"
                    Platform.OS.MAC -> "runtime/Contents/Home/legal"
                    Platform.OS.LINUX -> "lib/runtime/legal"
                },
                "LEGAL_PATH_APP" to when (platform.os) {
                    Platform.OS.WINDOWS -> "app\\$mainJarName"
                    Platform.OS.MAC -> "app/$mainJarName"
                    Platform.OS.LINUX -> "lib/app/$mainJarName"
                }
            )
            filter<ReplaceTokens>("tokens" to tokens)
        }
        into("app") {
            from(tasks.jar)
            from(configurations.runtimeClasspath)
            from(platformNativesTasks[platform])
            from(drawSplash)
        }
        into("images") {
            from(drawOSImagesTasks[platform.os])
        }
    }
}

val preparePackaging by tasks.registering {
    group = "Packaging Preparation"
    description = "For each platform, prepares files for building a package for that platform on that platform."
    dependsOn(preparePlatformPackagingTasks)
}


val mergeServices by tasks.registering(MergeServices::class) {
    classpath.from(configurations.runtimeClasspath)
    outputDir = layout.buildDirectory.dir("generated/allJar/services")
}

val allJar by tasks.registering(Jar::class) {
    group = "Build"
    description = "Assembles a jar archive containing the program classes and all dependencies, excluding natives."
    archiveClassifier = "all"
    manifest.attributes(
        "Main-Class" to mainClass,
        "SplashScreen-Image" to splashScreen,
        "Add-Opens" to addOpens.joinToString(" ")
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { it.map(::zipTree) }) {
        exclude("META-INF/**", "**/module-info.class")
    }
    into("META-INF/services") {
        from(mergeServices)
    }
}


val checkoutHarfBuzz by tasks.registering(CheckoutGitRef::class) {
    uri = "https://github.com/harfbuzz/harfbuzz.git"
    ref = harfBuzzVersion
    repositoryDir = layout.buildDirectory.dir("repositories/harfbuzz")
}

val checkoutZimg by tasks.registering(CheckoutGitRef::class) {
    uri = "https://github.com/sekrit-twc/zimg.git"
    ref = zimgVersion
    repositoryDir = layout.buildDirectory.dir("repositories/zimg")
}

for (platform in Platform.values()) {
    tasks.register<BuildHarfBuzz>("buildHarfBuzzFor${platform.label.capitalized()}") {
        group = "Native"
        description = "Builds the HarfBuzz native library for ${platform.label.capitalized()}."
        forPlatform = platform
        repositoryDir = checkoutHarfBuzz.flatMap { it.repositoryDir }
        outputFile = srcMainNatives(platform, "harfbuzz")
    }

    tasks.register<BuildZimg>("buildZimgFor${platform.label.capitalized()}") {
        group = "Native"
        description = "Builds the zimg native library for ${platform.label.capitalized()}."
        forPlatform = platform
        repositoryDir = checkoutZimg.flatMap { it.repositoryDir }
        outputFile = srcMainNatives(platform, "zimg")
    }
}

tasks.register<Jextract>("jextractHarfBuzz") {
    group = "Native"
    description = "Extracts Java bindings for the HarfBuzz native library."
    targetPackage = "com.loadingbyte.cinecred.natives.harfbuzz"
    addHarfBuzzIncludes()
    includeDir = checkoutHarfBuzz.flatMap { it.repositoryDir }.map { it.dir("src") }
    headerFile = includeDir.map { it.file("hb.h") }
    outputDir = srcMainJava
}

tasks.register<Jextract>("jextractZimg") {
    group = "Native"
    description = "Extracts Java bindings for the zimg native library."
    targetPackage = "com.loadingbyte.cinecred.natives.zimg"
    headerFile = checkoutZimg.flatMap { it.repositoryDir.file("src/zimg/api/zimg.h") }
    outputDir = srcMainJava
}


val srcMainJava get() = layout.projectDirectory.dir("src/main/java")
val srcMainResources get() = layout.projectDirectory.dir("src/main/resources")

val srcMainNatives get() = layout.projectDirectory.dir("src/main/natives")
fun srcMainNatives(platform: Platform) = srcMainNatives.dir(platform.slug)
fun srcMainNatives(platform: Platform, libName: String) = srcMainNatives(platform).file(
    when (platform.os) {
        Platform.OS.WINDOWS -> "$libName.dll"
        Platform.OS.MAC -> "lib$libName.dylib"
        Platform.OS.LINUX -> "lib$libName.so"
    }
)

val mainTranslations: Provider<Map<String, Properties>> = sourceSets.main.map {
    val result = TreeMap<String, Properties>()
    for (file in it.resources.matching { include("/l10n/strings*.properties") })
        result[file.name.drop(8).dropLast(11)] = Properties().apply { file.bufferedReader().use(::load) }
    if (result.isEmpty())
        throw GradleException("No l10n files have been found; has the l10n system changed?")
    result
}


fun String.capitalized(): String = replaceFirstChar(Char::uppercase)
