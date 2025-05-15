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
val SERVICES: List<Service> = listOf(GoogleService, EtherCalcService)

private val ACCOUNT_LIST_LISTENERS = CopyOnWriteArrayList<() -> Unit>()

fun addAccountListListener(listener: () -> Unit) {
    ACCOUNT_LIST_LISTENERS.add(listener)
}

fun removeAccountListListener(listener: () -> Unit) {
    ACCOUNT_LIST_LISTENERS.remove(listener)
}

fun invokeAccountListListeners() {
    for (listener in ACCOUNT_LIST_LISTENERS)
        listener()
}


interface Service {

    val product: String
    val authorizer: String?
    val accountNeedsServer: Boolean
    val uploadNeedsFilename: Boolean

    val accounts: List<Account>

    /** If [accountNeedsServer] is true, checks very quickly whether a server looks plausible for this service. */
    fun isServerPlausible(server: URI): Boolean = throw NotImplementedError()
    /** @throws IOException */
    fun addAccount(accountId: String, server: URI?)
    /** @throws IOException */
    fun removeAccount(account: Account)

    fun canWatch(link: URI): Boolean

    /** Asynchronously watches the given link. Doesn't throw [IOException], instead calls the problem callback. */
    fun watch(link: URI, callbacks: ServiceWatcher.Callbacks): ServiceWatcher

}


interface Account {

    val id: String
    val service: Service

    /** @throws IOException */
    fun upload(filename: String?, spreadsheet: Spreadsheet, look: SpreadsheetLook): URI

}


interface ServiceWatcher {

    /** Asynchronously polls for changes. Doesn't throw [IOException], instead calls the problem callback. */
    fun poll()
    /** Once this method returns, it is guaranteed that no more calls to the [Callbacks] will be made. */
    fun cancel()

    interface Callbacks {
        fun content(spreadsheets: List<Spreadsheet>)
        fun problem(problem: Problem)
    }

    enum class Problem { INACCESSIBLE, DOWN }

}


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
