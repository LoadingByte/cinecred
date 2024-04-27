package com.loadingbyte.cinecred

import com.loadingbyte.cinecred.Platform.OS.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File


abstract class BuildSkiaCAPI : DefaultTask() {

    @get:Input
    abstract val forPlatform: Property<Platform>
    @get:InputDirectory
    abstract val capiDir: DirectoryProperty
    @get:InputDirectory
    abstract val repositoryDir: DirectoryProperty
    @get:InputFile
    abstract val linkedFile: RegularFileProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val forPlatform = forPlatform.get()
        val srcPaths = capiDir.get().asFile.listFiles { f: File -> f.extension == "cpp" }!!.map { it.absolutePath }
        val repoDir = repositoryDir.get().asFile
        val lnkFile = linkedFile.get().asFile
        val outFile = outputFile.get().asFile

        // NDEBUG ensures that the Skia CAPI doesn't contain SkDebugf calls, which would crash due to a missing symbol.
        val macros = listOf("NDEBUG")

        val cmd = mutableListOf<String>()
        if (forPlatform.os == WINDOWS) {
            val sub = mutableListOf<String>()
            // Skia requires C++17.
            sub += listOf(Tools.vcvars(project), "&&", "cl", "/LD", "/std:c++17", "/O2", "/GL", "/GR-")
            sub += macros.map { "/D$it" } + "/DCAPI=__declspec(dllexport)"
            sub += listOf("\"/Fe:${outFile.absolutePath}\"", "/I", "\"${repoDir.absolutePath}\"")
            sub += srcPaths.map { "\"$it\"" }
            sub += listOf("/link", "/NOIMPLIB", "/NOEXP", "\"${lnkFile.absolutePath}\"")
            cmd += listOf("cmd", "/C", sub.joinToString(" "))
        } else {
            if (forPlatform.os == MAC) {
                cmd += listOf("clang++", "-dynamiclib", "-target", "${forPlatform.arch.slug}-apple-macos11")
                cmd += "-Wl,-install_name,@rpath/${outFile.name}"
            } else if (forPlatform.os == LINUX)
                cmd += listOf("g++", "-shared", "-s", "-Wl,-rpath,\$ORIGIN")
            // Skia requires C++17.
            cmd += listOf("-std=c++17", "-O3", "-fPIC", "-flto", "-fno-rtti", "-fno-exceptions")
            cmd += macros.map { "-D$it" }
            cmd += listOf("-o", outFile.absolutePath, "-I", repoDir.absolutePath)
            cmd += srcPaths
            // Link the main lib by its name and not its absolute path.
            cmd += listOf("-L${lnkFile.parentFile.absolutePath}", "-l${lnkFile.nameWithoutExtension.substring(3)}")
        }

        project.exec { commandLine(cmd).workingDir(temporaryDir) }.rethrowFailure().assertNormalExitValue()
    }

}
