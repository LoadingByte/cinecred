package com.loadingbyte.cinecred

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject


abstract class MergeServices : DefaultTask() {

    @get:Input
    abstract val configuration: Property<Configuration>
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileOperations: FileOperations

    @TaskAction
    fun run() {
        val outputDir = outputDir.get().asFile
        for (depFile in configuration.get())
            for (serviceFile in fileOperations.zipTree(depFile).matching { include("META-INF/services/*") })
                outputDir.resolve(serviceFile.name).appendText(serviceFile.readText())
    }

}
