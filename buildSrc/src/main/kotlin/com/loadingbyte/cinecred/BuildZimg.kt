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
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject


abstract class BuildZimg : DefaultTask() {

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
        val repoDir = repositoryDir.get().asFile
        val srcDirs = listOf(repoDir.resolve("src/zimg"), repoDir.resolve("graphengine/graphengine"))
        val incDirs = listOf(repoDir.resolve("src/zimg"), repoDir.resolve("graphengine/include"))
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

        val macros = listOf("NDEBUG", "GRAPHENGINE_IMPL_NAMESPACE=zimg") +
                if (forPlatform.arch == X86_64) listOf("ZIMG_X86") else listOf("ZIMG_ARM")

        val cc = mutableListOf<String>()
        val ld = mutableListOf<String>()
        val obj: String
        val simdFlavors: Map<String, List<String>>
        if (forPlatform.os == WINDOWS) {
            cc += listOf(Tools.clangCl(execOps), "/c", "/std:c++17", "/EHsc", "/O2", "/GS-", "-flto", "-Wno-assume")
            cc += macros.map { "/D$it" }
            cc += incDirs.flatMap { listOf("/I", it.absolutePath) }
            ld += listOf(Tools.lldLink(execOps), "/DLL", "/NOIMPLIB", "/OUT:${outFile.absolutePath}")
            obj = "obj"
            simdFlavors = mapOf(
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
            cc += listOf("-c", "-std=c++17", "-O2", "-fPIC", "-flto", "-fvisibility=hidden")
            cc += macros.map { "-D$it" }
            cc += incDirs.flatMap { listOf("-I", it.absolutePath) }
            ld += listOf("-o", outFile.absolutePath)
            obj = "o"
            simdFlavors = if (forPlatform.arch == ARM64) emptyMap() else mapOf(
                "avx2" to listOf("-mavx2", "-mf16c", "-mfma", "-mtune=haswell"),
                "avx512" to listOf(
                    "-mavx512f", "-mavx512cd", "-mavx512vl", "-mavx512bw", "-mavx512dq", "-mtune=skylake-avx512"
                ),
                "avx512_vnni" to listOf(
                    "-mavx512f", "-mavx512cd", "-mavx512vl", "-mavx512bw", "-mavx512dq", "-mavx512vnni",
                    "-mtune=cascadelake"
                )
                // Note: Using the ARM NEON intrinsics doesn't require compiler flags.
            )
        }

        val rootObjDir = temporaryDir
        check(rootObjDir.deleteRecursively())

        for (srcDir in srcDirs) {
            val objDir = rootObjDir.resolve(srcDir.name)
            check(objDir.mkdirs())

            val excludeDirname = if (forPlatform.arch == X86_64) "arm" else "x86"
            val cpps = srcDir.walk().onEnter { it.name != excludeDirname }.filter { it.extension == "cpp" }
                .groupBy { simdFlavors.keys.find(it.nameWithoutExtension::endsWith) }

            exec(objDir, cc + cpps.getValue(null).map { it.absolutePath })
            for ((simdKey, simdArgs) in simdFlavors)
                cpps[simdKey]?.let { simdCpps -> exec(objDir, cc + simdArgs + simdCpps.map { it.absolutePath }) }
        }

        exec(rootObjDir, ld + rootObjDir.walk().filter { it.extension == obj }.map { it.absolutePath })
    }

    private fun exec(objDir: File, commandLine: List<String>) {
        execOps.exec { commandLine(commandLine).workingDir(objDir) }.rethrowFailure().assertNormalExitValue()
    }

}
