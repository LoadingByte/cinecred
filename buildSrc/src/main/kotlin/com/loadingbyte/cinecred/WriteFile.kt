package com.loadingbyte.cinecred

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction


abstract class WriteFile : DefaultTask() {

    @get:Input
    abstract val text: Property<String>
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        outputFile.get().asFile.writeText(text.get())
    }

}
