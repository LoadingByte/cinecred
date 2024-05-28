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
import java.io.File


abstract class BuildDeckLinkCAPI : DefaultTask() {

    @get:Input
    abstract val forPlatform: Property<Platform>
    @get:InputDirectory
    abstract val capiDir: DirectoryProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val forPlatform = forPlatform.get()
        val srcDir = capiDir.get().asFile
        val srcPaths = srcDir.listFiles { f: File -> f.extension == "cpp" }!!.map { it.absolutePath }
        val outFile = outputFile.get().asFile

        val cmd = mutableListOf<String>()
        if (forPlatform.os == WINDOWS) {
            // First run MIDL to generate an actual .h file from DeckLink's .idl file.
            val vcvars = Tools.vcvars(project)
            val idlFile = srcDir.resolve("sdk/windows/DeckLinkAPI.idl")
            val midlCmd = listOf("cmd", "/C", "$vcvars && midl /notlb /h DeckLinkAPI.h ${idlFile.absolutePath}")
            project.exec { commandLine(midlCmd).workingDir(temporaryDir) }.rethrowFailure().assertNormalExitValue()
            val sub = mutableListOf<String>()
            sub += listOf(vcvars, "&&", "cl", "/LD", "/std:c++17", "/O2", "/GL", "/GR-", "/DCAPI=__declspec(dllexport)")
            sub += listOf("\"/Fe:${outFile.absolutePath}\"", "/I", "\"${temporaryDir.absolutePath}\"")
            sub += srcPaths.map { "\"$it\"" }
            sub += listOf("/link", "/NOIMPLIB", "/NOEXP", "ole32.lib", "comsuppw.lib")
            cmd += listOf("cmd", "/C", sub.joinToString(" "))
        } else {
            if (forPlatform.os == MAC) {
                cmd += listOf("clang++", "-dynamiclib", "-target", "${forPlatform.arch.slug}-apple-macos11")
                cmd += listOf("-Wl,-install_name,@rpath/${outFile.name}", "-framework", "CoreFoundation")
            } else if (forPlatform.os == LINUX)
                cmd += listOf("g++", "-shared", "-s", "-Wl,-rpath,\$ORIGIN")
            cmd += listOf("-std=c++17", "-O3", "-fPIC", "-flto", "-fno-rtti", "-fno-exceptions")
            cmd += listOf("-o", outFile.absolutePath)
            cmd += srcPaths
        }

        project.exec { commandLine(cmd).workingDir(temporaryDir) }.rethrowFailure().assertNormalExitValue()
    }

}
