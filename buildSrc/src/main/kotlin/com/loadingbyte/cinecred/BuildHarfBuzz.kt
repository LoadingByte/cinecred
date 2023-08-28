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


abstract class BuildHarfBuzz : DefaultTask() {

    @get:Input
    abstract val forPlatform: Property<Platform>
    @get:InputDirectory
    abstract val repositoryDir: DirectoryProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val forPlatform = forPlatform.get()
        val repositoryDir = repositoryDir.get().asFile
        val outputFile = outputFile.get().asFile

        val src = repositoryDir.resolve("src/harfbuzz.cc").absolutePath
        val lib = outputFile.absolutePath
        val win = a("/O2", "/GL", "/GR-", "/DHB_EXTERN=__declspec(dllexport)")
        val unix = a("-s", "-std=c++11", "-O2", "-fPIC", "-flto", "-fno-rtti", "-fno-exceptions", "-DHAVE_PTHREAD")
        val macTarget = "${forPlatform.arch.slug}-apple-macos11"

        val cmd = when (forPlatform.os) {
            WINDOWS -> a("cl", *win, *macros("/D"), src, "/OUT:$lib", "/LD")
            MAC -> a("clang", *unix, *macros("-D"), src, "-o", lib, "-dynamiclib", "-target", macTarget)
            LINUX -> a("gcc", *unix, *macros("-D"), src, "-o", lib, "-shared")
        }

        project.exec { commandLine(*cmd) }.rethrowFailure().assertNormalExitValue()
    }

    private fun a(vararg s: String) = s

    /**
     * Among other things, these macros disable unnecessary calls to OS libraries to maximize portability.
     *
     * Side note 1: We don't use `HB_LEAN` since that disables a lot of advanced shaping functionality (like automatic
     * fractions) which we actually wish to support.
     *
     * Side note 2: The following macros could be enabled on macOS and Linux, however, they are only needed in case we
     * create non-writable blobs, which we don't do at the moment. So for the time being, we omit these options to
     * maximize portability.
     *
     *     -DGETPAGESIZE -DHAVE_MPROTECT -DHAVE_SYSCONF -DHAVE_SYS_MMAN_H -DHAVE_UNISTD_H
     *
     */
    private fun macros(syntax: String) = listOf(
        "HB_DISABLE_DEPRECATED",
        "HB_NDEBUG",
        "HB_NO_ATEXIT",
        "HB_NO_ERRNO",
        "HB_NO_GETENV",
        "HB_NO_MMAP",
        "HB_NO_OPEN",
        "HB_NO_SETLOCALE"
    ).map { "$syntax$it" }.toTypedArray()

}
