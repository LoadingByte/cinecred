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
import javax.inject.Inject


abstract class BuildHarfBuzz : DefaultTask() {

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
        val srcFile = repositoryDir.get().asFile.resolve("src/harfbuzz.cc")
        val outFile = outputFile.get().asFile

        // Among other things, these macros disable unnecessary calls to OS libraries to maximize portability.
        //
        // Side note 1: We don't use `HB_LEAN` since that disables a lot of advanced shaping functionality (like automatic
        // fractions) which we actually wish to support.
        //
        // Side note 2: The following macros could be enabled on macOS and Linux, however, they are only needed in case we
        // create non-writable blobs, which we don't do at the moment. So for the time being, we omit these options to
        // maximize portability.
        //     -DGETPAGESIZE -DHAVE_MPROTECT -DHAVE_SYSCONF -DHAVE_SYS_MMAN_H -DHAVE_UNISTD_H
        val macros = listOf(
            "HB_DISABLE_DEPRECATED",
            "HB_NDEBUG",
            "HB_NO_ATEXIT",
            "HB_NO_ERRNO",
            "HB_NO_GETENV",
            "HB_NO_MMAP",
            "HB_NO_OPEN",
            "HB_NO_SETLOCALE"
        )

        val cmd = mutableListOf<String>()
        if (forPlatform.os == WINDOWS) {
            val sub = mutableListOf<String>()
            sub += listOf(Tools.vcvars(execOps), "&&", "cl", "/LD", "/std:c++11", "/O2", "/GL", "/GR-")
            sub += macros.map { "/D$it" } + "/DHB_EXTERN=__declspec(dllexport)"
            sub += listOf("\"/Fe:${outFile.absolutePath}\"", "\"${srcFile.absolutePath}\"")
            sub += listOf("/link", "/NOIMPLIB", "/NOEXP")
            cmd += listOf("cmd", "/C", sub.joinToString(" "))
        } else {
            if (forPlatform.os == MAC) {
                cmd += listOf("clang", "-dynamiclib", "-target", "${forPlatform.arch.slug}-apple-macos12")
                cmd += "-Wl,-install_name,@rpath/${outFile.name}"
            } else if (forPlatform.os == LINUX)
                cmd += listOf("gcc", "-shared", "-s")
            cmd += listOf("-std=c++11", "-O2", "-fPIC", "-flto", "-fno-rtti", "-fno-exceptions")
            cmd += macros.map { "-D$it" } + "-DHAVE_PTHREAD"
            cmd += listOf("-o", outFile.absolutePath, srcFile.absolutePath)
        }

        execOps.exec { commandLine(cmd).workingDir(temporaryDir) }.rethrowFailure().assertNormalExitValue()
    }

}
