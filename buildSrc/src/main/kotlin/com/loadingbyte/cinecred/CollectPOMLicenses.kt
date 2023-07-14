package com.loadingbyte.cinecred

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory


abstract class CollectPOMLicenses : DefaultTask() {

    @get:Input
    abstract val configuration: Property<Configuration>
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val configuration = configuration.get()
        val outputDir = outputDir.get().asFile

        outputDir.deleteRecursively()
        outputDir.mkdirs()
        for (dep in configuration.resolvedConfiguration.resolvedArtifacts) {
            val id = dep.moduleVersion.id
            resolveLicenseElements(id.group, id.name, id.version)?.let { licenseElems ->
                val content = StringBuilder("${id.group}:${id.name} is licensed under:").appendLine()
                for (idx in 0 until licenseElems.length) {
                    val licenseElem = licenseElems.item(idx) as Element
                    val urlElems = licenseElem.getElementsByTagName("url")
                    content.appendLine().appendLine(licenseElem.getElementsByTagName("name").item(0).textContent)
                    if (urlElems.length != 0)
                        content.appendLine(urlElems.item(0).textContent)
                }
                outputDir.resolve("${dep.name}-LICENSE").writeText(content.toString())
            }
        }
    }

    private fun resolveLicenseElements(groupId: String, artifactId: String, version: String): NodeList? {
        val pomDep = project.dependencies.create(groupId, artifactId, version, ext = "pom")
        val pomFile = project.configurations.detachedConfiguration(pomDep).resolve().single()
        val pomDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile)
        val licenseElems = XPathFactory.newInstance().newXPath().compile("/project/licenses/license")
            .evaluate(pomDoc, XPathConstants.NODESET) as NodeList
        if (licenseElems.length != 0)
            return licenseElems
        val parentElems = XPathFactory.newInstance().newXPath().compile("/project/parent")
            .evaluate(pomDoc, XPathConstants.NODESET) as NodeList
        if (parentElems.length != 0) {
            val parentElem = parentElems.item(0) as Element
            return resolveLicenseElements(
                parentElem.getElementsByTagName("groupId").item(0).textContent,
                parentElem.getElementsByTagName("artifactId").item(0).textContent,
                parentElem.getElementsByTagName("version").item(0).textContent
            )
        }
        return null
    }

}
