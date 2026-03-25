package com.loadingbyte.cinecred

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.Serializable
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory


abstract class CollectPOMLicenses : DefaultTask() {

    protected class Licensed(val groupId: String, val artifactId: String, val licenses: List<License>) : Serializable
    protected class License(val name: String, val url: String?) : Serializable

    @get:Input
    abstract val moduleComponentIds: ListProperty<ModuleComponentIdentifier>
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    protected val allLicensed: Provider<List<Licensed>>
        get() = moduleComponentIds.map { ids ->
            ids.mapNotNull { id ->
                val licenseElems = resolveLicenseElements(id.group, id.module, id.version) ?: return@mapNotNull null
                Licensed(id.group, id.module, licenses = buildList {
                    for (idx in 0 until licenseElems.length) {
                        val licenseElem = licenseElems.item(idx) as Element
                        val urlElems = licenseElem.getElementsByTagName("url")
                        this += License(
                            name = licenseElem.getElementsByTagName("name").item(0).textContent,
                            url = if (urlElems.length == 0) null else urlElems.item(0).textContent
                        )
                    }
                })
            }
        }

    private fun resolveLicenseElements(groupId: String, artifactId: String, version: String): NodeList? {
        val pomDep = project.dependencies.create(groupId, artifactId, version, ext = "pom")
        val pomFile = project.configurations.detachedConfiguration(pomDep).singleFile
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

    @TaskAction
    fun run() {
        val allLicensed = allLicensed.get()
        val outputDir = outputDir.get().asFile

        outputDir.deleteRecursively()
        outputDir.mkdirs()
        for (licensed in allLicensed) {
            val content = StringBuilder("${licensed.groupId}:${licensed.artifactId} is licensed under:").appendLine()
            for (license in licensed.licenses) {
                content.appendLine().appendLine(license.name)
                license.url?.let(content::appendLine)
            }
            outputDir.resolve("${licensed.artifactId}-LICENSE").writeText(content.toString())
        }
    }

}
