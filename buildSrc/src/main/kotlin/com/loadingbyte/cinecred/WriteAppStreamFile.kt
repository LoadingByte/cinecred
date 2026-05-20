package com.loadingbyte.cinecred

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.time.LocalDate
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult


abstract class WriteAppStreamFile : DefaultTask() {

    @get:Input
    abstract val version: Property<String>
    @get:Input
    abstract val summaries: MapProperty<Locale, String>
    @get:Input
    abstract val descriptions: MapProperty<Locale, String>
    @get:Input
    abstract val homepage: Property<String>
    @get:Input
    abstract val developer: Property<String>
    @get:Input
    abstract val updateContact: Property<String>
    @get:Input
    abstract val categories: ListProperty<String>
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().domImplementation
            .createDocument(null, "component", null)
        val comp = doc.documentElement
        comp.setAttribute("type", "desktop-application")

        comp.appendChild(doc.createElement("id", "com.cinecred.cinecred"))
        comp.appendChild(doc.createElement("metadata_license", "CC0-1.0"))
        comp.appendChild(doc.createElement("name", "Cinecred"))
        for ((locale, summary) in summaries.get().entries)
            comp.appendChild(makeLocalized(doc, "summary", locale, summary))
        comp.appendChild(doc.createElement("description").also { desc ->
            val splitDescs = descriptions.get().mapValues { (_, teaser) -> teaser.split("\n\n") }
            for (p in splitDescs.getValue(Locale.ENGLISH).indices)
                for ((locale, splitDesc) in splitDescs)
                    desc.appendChild(makeLocalized(doc, "p", locale, splitDesc[p]))
        })
        comp.appendChild(doc.createElement("categories").apply {
            for (category in categories.get())
                appendChild(doc.createElement("category", category))
        })
        comp.appendChild(makeURL(doc, "homepage", homepage.get()))
        comp.appendChild(makeURL(doc, "bugtracker", "https://github.com/LoadingByte/cinecred/issues"))
        comp.appendChild(makeURL(doc, "help", "https://cinecred.com/guide/"))
        comp.appendChild(makeURL(doc, "donation", "https://cinecred.com/donate/"))
        comp.appendChild(makeURL(doc, "translate", "https://hosted.weblate.org/projects/cinecred/"))
        comp.appendChild(makeURL(doc, "vcs-browser", "https://github.com/LoadingByte/cinecred"))
        comp.appendChild(doc.createElement("launchable", "cinecred.desktop").apply {
            setAttribute("type", "desktop-id")
        })
        comp.appendChild(doc.createElement("releases")).appendChild(doc.createElement("release").apply {
            setAttribute("version", version.get())
            setAttribute("date", LocalDate.now().toString())
        })
        comp.appendChild(doc.createElement("provides").apply {
            appendChild(doc.createElement("binary", "cinecred"))
        })
        comp.appendChild(doc.createElement("project_license", "GPL-3.0-or-later"))
        comp.appendChild(doc.createElement("developer").apply {
            setAttribute("id", "com.cinecred")
            appendChild(doc.createElement("name", developer.get()))
        })
        comp.appendChild(doc.createElement("screenshots").apply {
            appendChild(makeLocalizedScreenshot(doc, "screenshot").apply {
                setAttribute("type", "default")
            })
        })
        comp.appendChild(doc.createElement("content_rating").apply {
            setAttribute("type", "oars-1.1")
        })
        comp.appendChild(doc.createElement("update_contact", updateContact.get().replace("@", "_at_")))

        outputFile.get().asFile.bufferedWriter().use { writer ->
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }.transform(DOMSource(doc), StreamResult(writer))
        }
    }

    private fun makeURL(doc: Document, type: String, url: String) =
        doc.createElement("url", url).apply { setAttribute("type", type) }

    private fun makeLocalizedScreenshot(doc: Document, name: String): Element {
        val elem = doc.createElement("screenshot")
        for (locale in summaries.get().keys) {
            val url = "https://cinecred.com/home/$name.${locale.toLanguageTag()}.png"
            elem.appendChild(makeLocalized(doc, "image", locale, url))
        }
        return elem
    }

    private fun makeLocalized(doc: Document, tagName: String, locale: Locale, textContent: String) =
        doc.createElement(tagName, textContent).apply {
            if (locale != Locale.ENGLISH)
                setAttribute("xml:lang", locale.toLanguageTag())
        }

    private fun Document.createElement(tagName: String, textContent: String) =
        createElement(tagName).also { it.textContent = textContent }

}
