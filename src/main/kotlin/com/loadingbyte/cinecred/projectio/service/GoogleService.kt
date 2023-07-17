package com.loadingbyte.cinecred.projectio.service

import com.google.api.client.auth.oauth2.StoredCredential
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.UrlEncodedContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsRequest
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.*
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.useResourceStream
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import com.loadingbyte.cinecred.ui.helper.tryBrowse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.collections.immutable.toPersistentList
import org.apache.http.client.utils.URLEncodedUtils
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.text.MessageFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.roundToInt


object GoogleService : Service {

    override val product get() = "Google Sheets"
    override val authenticator get() = "Google"

    override val accounts: List<Account> get() = _accounts.get()

    private val credentialDataStore by lazy {
        StoredCredential.getDefaultDataStore(FileDataStoreFactory(SERVICE_CONFIG_DIR.resolve("google").toFile()))
    }
    private var _accounts =
        AtomicReference(credentialDataStore.keySet().sorted().map(::GoogleAccount).toPersistentList())

    private val watchersForPolling = LinkedBlockingQueue<GoogleWatcher>()
    private val watchersPoller = AtomicReference<Thread?>()

    override fun addAccount(accountId: String) {
        require(_accounts.get().none { it.id == accountId }) { "Account ID already in use." }
        val account = GoogleAccount(accountId)
        account.sheets(authorizeIfNeeded = true)
        _accounts.updateAndGet { it.add(account) }
        invokeAccountListListeners()
    }

    override fun removeAccount(account: Account) {
        require(account is GoogleAccount && account in _accounts.get()) { "This service is not responsible." }

        // Try to revoke the refresh token. If this fails, do not remove the account. We don't want non-revoked refresh
        // tokens floating around.
        // This request is manual as revocation is missing from the client library:
        // https://github.com/googleapis/google-oauth-java-client/issues/250
        val refreshToken = credentialDataStore.get(account.id)?.refreshToken
        if (refreshToken != null) {
            val request = GoogleNetHttpTransport.newTrustedTransport().createRequestFactory().buildPostRequest(
                GenericUrl("https://oauth2.googleapis.com/revoke"),
                UrlEncodedContent(mapOf("token" to refreshToken))
            ).setThrowExceptionOnExecuteError(false)
            val response = request.execute()
            if (!response.isSuccessStatusCode) {
                val e = TokenResponseException.from(GsonFactory.getDefaultInstance(), response)
                // Check whether the token has already been revoked, or whether the client secret has become invalid,
                // in which case everything is fine.
                if (!(response.statusCode == 400 && e.details.error != "invalid_token" || response.statusCode == 401))
                    throw e
            }
        }

        forceRemoveAccount(account)
    }

    private fun forceRemoveAccount(account: GoogleAccount) {
        credentialDataStore.delete(account.id)
        _accounts.updateAndGet { it.remove(account) }
        invokeAccountListListeners()
    }

    private fun fileIdFromLink(link: URI): String? {
        if (link.scheme != "http" && link.scheme != "https" || link.host != "docs.google.com")
            return null
        val path = link.path.split('/')
        if (path[1] != "spreadsheets" || path[2] != "d") return null
        return path[3]
    }

    override fun canWatch(link: URI): Boolean =
        fileIdFromLink(link) != null

    override fun watch(link: URI, callbacks: ServiceWatcher.Callbacks): ServiceWatcher {
        // Just add an uninitialized watcher. All the work will be done by the poller.
        val watcher = GoogleWatcher(fileIdFromLink(link)!!, callbacks)
        watchersPoller.updateAndGet { it ?: Thread(::pollerLoop, "GooglePoller").apply { isDaemon = true; start() } }
        watchersForPolling.add(watcher)
        return watcher
    }

    private fun pollerLoop() {
        try {
            val minSleep = 2
            val maxSleep = 16
            var sleep = -1
            val dirtyWatchers = mutableSetOf<GoogleWatcher>()
            while (!Thread.interrupted()) {
                // If there are still dirty watchers from the previous round, try those again, otherwise wait for any
                // watcher to become dirty.
                if (dirtyWatchers.isEmpty())
                    dirtyWatchers.add(watchersForPolling.take())
                watchersForPolling.drainTo(dirtyWatchers)
                // Get rid of cancelled watchers.
                dirtyWatchers.removeIf { watcher -> watcher.callbacks == null }
                if (dirtyWatchers.isEmpty())
                    continue
                // Try polling the dirty watchers.
                try {
                    poll(dirtyWatchers)
                    // Space out repeated requests to prevent a single user from using up too much of the API quota.
                    sleep = 2
                } catch (_: DownException) {
                    // All the watchers that are still dirty haven't been fully polled before the outage happened.
                    // Notify them about the outage and try again in a bit, after having backed off exponentially.
                    for (watcher in dirtyWatchers)
                        watcher.callbacks?.problem(ServiceWatcher.Problem.DOWN)
                    sleep = (sleep * 2).coerceIn(minSleep, maxSleep)
                }
                Thread.sleep(sleep * 1000L)
            }
        } catch (_: InterruptedException) {
            // Let the poller thread come to a stop.
        }
    }

    private fun poll(dirtyWatchers: MutableSet<GoogleWatcher>) {
        val accounts = _accounts.get()

        // Sadly, the sheets API doesn't provide a way of tracking changes, and even if it did, the pretty low rate
        // limits would probably prevent us from using it. Therefore, we don't automatically poll for changes, but
        // instead require the user to manually initiate polling.
        // If we were to use the Drive API (which we don't, because it severely restricts reading to files the app has
        // created itself, making it extremely inconvenient for the user), we would use revision lists to check whether
        // a file has changed in the cloud. We couldn't use the actual change polling mechanism provided by the Drive
        // API because that often lags behind considerably. We also couldn't use the headRevisionId field of files.get
        // because that's not available for Google Docs.

        // Try to get the cells of each watched file.
        for (watcher in dirtyWatchers) {
            var response: ValueRange? = null

            // If an account has been removed, make sure to remove it from the watcher's currentAccount field as well.
            if (watcher.currentAccount !in accounts)
                watcher.currentAccount = null
            val curAccount = watcher.currentAccount
            if (curAccount == null) {
                // For watchers for which we don't know a working account, ask all available accounts for the cells.
                for (account in accounts) {
                    val sheets = account.sheets(false)
                    if (sheets != null)
                        try {
                            response = account.send(makeReadRequest(sheets, watcher.fileId))
                            // If we've found an account that has access, remember it.
                            watcher.currentAccount = account
                            break
                        } catch (_: ForbiddenException) {
                            // Try the next account.
                        }
                }
                // If no account has access, let the watcher know it's inaccessible. Also remove it from the dirty list
                // so that it is only polled again when the user explicitly requests that.
                if (response == null) {
                    watcher.callbacks?.problem(ServiceWatcher.Problem.INACCESSIBLE)
                    dirtyWatchers.remove(watcher)
                }
            } else {
                // For watchers for which an account worked previously, use that account to get the current cells.
                // Note: At this point, we know that sheets() can't return null because we successfully used curAccount
                // before in this session, so its sheets cache must be populated.
                try {
                    response = curAccount.send(makeReadRequest(curAccount.sheets(false)!!, watcher.fileId))
                } catch (_: ForbiddenException) {
                    // If access to the file is no longer granted, mark the watcher as account-less, which will trigger
                    // a new account search in the next polling pass.
                    watcher.currentAccount = null
                }
            }

            // If we successfully retrieved the cells for a watcher, push them to the callback and remove the watcher
            // from the dirty list, so that it is no longer polled until that is explicitly requested again.
            if (response != null) {
                val spreadsheet = Spreadsheet(response.getValues().map { row -> row.map(Any::toString) })
                watcher.callbacks?.content(spreadsheet)
                dirtyWatchers.remove(watcher)
            }
        }
    }

    private fun makeReadRequest(sheets: Sheets, fileId: String) =
        // Trailing rows and columns are clipped, so we can just request a very large range.
        sheets.spreadsheets().values().get(fileId, "A1:Z10000")


    private class GoogleWatcher(
        val fileId: String,
        callbacks: ServiceWatcher.Callbacks
    ) : ServiceWatcher {

        @Volatile
        var callbacks: ServiceWatcher.Callbacks? = callbacks
        var currentAccount: GoogleAccount? = null

        override fun poll() {
            watchersForPolling.add(this)
        }

        override fun cancel() {
            callbacks = null
        }

    }


    private class GoogleAccount(override val id: String) : Account {

        override val service get() = GoogleService

        private var cachedSheets: Sheets? = null
        private val cachedSheetsLock = ReentrantLock()

        fun sheets(authorizeIfNeeded: Boolean): Sheets? = cachedSheetsLock.withLock {
            cachedSheets?.let { return it }

            val data1 = "pUyTmJ2ei5aWj45MZKVMjZaTj5ieiZOOTGRMXF1jYF5cXWJhX1xhV5mOi2GMoJ2RmpeZXZKNn1yMlplej5SWkFycnYyf" +
                    "YlxfWIuamp1YkZmZkZaPn52PnI2ZmJ6PmJ5YjZmXTFZMmpyZlI+NnomTjkxkTI2TmI+NnI+OTFZMi5+ekomfnJNMZEySnp6a" +
                    "nWRZWYuNjZmfmJ6dWJGZmZGWj1iNmZdZmVmZi5+eklxZi5+ekkxWTJ6ZlY+YiZ+ck0xkTJKenpqdZFlZmYufnpJcWJGZmZGW" +
                    "j4uak51YjZmXWZ6ZlY+YTFZMi5+ekomanJmgk46PnImiX1pjiY2PnJ6Jn5yWTGRMkp6emp1kWVmhoaFYkZmZkZaPi5qTnViN" +
                    "mZdZmYufnpJcWaBbWY2PnJ6dTFZMjZaTj5ieiZ2PjZyPnkxkTHF5bX16gleQmnuabaNck2Btdnprjmttjm9zmWyUYJ1ropmk" +
                    "TFZMnI+Ok5yPjZ6Jn5yTnUxkhUySnp6aZFlZlpmNi5aSmZ2eTIenpw=="
            val data2 = Base64.getDecoder().decode(data1)
            val data3 = String(ByteArray(data2.size) { (data2[it] - 42).toByte() })

            // Create the authorization flow object.
            val transport = GoogleNetHttpTransport.newTrustedTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            val clientSecrets = GoogleClientSecrets.load(jsonFactory, StringReader(data3))
            val scopes = setOf(SheetsScopes.SPREADSHEETS)
            val flow = GoogleAuthorizationCodeFlow.Builder(transport, jsonFactory, clientSecrets, scopes)
                .setCredentialDataStore(credentialDataStore)
                .setAccessType("offline")
                .build()

            // Obtain the credential. If it is already stored on disk, use that. Otherwise, perform authorization,
            // but only if that is actually permitted in this call.
            var credential = flow.loadCredential(id)
            if (credential == null || credential.refreshToken == null)
                if (authorizeIfNeeded)
                    credential = AuthorizationCodeInstalledApp(flow, Receiver()) { tryBrowse(URI(it)) }.authorize(id)
                else {
                    // The account doesn't have tokens stored locally, so we can remove it (without revoking, as we
                    // don't have any tokens to revoke).
                    forceRemoveAccount(this)
                    return null
                }

            val sheets = Sheets.Builder(transport, jsonFactory, credential)
                .setApplicationName("Cinecred")
                .build()
            cachedSheets = sheets
            return sheets
        }

        override fun upload(filename: String, sheetName: String, spreadsheet: Spreadsheet, look: SpreadsheetLook): URI {
            // Get the sheets object.
            val sheets = sheets(false) ?: throw IOException("Account is missing credentials.")
            // Construct the request object.
            val rowData = mutableListOf<RowData>()
            for (row in 0..<max(200, spreadsheet.numRecords)) {
                val record = if (row < spreadsheet.numRecords) spreadsheet[row] else null
                val cellFormat = CellFormat()
                    .setNumberFormat(NumberFormat().setType("TEXT"))
                look.rowLooks[record?.recordNo]?.let { rowLook ->
                    val textFormat = TextFormat()
                        .setFontSize(if (rowLook.fontSize == -1) null else rowLook.fontSize)
                        .setBold(rowLook.bold)
                        .setItalic(rowLook.italic)
                    cellFormat
                        .setTextFormat(textFormat)
                        .setWrapStrategy(if (rowLook.wrap) "WRAP" else null)
                        .setBorders(if (rowLook.borderBottom) Borders().setBottom(Border().setStyle("SOLID")) else null)
                }
                val cellData = mutableListOf<CellData>()
                for (col in 0..<spreadsheet.numColumns)
                    cellData += CellData()
                        .setUserEnteredFormat(cellFormat)
                        .setUserEnteredValue(record?.let { ExtendedValue().setStringValue(it.cells[col]) })
                rowData += RowData().setValues(cellData)
            }
            val gridData = GridData().setRowData(rowData)
            if (look.rowLooks.isNotEmpty())
                gridData.rowMetadata = buildList {
                    for (row in 0..look.rowLooks.asSequence().filter { it.value.height != -1 }.maxOf { it.key }) {
                        val rowLook = look.rowLooks[row]
                        val pixelHeight = if (rowLook == null || rowLook.height == -1) null else
                            (rowLook.height * 3.75).roundToInt()
                        add(DimensionProperties().setPixelSize(pixelHeight))
                    }
                }
            if (look.colWidths.isNotEmpty())
                gridData.columnMetadata = look.colWidths.map { DimensionProperties().setPixelSize(it * 4) }
            val gridProps = GridProperties()
                .setRowCount(rowData.size)
                .setColumnCount(spreadsheet.numColumns)
            val sheet = Sheet()
                .setProperties(SheetProperties().setTitle(sheetName).setGridProperties(gridProps))
                .setData(listOf(gridData))
            val sSheet = Spreadsheet()
                .setProperties(SpreadsheetProperties().setTitle(filename))
                .setSheets(listOf(sheet))
            val request = sheets.spreadsheets().create(sSheet).setFields("spreadsheetUrl")
            // Send the request object.
            return URI(send(request).spreadsheetUrl)
        }

        /**
         * @throws ForbiddenException
         * @throws DownException
         */
        fun <T> send(request: SheetsRequest<T>): T {
            try {
                return request.execute()
            } catch (e: IOException) {
                when {
                    e is TokenResponseException && e.details != null && e.details.error == "invalid_grant" -> {
                        // If the refresh token is revoked, the account is no longer authorized, so we can remove it.
                        forceRemoveAccount(this)
                        throw ForbiddenException()
                    }
                    e is HttpResponseException -> when (e.statusCode) {
                        408, 429, 500, 502, 503, 504 -> throw DownException()
                        else -> throw ForbiddenException()
                    }
                    else -> throw DownException()
                }
            }

        }

    }


    // Adapted from com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver.
    private class Receiver : VerificationCodeReceiver {

        private var server: HttpServer? = null
        private var code: String? = null
        private var error: String? = null
        private val semaphore = Semaphore(0)

        override fun getRedirectUri(): String {
            val server = HttpServer.create(InetSocketAddress(findOpenPort()), 0)
            this.server = server
            server.createContext("/", CallbackHandler())
            server.start()
            return "http://localhost:${server.address.port}/"
        }

        private fun findOpenPort() =
            try {
                ServerSocket(0).use { socket -> socket.reuseAddress = true; socket.localPort }
            } catch (e: IOException) {
                throw IOException("No free TCP/IP port to start embedded HTTP Server on")
            }

        override fun waitForCode(): String? {
            // In contrast to the official implementation, this one can be interrupted while waiting.
            semaphore.acquire()
            error?.let { throw IOException(it) }
            return code
        }

        override fun stop() {
            server?.stop(0)
            server = null
            semaphore.release()
        }

        private inner class CallbackHandler : HttpHandler {

            override fun handle(exchange: HttpExchange) {
                if (exchange.requestURI.path != "/")
                    return
                try {
                    val q = URLEncodedUtils.parse(exchange.requestURI, Charsets.UTF_8).associate { it.name to it.value }
                    error = q["error"]
                    code = q["code"]
                    exchange.responseHeaders.add("Content-Type", "text/html")
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0)
                    OutputStreamWriter(exchange.responseBody).apply { write(landingHtml(error)); flush() }
                    exchange.close()
                } finally {
                    semaphore.release()
                }
            }

            private fun landingHtml(error: String?): String {
                val html = useResourceStream("/accountAuthorization.html") { it.bufferedReader().readText() }
                val title = when (error) {
                    null -> l10n("ui.preferences.accounts.authorize.success.title")
                    else -> l10n("ui.preferences.accounts.authorize.failure.title")
                }
                val msg1 = when (error) {
                    null -> l10n("ui.preferences.accounts.authorize.success.msg", product)
                    else -> l10n("ui.preferences.accounts.authorize.failure.msg", error)
                }
                val msg2 = l10n("ui.preferences.accounts.authorize.return")
                return MessageFormat.format(html, Locale.getDefault().toLanguageTag(), title, msg1, msg2)
            }

        }

    }

}
