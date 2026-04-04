package com.loadingbyte.cinecred

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject


abstract class BuildNFD : DefaultTask() {

    @get:InputDirectory
    abstract val repositoryDir: DirectoryProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val repoDir = repositoryDir.get().asFile.resolve("src")
        val incDir = repoDir.resolve("include")
        val outFile = outputFile.get().asFile

        val macros = listOf("NFD_EXPORT")

        val cmd = mutableListOf<String>()
        cmd += listOf("gcc", "-shared", "-s", "-std=c++17")
        cmd += pkgConfig("--cflags", "dbus-1")
        cmd += listOf("-O2", "-fPIC", "-flto", "-fno-rtti", "-fno-exceptions")
        cmd += macros.map { "-D$it" }
        cmd += listOf("-o", outFile.absolutePath, "-I", incDir.absolutePath)
        cmd += repoDir.resolve("nfd_portal.cpp").absolutePath
        // We must put the linked dbus library at the very end, or else it isn't getting linked.
        cmd += pkgConfig("--libs", "dbus-1")

        execOps.exec { commandLine(cmd).workingDir(temporaryDir) }.rethrowFailure().assertNormalExitValue()
    }

    private fun pkgConfig(vararg args: String): List<String> {
        val baos = ByteArrayOutputStream()
        execOps.exec { commandLine("pkg-config", *args).standardOutput = baos }
            .rethrowFailure().assertNormalExitValue()
        return baos.toString(Charsets.UTF_8).trim().split(' ')
    }

}
