package com.loadingbyte.cinecred

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject


abstract class MergeServices : DefaultTask() {

    @get:InputFiles
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileOperations: FileOperations

    @TaskAction
    fun run() {
        val outputDir = outputDir.get().asFile
        for (file in classpath)
            for (serviceFile in fileOperations.zipTree(file).matching { include("META-INF/services/*") })
                outputDir.resolve(serviceFile.name).appendText(serviceFile.readText())
    }

}
