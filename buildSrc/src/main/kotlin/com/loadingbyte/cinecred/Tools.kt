package com.loadingbyte.cinecred

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import java.io.ByteArrayOutputStream
import java.io.File


object Tools {

    fun vcvars(project: Project): String {
        locateVisualStudioBuildTools(project)?.let { baseDir ->
            val bat = baseDir.resolve("VC/Auxiliary/Build/vcvars64.bat")
            if (bat.canExecute())
                return bat.absolutePath.replace(Regex("[ ()]"), "^\$0")
        }
        throw InvalidUserDataException("You must install the MSVC compiler from the Build Tools for Visual Studio.")
    }

    fun vsLlvmHome(project: Project): String = locateVisualStudioLlvmHome(project).absolutePath
    fun clangCl(project: Project): String = locateVisualStudioLlvmHome(project).resolve("bin/clang-cl.exe").absolutePath
    fun lldLink(project: Project): String = locateVisualStudioLlvmHome(project).resolve("bin/lld-link.exe").absolutePath

    private fun locateVisualStudioLlvmHome(project: Project): File {
        locateVisualStudioBuildTools(project)?.let { baseDir ->
            val homeDir = baseDir.resolve("VC/Tools/Llvm/x64")
            if (homeDir.isDirectory)
                return homeDir
        }
        throw InvalidUserDataException("You must install the Clang compiler from the Build Tools for Visual Studio.")
    }

    private fun locateVisualStudioBuildTools(project: Project): File? {
        val cmd = listOf(
            File(System.getenv("ProgramFiles(x86)") ?: return null)
                .resolve("Microsoft Visual Studio/Installer/vswhere.exe").absolutePath,
            "-products", "Microsoft.VisualStudio.Product.BuildTools",
            "-latest",
            "-property", "installationPath",
            "-utf8"
        )
        val baos = ByteArrayOutputStream()
        if (project.exec { commandLine(cmd).setStandardOutput(baos) }.rethrowFailure().exitValue != 0)
            return null
        val baseDir = File(baos.toString(Charsets.UTF_8).trim())
        return if (baseDir.isDirectory) baseDir else null
    }

    fun jextractJava(project: Project): String {
        val dir = System.getProperty("jextract")
        val java = dir?.let(::File)?.resolve("bin/java")
        if (java != null && java.canExecute())
            return java.absolutePath
        val ver = project.extensions.getByType(JavaPluginExtension::class.java).toolchain.languageVersion.get()
        throw InvalidUserDataException(
            "You must download the jextract distribution for JDK $ver and point the VM property 'jextract' to the " +
                    "distribution's home folder (which contains 'bin').\n" +
                    "The property's current value '$dir' does not point to such a folder.\n" +
                    "Example on Linux: ./gradlew -Djextract=/path/to/jextract/distribution ..."
        )
    }

}
