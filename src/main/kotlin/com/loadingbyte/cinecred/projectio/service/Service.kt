package com.loadingbyte.cinecred.projectio.service

import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.loadingbyte.cinecred.common.CONFIG_DIR
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.io.path.writeText


val SERVICE_CONFIG_DIR: Path = CONFIG_DIR.resolve("services")
val SERVICE_PROVIDERS: List<ServiceProvider> = listOf(GoogleServiceProvider)

private val SERVICE_LIST_LISTENERS = CopyOnWriteArrayList<() -> Unit>()

fun addServiceListListener(listener: () -> Unit) {
    SERVICE_LIST_LISTENERS.add(listener)
}

fun invokeServiceListListeners() {
    for (listener in SERVICE_LIST_LISTENERS)
        listener()
}


interface ServiceProvider {

    val label: String
    val services: List<Service>

    /** @throws IOException */
    fun addService(serviceId: String)
    /** @throws IOException */
    fun removeService(service: Service)

    fun canWatch(link: URI): Boolean

    /** Asynchronously watches the given link. Doesn't throw [IOException], instead calls the problem callback. */
    fun watch(link: URI, callbacks: ServiceWatcher.Callbacks): ServiceWatcher

}


interface Service {

    val id: String
    val provider: ServiceProvider

    /**
     * @throws ForbiddenException
     * @throws DownException
     * @throws IOException
     */
    fun upload(filename: String, sheetName: String, spreadsheet: Spreadsheet, look: SpreadsheetLook): URI

}


interface ServiceWatcher {

    /** Asynchronously polls for changes. */
    fun poll()
    /** Once this method returns, it is guaranteed that no more calls to the [Callbacks] will be made. */
    fun cancel()

    interface Callbacks {
        fun content(spreadsheet: Spreadsheet)
        fun problem(problem: Problem)
    }

    enum class Problem { INACCESSIBLE, DOWN }

}


class ForbiddenException : IOException()
class DownException : IOException()


const val WRITTEN_SERVICE_LINK_EXT = "url"
val SERVICE_LINK_EXTS = listOf("url", "webloc")

/** @throws Exception */
fun readServiceLink(file: Path): URI =
    when (val fileExt = file.extension) {
        "url" -> {
            val line = file.readLines().find { it.startsWith("URL=", ignoreCase = true) }
                ?: throw IllegalArgumentException("Missing URL= entry in .url file.")
            URI(line.substring(4))
        }
        "webloc" -> {
            val plist = PropertyListParser.parse(file)
            require(plist is NSDictionary) { "Top-level element in .webloc file must be an NSDictionary." }
            val rawURI = plist["URL"]?.toString()
                ?: throw IllegalArgumentException("Missing URL entry in .webloc file.")
            URI(rawURI)
        }
        else -> throw IllegalArgumentException("Not a link file extension: .$fileExt")
    }

/** @throws IOException */
fun writeServiceLink(file: Path, link: URI) {
    file.writeText("[InternetShortcut]\r\nURL=$link")
}
