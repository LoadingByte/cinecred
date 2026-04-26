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
import java.io.File
import javax.inject.Inject


abstract class BuildCLib : DefaultTask() {

    @get:Input
    abstract val forPlatform: Property<Platform>
    @get:InputDirectory
    abstract val clibDir: DirectoryProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val forPlatform = forPlatform.get()
        val srcFile = clibDir.get().asFile.resolve("clib.c")
        val outFile = outputFile.get().asFile

        val cmd = mutableListOf<String>()
        if (forPlatform.os == WINDOWS) {
            val sub = mutableListOf<String>()
            sub += listOf(Tools.vcvars(execOps), "&&", "cl", "/LD", "/O2", "/GL", "/GR-")
            sub += "/DCAPI=__declspec(dllexport)"
            sub += listOf("\"/Fe:${outFile.absolutePath}\"", "\"${srcFile.absolutePath}\"")
            sub += listOf("/link", "/NOIMPLIB", "/NOEXP")
            cmd += listOf("cmd", "/C", sub.joinToString(" "))
        } else {
            if (forPlatform.os == MAC) {
                cmd += listOf("clang", "-dynamiclib", "-target", "${forPlatform.arch.slug}-apple-macos12")
                cmd += "-Wl,-install_name,@rpath/${outFile.name}"
            } else if (forPlatform.os == LINUX)
                cmd += listOf("gcc", "-shared", "-s")
            cmd += listOf("-O2", "-fPIC")
            cmd += listOf("-o", outFile.absolutePath, srcFile.absolutePath)
        }

        execOps.exec { commandLine(cmd).workingDir(temporaryDir) }.rethrowFailure().assertNormalExitValue()
    }

}
