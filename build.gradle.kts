import com.loadingbyte.cinecred.*
import com.loadingbyte.cinecred.Platform
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.configurationcache.extensions.capitalized
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*


plugins {
    kotlin("jvm") version "1.9.0"
}

group = "com.loadingbyte"
version = "1.6.0-SNAPSHOT"

val jdkVersion = 17
val slf4jVersion = "2.0.7"
val poiVersion = "5.2.3"
val batikVersion = "1.16"
val javacppVersion = "1.5.8"
val ffmpegVersion = "5.1.2-$javacppVersion"
val flatlafVersion = "3.1.1"

val javaProperties = Properties().apply { file("java.properties").reader().use(::load) }
val mainClass = javaProperties.getProperty("mainClass")!!
val addModules = javaProperties.getProperty("addModules").split(' ')
val addOpens = javaProperties.getProperty("addOpens").split(' ')
val splashScreen = javaProperties.getProperty("splashScreen")!!
val javaOptions = javaProperties.getProperty("javaOptions")!!


sourceSets {
    register("demo") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}


java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(jdkVersion))
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

    // Batik & PDFBox: We replace this commons-logging dependency by the slf4j bridge.
    exclude("commons-logging", "commons-logging")

    // Batik:
    // The Java XML APIs are part of the JDK itself since Java 5.
    exclude("xml-apis", "xml-apis")
    // Batik functions well without the big xalan dependency.
    exclude("xalan", "xalan")
}


tasks.withType<JavaCompile>().configureEach {
    options.release.set(jdkVersion)
    options.compilerArgs = listOf("--add-modules") + addModules
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = jdkVersion.toString()
}

tasks.test {
    useJUnitPlatform()
}


val writeVersionFile by tasks.registering(WriteFile::class) {
    text.set(version.toString())
    outputFile.set(layout.buildDirectory.file("generated/version/version"))
}

val drawSplash by tasks.registering(DrawSplash::class) {
    version.set(project.version.toString())
    logoFile.set(mainResource("logo.svg"))
    reguFontFile.set(mainResource("fonts/Titillium-RegularUpright.otf"))
    semiFontFile.set(mainResource("fonts/Titillium-SemiboldUpright.otf"))
    outputFile.set(layout.buildDirectory.file("generated/splash/splash.png"))
}

val collectPOMLicenses by tasks.registering(CollectPOMLicenses::class) {
    configuration.set(configurations.runtimeClasspath)
    outputDir.set(layout.buildDirectory.dir("generated/licenses"))
}

tasks.processResources {
    from(writeVersionFile)
    from(drawSplash)
    from("CHANGELOG.md")
    into("licenses") {
        from("LICENSE")
        rename("LICENSE", "Cinecred-LICENSE")
    }
    // Collect all licenses (and related files) from the dependencies.
    // Rename these files such that each one carries the name of the JAR it originated from.
    for (dep in configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts)
        from(zipTree(dep.file)) {
            include(listOf("COPYRIGHT", "LICENSE", "NOTICE", "README").map { "**/*$it*" })
            eachFile { path = "licenses/libraries/${dep.name}-${file.nameWithoutExtension}" }
            includeEmptyDirs = false
        }
    into("licenses/libraries") {
        from(collectPOMLicenses)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}


val platformNativesTasks = Platform.values().associateWith { platform ->
    tasks.register<Sync>("${platform.label}Natives") {
        // Collect all natives for the platform in a single directory
        from(layout.projectDirectory.dir("src/main/natives/${platform.slug}"))
        for (dep in natives.getValue(platform).resolvedConfiguration.resolvedArtifacts)
            if (dep.file.extension == platform.os.nativesExt)
                from(dep.file)
            else
                from(zipTree(dep.file)) {
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
        mainClass.set(mainClass_)
        jvmArgs = jvmArgs_
    }
    tasks.register<JavaExec>("runDemoOn${platform.label.capitalized()}") {
        group = "Execution"
        description = "Runs the demo on ${platform.label.capitalized()}."
        dependsOn(platformNatives)
        classpath(sourceSets.named("demo").map { it.runtimeClasspath })
        mainClass.set("com.loadingbyte.cinecred.DemoMain")
        jvmArgs = jvmArgs_
    }
}


val drawOSImagesTasks = Platform.OS.values().associateWith { os ->
    // Draw the images that are needed for the OS.
    tasks.register<DrawImages>("draw${os.slug.capitalized()}Images") {
        version.set(project.version.toString())
        forOS.set(os)
        logoFile.set(mainResource("logo.svg"))
        semiFontFile.set(mainResource("fonts/Titillium-SemiboldUpright.otf"))
        boldFontFile.set(mainResource("fonts/Titillium-BoldUpright.otf"))
        outputDir.set(layout.buildDirectory.dir("generated/packaging/${os.slug}"))
    }
}


val preparePlatformPackagingTasks = Platform.values().map { platform ->
    // Collect all files needed for packaging in a folder.
    val preparePlatformPackaging = tasks.register<Sync>("prepare${platform.label.capitalized()}Packaging") {
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
                "DESCRIPTION" to "Create beautiful film credits without the pain",
                "DESCRIPTION_DE" to "Wunderschöne Filmabspänne schmerzfrei erstellen",
                "DESCRIPTION_ZH_CN" to "高效简洁且功能多样的电影片尾字幕处理方案",
                "URL" to "https://cinecred.com",
                "VENDOR" to "Felix Mujkanovic",
                "EMAIL" to "felix@cinecred.com",
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
            from(tasks.processResources.map { it.destDirProvider.file(splashScreen) })
        }
        into("images") {
            from(drawOSImagesTasks[platform.os])
        }
    }

    return@map preparePlatformPackaging
}

val preparePackaging by tasks.registering {
    group = "Packaging Preparation"
    description = "For each platform, prepares files for building a package for that platform on that platform."
    dependsOn(preparePlatformPackagingTasks)
}


val mergeServices by tasks.registering(MergeServices::class) {
    configuration.set(configurations.runtimeClasspath)
    outputDir.set(layout.buildDirectory.dir("generated/allJar/services"))
}

val allJar by tasks.registering(Jar::class) {
    group = "Build"
    description = "Assembles a jar archive containing the program classes and all dependencies, excluding natives."
    archiveClassifier.set("all")
    manifest.attributes(
        "Main-Class" to mainClass,
        "SplashScreen-Image" to splashScreen,
        "Add-Opens" to addOpens.joinToString(" ")
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.map { it.output })
    for (dep in configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts)
        from(zipTree(dep.file)) {
            exclude("META-INF/**", "**/module-info.class")
        }
    into("META-INF/services") {
        from(mergeServices)
    }
}


fun mainResource(path: String): Provider<RegularFile> =
    sourceSets.main.map { layout.projectDirectory.file(it.resources.matching { include("/$path") }.singleFile.path) }

// We need to retrofit this property in a hacky and not entirely compliant way because it's sadly not migrated yet.
val Copy.destDirProvider: Directory
    get() = layout.projectDirectory.dir(destinationDir.path)
