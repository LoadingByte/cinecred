package com.loadingbyte.cinecred

import com.loadingbyte.cinecred.Platform.OS.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject


abstract class BuildNFD : DefaultTask() {

    @get:Input
    abstract val forPlatform: Property<Platform>
    @get:InputDirectory
    abstract val repositoryDir: DirectoryProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val forPlatform = forPlatform.get()
        val repoDir = repositoryDir.get().asFile.resolve("src")
        val incDir = repoDir.resolve("include")
        val outFile = outputFile.get().asFile

        val macros = listOf("NFD_EXPORT")

        val cmd = mutableListOf<String>()
        if (forPlatform.os == WINDOWS) {
            val sub = mutableListOf<String>()
            sub += listOf(Tools.vcvars(execOps), "&&", "cl", "/LD", "/std:c++17", "/O2", "/GL", "/GR-")
            sub += macros.map { "/D$it" }
            sub += listOf("\"/Fe:${outFile.absolutePath}\"", "/I", "\"${incDir.absolutePath}\"")
            sub += "\"${repoDir.resolve("nfd_win.cpp").absolutePath}\""
            sub += listOf("/link", "/NOIMPLIB", "/NOEXP", "ole32.lib", "shell32.lib")
            cmd += listOf("cmd", "/C", sub.joinToString(" "))
        } else {
            var srcFile: File? = null
            var lnkArgs = emptyList<String>()
            if (forPlatform.os == MAC) {
                cmd += listOf("clang", "-isysroot", "/Library/Developer/CommandLineTools/SDKs/MacOSX11.sdk")
                cmd += listOf("-dynamiclib", "-target", "${forPlatform.arch.slug}-apple-macos12")
                cmd += "-Wl,-install_name,@rpath/${outFile.name}"
                cmd += listOf("-framework", "AppKit", "-framework", "UniformTypeIdentifiers")
                srcFile = repoDir.resolve("nfd_cocoa.m")
            } else if (forPlatform.os == LINUX) {
                cmd += listOf("gcc", "-shared", "-s", "-std=c++17")
                cmd += pkgConfig("--cflags", "dbus-1")
                srcFile = repoDir.resolve("nfd_portal.cpp")
                lnkArgs = pkgConfig("--libs", "dbus-1")
            }
            cmd += listOf("-O2", "-fPIC", "-flto", "-fno-rtti", "-fno-exceptions")
            cmd += macros.map { "-D$it" }
            cmd += listOf("-o", outFile.absolutePath, "-I", incDir.absolutePath, srcFile!!.absolutePath)
            // We must put the linked dbus library at the very end, or else it isn't getting linked.
            cmd += lnkArgs
        }

        execOps.exec { commandLine(cmd).workingDir(temporaryDir) }.rethrowFailure().assertNormalExitValue()
    }

    private fun pkgConfig(vararg args: String): List<String> {
        val baos = ByteArrayOutputStream()
        execOps.exec { commandLine("pkg-config", *args).standardOutput = baos }
            .rethrowFailure().assertNormalExitValue()
        return baos.toString(Charsets.UTF_8).trim().split(' ')
    }

}
