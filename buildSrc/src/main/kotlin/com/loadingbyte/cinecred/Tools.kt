package com.loadingbyte.cinecred

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
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

}
