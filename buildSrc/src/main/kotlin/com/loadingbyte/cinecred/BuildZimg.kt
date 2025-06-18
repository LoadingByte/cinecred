package com.loadingbyte.cinecred

import com.loadingbyte.cinecred.Platform.Arch.ARM64
import com.loadingbyte.cinecred.Platform.Arch.X86_64
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


abstract class BuildZimg : DefaultTask() {

    @get:Input
    abstract val forPlatform: Property<Platform>
    @get:InputDirectory
    abstract val repositoryDir: DirectoryProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val forPlatform = forPlatform.get()
        val srcDir = repositoryDir.get().asFile.resolve("src/zimg")
        val outFile = outputFile.get().asFile

        // Build the native library. For that, compile the sources for each SIMD flavor (e.g., SSE vs SSE2) separately,
        // then link the resulting object files to produce the library.
        //
        // Side note: The above bash script is derived from the GNU autotools build process officially provided by zlib
        // (and from the MSVC project files). However, as that process is extremely cumbersome on Windows when it comes
        // to dependencies added by MinGW-w64, we decided to instead manually employ Window's native CL compiler,
        // without a proper build system.
        //
        // Side note 2: On Linux, the compiled libraries will depend on libstdc++ and libgcc_s. We are fine with these
        // dependencies because the FFmpeg native libraries would declare them anyway.

        val macros = listOf("NDEBUG") +
                if (forPlatform.arch == X86_64) listOf("ZIMG_X86", "ZIMG_X86_AVX512") else listOf("ZIMG_ARM")

        val cc = mutableListOf<String>()
        val ld = mutableListOf<String>()
        val obj: String
        val simdFlavors: Map<String, List<String>>
        if (forPlatform.os == WINDOWS) {
            cc += listOf(Tools.clangCl(project), "/c", "/std:c++14", "/EHsc", "/O2", "/GS-", "-flto", "-Wno-assume")
            cc += macros.map { "/D$it" }
            cc += listOf("/I", srcDir.absolutePath)
            ld += listOf(Tools.lldLink(project), "/DLL", "/NOIMPLIB", "/OUT:${outFile.absolutePath}")
            obj = "obj"
            // Note: There are no switches for SSE and SSE2 in clang-cl; both are always enabled.
            simdFlavors = mapOf(
                "sse" to listOf(),
                "sse2" to listOf(),
                "avx" to listOf("/arch:AVX"),
                "f16c_ivb" to listOf("/arch:AVX", "-mf16c"),
                "avx2" to listOf("/arch:AVX2"),
                "avx512" to listOf("/arch:AVX512"),
                "avx512_vnni" to listOf("/arch:AVX512", "-mavx512vnni")
            )
        } else {
            if (forPlatform.os == MAC) {
                cc += listOf("clang++", "-target", "${forPlatform.arch.slug}-apple-macos12")
                ld += listOf("clang++", "-target", "${forPlatform.arch.slug}-apple-macos12", "-dynamiclib")
                ld += "-Wl,-install_name,@rpath/${outFile.name}"
            } else if (forPlatform.os == LINUX) {
                cc += listOf("g++")
                ld += listOf("g++", "-shared", "-s")
            }
            cc += listOf("-c", "-std=c++14", "-O2", "-fPIC", "-flto", "-fvisibility=hidden")
            cc += macros.map { "-D$it" }
            cc += listOf("-I", srcDir.absolutePath)
            ld += listOf("-o", outFile.absolutePath)
            obj = "o"
            simdFlavors = if (forPlatform.arch == ARM64) emptyMap() else mapOf(
                "sse" to listOf("-msse"),
                "sse2" to listOf("-msse2"),
                "avx" to listOf("-mavx", "-mtune=sandybridge"),
                "f16c_ivb" to listOf("-mavx", "-mf16c", "-mtune=ivybridge"),
                "avx2" to listOf("-mavx2", "-mf16c", "-mfma", "-mtune=haswell"),
                "avx512" to listOf(
                    "-mavx512f", "-mavx512cd", "-mavx512vl", "-mavx512bw", "-mavx512dq", "-mtune=skylake-avx512"
                ),
                "avx512_vnni" to listOf(
                    "-mavx512f", "-mavx512cd", "-mavx512vl", "-mavx512bw", "-mavx512dq", "-mavx512vnni",
                    "-mtune=cascadelake"
                )
            )
        }

        val excludeDirname = if (forPlatform.arch == X86_64) "arm" else "x86"
        val cpps = srcDir.walk().onEnter { it.name != excludeDirname }.filter { it.extension == "cpp" }
            .groupBy { simdFlavors.keys.find(it.nameWithoutExtension::endsWith) }

        val objDir = temporaryDir
        check(objDir.deleteRecursively())
        check(objDir.mkdir())

        exec(objDir, cc + cpps.getValue(null).map { it.absolutePath })
        for ((simdKey, simdArgs) in simdFlavors)
            cpps[simdKey]?.let { simdCpps -> exec(objDir, cc + simdArgs + simdCpps.map { it.absolutePath }) }
        exec(objDir, ld + cpps.values.flatten().map { "${it.nameWithoutExtension}.$obj" })
    }

    private fun exec(objDir: File, commandLine: List<String>) {
        project.exec { commandLine(commandLine).workingDir(objDir) }.rethrowFailure().assertNormalExitValue()
    }

}
