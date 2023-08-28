package com.loadingbyte.cinecred

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction


abstract class CheckoutGitRef : DefaultTask() {

    @get:Input
    abstract val uri: Property<String>
    @get:Input
    abstract val ref: Property<String>
    @get:OutputDirectory
    abstract val repositoryDir: DirectoryProperty

    @TaskAction
    fun run() {
        val uri = uri.get()
        val ref = ref.get()
        val repositoryDir = repositoryDir.get().asFile

        if (!repositoryDir.exists() || repositoryDir.list().let { it == null || it.isEmpty() })
            Git.cloneRepository().setURI(uri).setDirectory(repositoryDir).setNoCheckout(true).call()

        val git = Git.open(repositoryDir)
        git.fetch().call()
        git.reset().setMode(ResetCommand.ResetType.HARD).call()
        git.clean().setForce(true).setCleanDirectories(true).call()
        git.checkout().setName(ref).call()
    }

}
