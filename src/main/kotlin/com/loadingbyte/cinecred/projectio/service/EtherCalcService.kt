package com.loadingbyte.cinecred.projectio.service

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import com.loadingbyte.cinecred.ui.helper.JobSlot
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.deleteIfExists
import kotlin.io.path.notExists


object EtherCalcService : Service {

    override val product get() = "EtherCalc"
    override val authorizer get() = null
    override val accountNeedsServer get() = true
    override val uploadNeedsFilename get() = false

    override val accounts: List<Account> get() = _accounts

    private val httpClient by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build() }
    private fun httpRequestBuilder(uri: URI) = HttpRequest.newBuilder(uri).setHeader("User-Agent", "Cinecred/$VERSION")

    private val accountsFile = SERVICE_CONFIG_DIR.resolve("ethercalc.toml")
    private val accountsLock = ReentrantLock()
    @Volatile
    private var _accounts =
        if (accountsFile.notExists()) persistentListOf() else try {
            (readToml(accountsFile)["instance"] as? List<*> ?: emptyList<Any>()).mapNotNull {
                if (it !is Map<*, *>) return@mapNotNull null
                val id = it["id"] as? String ?: return@mapNotNull null
                val server = try {
                    URI(it["server"] as? String ?: return@mapNotNull null)
                } catch (_: URISyntaxException) {
                    return@mapNotNull null
                }
                if (!isServerPlausible(server)) return@mapNotNull null
                EtherCalcAccount(id, normalizeServer(server))
            }.toPersistentList()
        } catch (e: IOException) {
            LOGGER.error("Cannot access the EtherCalc services file at '{}'.", accountsFile, e)
            persistentListOf()
        }

    private fun writeAccountsFile() {
        val toml = mapOf("instance" to _accounts.map { mapOf("id" to it.id, "server" to it.server.toString()) })
        writeToml(accountsFile, toml)
    }

    override fun isServerPlausible(server: URI): Boolean =
        (server.scheme == "http" || server.scheme == "https") && !server.isOpaque && !server.host.isNullOrEmpty()

    private fun normalizeServer(server: URI): URI =
        server.resolve(server.rawPath.let { if (it.endsWith('/')) it else "$it/" })

    override fun addAccount(accountId: String, server: URI?) {
        if (!isServerPlausible(server!!))
            throw IOException("Server URL must use HTTP(S) and be hierarchical.")
        val server = normalizeServer(server)
        val req = httpRequestBuilder(server.resolve("_exists/x")).build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200 || resp.body().let { it != "true" && it != "false" })
            throw IOException(l10n("ui.preferences.accounts.establish.noServerFound", product, l10nQuoted(server)))
        val account = EtherCalcAccount(accountId, server)
        accountsLock.withLock {
            _accounts = _accounts.add(account)
            writeAccountsFile()
        }
        invokeAccountListListeners()
    }

    override fun removeAccount(account: Account) {
        require(account is EtherCalcAccount && account in _accounts) { "This service is not responsible." }
        accountsLock.withLock {
            _accounts = _accounts.remove(account)
            if (_accounts.isEmpty()) accountsFile.deleteIfExists() else writeAccountsFile()
        }
        invokeAccountListListeners()
    }

    override fun canWatch(link: URI): Boolean {
        if (link.scheme != "http" && link.scheme != "https" || link.isOpaque || link.host.isNullOrEmpty() ||
            '/' !in link.rawPath || link.rawPath.endsWith('/')
        )
            return false
        val linkStr = link.toString()
        for (account in _accounts) {
            val serverStr = account.server.toString()
            if (linkStr.startsWith(serverStr) && '/' !in linkStr.substring(serverStr.length))
                return true
        }
        val req = httpRequestBuilder(makeAPIURI(link, "_exists", "")).build()
        try {
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            return resp.statusCode() == 200 && resp.body() == "true"
        } catch (_: IOException) {
            return false
        }
    }

    override fun watch(link: URI, callbacks: ServiceWatcher.Callbacks): ServiceWatcher =
        EtherCalcWatcher(makeAPIURI(link, "_", "/csv"), callbacks)
            .also(EtherCalcWatcher::poll)

    private fun makeAPIURI(link: URI, endpoint: String, suffix: String): URI {
        val path = link.rawPath
        val idx = path.lastIndexOf('/')
        return link.resolve("${path.substring(0, idx)}/$endpoint/${path.substring(idx + 1)}$suffix")
    }


    private class EtherCalcWatcher(
        private val csvURI: URI,
        callbacks: ServiceWatcher.Callbacks
    ) : ServiceWatcher {

        private val jobSlot = JobSlot()
        @Volatile
        var callbacks: ServiceWatcher.Callbacks? = callbacks

        override fun poll() {
            callbacks ?: return
            jobSlot.submit {
                val req = httpRequestBuilder(csvURI).build()
                try {
                    val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                    if (resp.statusCode() != 200) throw IOException()
                    callbacks?.content(listOf(CsvFormat.read(resp.body(), l10n("project.template.spreadsheetName"))))
                } catch (_: IOException) {
                    callbacks?.problem(ServiceWatcher.Problem.DOWN)
                }
                // Simple rate limiting.
                Thread.sleep(1000)
            }
        }

        override fun cancel() {
            callbacks = null
        }

    }


    private class EtherCalcAccount(override val id: String, val server: URI) : Account {

        override val service get() = EtherCalcService

        override fun upload(filename: String?, spreadsheet: Spreadsheet, look: SpreadsheetLook): URI {
            val body = StringBuilder()
            body.appendLine(
                """socialcalc:version:1.0
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary=SocialCalcSpreadsheetControlSave
--SocialCalcSpreadsheetControlSave
Content-type: text/plain; charset=UTF-8

version:1.0
part:sheet
--SocialCalcSpreadsheetControlSave
Content-type: text/plain; charset=UTF-8

version:1.5"""
            )
            val fontNumbers = HashMap<FontKey, Int>()
            val rSty = StringBuilder()
            for (record in spreadsheet) {
                val rowLook = look.rowLooks[record.recordNo]
                for (col in 0..<spreadsheet.numColumns) {
                    if (rowLook != null) {
                        if (rowLook.borderBottom)
                            rSty.append(":b:::1:")
                        if (rowLook.fontSize != -1 || rowLook.bold || rowLook.italic) {
                            val fontKey = FontKey(rowLook.fontSize, rowLook.bold, rowLook.italic)
                            rSty.append(":f:").append(fontNumbers.computeIfAbsent(fontKey) { fontNumbers.size + 1 })
                        }
                    }
                    val cell = record.cells.getOrElse(col) { "" }
                    if (cell.isNotEmpty() || rSty.isNotEmpty())
                        body.append("cell:").append('A' + col).append(record.recordNo + 1).append(":t:")
                            .append(cell.replace("\n", "\\n").replace(":", "\\c"))
                            .appendLine(rSty)
                    rSty.clear()
                }
            }
            for ((col, colWidth) in look.colWidths.withIndex())
                body.append("col:").append('A' + col).append(":w:").appendLine(colWidth * 5)
            body.appendLine("sheet:layout:1")
                .appendLine("border:1:1px solid rgb(0,0,0)")
            for ((fontKey, fontNumber) in fontNumbers) {
                body.append("font:").append(fontNumber).append(':')
                if (!fontKey.bold && !fontKey.italic) body.append('*') else
                    body.append(if (fontKey.italic) "italic" else "normal").append(' ')
                        .append(if (fontKey.bold) "bold" else "normal")
                body.append(' ')
                if (fontKey.size == -1) body.append('*') else body.append(fontKey.size).append("pt")
                body.appendLine(" *")
            }
            body.appendLine("layout:1:padding:* * * *;vertical-align:bottom;")
                .appendLine("--SocialCalcSpreadsheetControlSave--")

            val req = httpRequestBuilder(server.resolve("_"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .setHeader("Content-Type", "text/x-socialcalc")
                .build()
            val name = try {
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() != 201) throw IOException()
                resp.body().substringAfterLast("/")
            } catch (e: IOException) {
                throw IOException(l10n("ui.projects.create.error.unresponsive"), e)
            }
            return try {
                server.resolve(URI(name))
            } catch (e: URISyntaxException) {
                throw IOException("Received malformed spreadsheet name: $name", e)
            }
        }

        private data class FontKey(val size: Int, val bold: Boolean, val italic: Boolean)

    }

}
