package com.loadingbyte.cinecred

import org.eclipse.jgit.api.Git
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File


abstract class BuildSkia : DefaultTask() {

    @get:Input
    abstract val forPlatform: Property<Platform>
    @get:InputDirectory
    abstract val repositoryDir: DirectoryProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val forPlatform = forPlatform.get()
        val repoDir = repositoryDir.get().asFile
        val outFile = outputFile.get().asFile

        val gnArgs = mutableListOf<String>()
        // By default, skcms symbols are not exported. We define the SKCMS_API macro to remedy that.
        val export: String
        val ninja: String
        // Sadly, Skia's build system builds a separate libsvg.so library (or equivalent for other OSes), which is also
        // unusable because it depends on hidden symbols in libskia.so. As a workaround, we manually link all compiled
        // object files into a big shared library. The linker options mimic what Skia does.
        val ld = mutableListOf<String>()
        val obj: String
        if (forPlatform.os == Platform.OS.WINDOWS) {
            gnArgs += listOf(
                "clang_win=\"${Tools.vsLlvmHome(project)}\"",
                // Skia's own version detection algorithm expects a directory "x.y.z", but the actual directory is
                // called "17" at the time of writing. Since the result is irrelevant anyway, we just put a placeholder
                // to prevent Skia's algorithm from running at all.
                "clang_win_version=\"PLACEHOLDER\"",
                // Skia's build system raises linker errors if it cannot resolve symbols. We don't care about the result
                // of Skia's linker because we link everything ourselves. So we add a flag to make it run successfully.
                "extra_ldflags=[\"/FORCE:UNRESOLVED\"]"
            )
            export = "__declspec(dllexport)"
            ninja = "ninja.bat"
            // We need to ignore some unresolvable symbols in our actual final library:
            //   - The SVG module looks for text shaping, but we don't compile that in and instead remove all <text>
            //     tags from the SVG before passing it to Skia.
            //   - Some Windows-specific image format code looks for Windows libraries, but we don't use that code.
            ld += listOf(Tools.lldLink(project), "/DLL", "/FORCE:UNRESOLVED", "/OPT:ICF", "/OPT:REF")
            ld += "/OUT:${outFile.absolutePath}"
            obj = "obj"
        } else {
            export = "__attribute__((visibility(\\\"default\\\")))"
            ninja = "ninja"
            if (forPlatform.os == Platform.OS.MAC) {
                // Just like on Windows, Skia's build system can't resolve symbols, but we don't care.
                gnArgs += "extra_ldflags=[\"-Wl,-undefined,dynamic_lookup\"]"
                ld += listOf("clang++", "-target", "${forPlatform.arch.slug}-apple-macos12", "-dynamiclib")
                // Just like on Windows, our actual final library also contains some missing symbols.
                ld += listOf("-dead_strip", "-Wl,-undefined,dynamic_lookup", "-Wl,-install_name,@rpath/${outFile.name}")
            } else if (forPlatform.os == Platform.OS.LINUX)
                ld += listOf("clang++", "-shared", "-Wl,--gc-sections", "-s")
            ld += listOf("-o", outFile.absolutePath)
            obj = "o"
        }

        gnArgs += listOf(
            "is_official_build=true",
            "is_component_build=true",
            "cc=\"clang\"",
            "cxx=\"clang++\"",
            "target_cpu=\"${forPlatform.arch.slugWix}\"",
            // SKIA_IMPLEMENTATION leads to SK_API being defined as exporting instead of importing on Windows.
            // Skia's build system correctly defines it when compiling the main library, but forgets defining it
            // when compiling the SVG parsing module, so we force-define it here.
            "extra_cflags=[\"-Wno-psabi\", \"-DSKIA_IMPLEMENTATION=1\", \"-DSKCMS_API=$export\"]",
            "skia_enable_fontmgr_win=false",
            "skia_enable_fontmgr_win_gdi=false",
            "skia_enable_ganesh=false",
            "skia_enable_graphite=false",
            "skia_enable_pdf=true",
            "skia_enable_skottie=false",
            "skia_enable_skparagraph=false",
            "skia_enable_skshaper=false",
            "skia_enable_svg=true",
            "skia_use_client_icu=false",
            "skia_use_expat=true",  // for SVG support
            "skia_use_ffmpeg=false",
            "skia_use_fontations=false",
            "skia_use_fontconfig=false",
            "skia_use_fonthost_mac=false",
            "skia_use_freetype=false",
            "skia_use_harfbuzz=false",
            "skia_use_icu=false",
            "skia_use_libavif=false",
            "skia_use_libheif=false",
            "skia_use_libjpeg_turbo_decode=true",
            "skia_use_libjpeg_turbo_encode=true",
            "skia_use_libjxl_decode=false",
            "skia_use_libpng_decode=true",
            "skia_use_libpng_encode=true",
            "skia_use_libwebp_decode=false",
            "skia_use_libwebp_encode=false",
            "skia_use_lua=false",
            "skia_use_perfetto=false",
            "skia_use_piex=false",
            "skia_use_sfml=false",
            "skia_use_system_expat=false",
            "skia_use_system_libjpeg_turbo=false",
            "skia_use_system_libpng=false",
            "skia_use_system_zlib=false",
            "skia_use_wuffs=false",
            "skia_use_xps=false",
            "skia_use_zlib=true"  // for PDF support
        )

        var gnArgsArg = "--args=" + gnArgs.joinToString(" ")
        // On Windows, we have to escape double quotes for some reason; if we don't, gn gets confused.
        if (forPlatform.os == Platform.OS.WINDOWS)
            gnArgsArg = gnArgsArg.replace("\"", "\\\"")

        // Running these (expensive) commands modifies files in the original repo, but they are .gitignored and hence
        // shouldn't interfere with the checkout mechanism. We run the commands in the original repo instead of a copy
        // to benefit from caching when this task is run multiple times.
        exec(repoDir, "python3", "tools/git-sync-deps")
        exec(repoDir, "python3", "bin/fetch-ninja")

        val depotToolsDir = temporaryDir.resolve("depot_tools")
        check(depotToolsDir.deleteRecursively())
        Git.cloneRepository().setDirectory(depotToolsDir).setDepth(1)
            .setURI("https://chromium.googlesource.com/chromium/tools/depot_tools.git").call()

        val buildDir = temporaryDir.resolve("build")
        check(buildDir.deleteRecursively())

        exec(repoDir, repoDir.resolve("bin/gn").absolutePath, "gen", buildDir.absolutePath, gnArgsArg)
        exec(repoDir, depotToolsDir.resolve(ninja).absolutePath, "-C", buildDir.absolutePath, "skia", "modules")
        exec(buildDir, ld + buildDir.walk().filter { it.extension == obj }.map { it.toRelativeString(buildDir) })
    }

    private fun exec(workDir: File, vararg commandLine: String) = exec(workDir, commandLine.asList())
    private fun exec(workDir: File, commandLine: List<String>) {
        project.exec { commandLine(commandLine).workingDir(workDir) }.rethrowFailure().assertNormalExitValue()
    }

}
