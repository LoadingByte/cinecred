package com.loadingbyte.cinecred.projectio.service

import com.google.api.client.auth.oauth2.StoredCredential
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.batch.BatchCallback
import com.google.api.client.googleapis.batch.BatchRequest
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonErrorContainer
import com.google.api.client.http.*
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveRequest
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.RevisionList
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.projectio.OdsFormat
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import com.loadingbyte.cinecred.ui.helper.tryBrowse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.collections.immutable.toPersistentList
import org.apache.http.client.utils.URLEncodedUtils
import java.io.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min


object GoogleServiceProvider : ServiceProvider {

    override val id get() = "google"
    override val label get() = "Google"
    override val services: List<Service> get() = _services.get()

    private val credentialDataStore by lazy {
        StoredCredential.getDefaultDataStore(FileDataStoreFactory(SERVICE_CONFIG_DIR.resolve("google").toFile()))
    }
    private var _services =
        AtomicReference(credentialDataStore.keySet().sorted().map(::GoogleService).toPersistentList())

    private val watchers = mutableListOf<GoogleWatcher>()
    private var watchersPoller: Thread? = null
    private val watchersLock = ReentrantLock()

    override fun addService(serviceId: String) {
        require(_services.get().none { it.id == serviceId }) { "Service ID already in use." }
        val service = GoogleService(serviceId)
        service.drive(authorizeIfNeeded = true)
        _services.updateAndGet { it.add(service) }
        invokeServiceListListeners()
    }

    override fun removeService(service: Service) {
        require(service is GoogleService && service in _services.get()) { "This provider is not responsible." }

        // Try to revoke the refresh token. If this fails, do not remove the service. We don't want non-revoked refresh
        // tokens floating around.
        // This request is manual as revocation is missing from the client library:
        // https://github.com/googleapis/google-oauth-java-client/issues/250
        val refreshToken = credentialDataStore.get(service.id)?.refreshToken
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

        forceRemoveService(service)
    }

    private fun forceRemoveService(service: GoogleService) {
        credentialDataStore.delete(service.id)
        _services.updateAndGet { it.remove(service) }
        invokeServiceListListeners()
    }

    override fun watch(linkCoords: LinkCoords, callbacks: ServiceProvider.WatcherCallbacks): ServiceProvider.Watcher {
        val fileId = linkCoords["file"] ?: throw IOException("Missing 'file' entry in link coordinates.")
        // Just add an uninitialized watcher. All the work will be done by the poller.
        val watcher = GoogleWatcher(fileId, callbacks, null, null)
        watchersLock.withLock {
            watchers.add(watcher)
            if (watchersPoller == null)
                watchersPoller = Thread(::pollerLoop, "GooglePoller").apply { isDaemon = true; start() }
        }
        return watcher
    }

    private fun pollerLoop() {
        try {
            val minSleep = 2
            val maxSleep = 16
            var sleep = minSleep
            var wasDown = false
            while (!Thread.interrupted()) {
                val watchers = watchersLock.withLock { ArrayList(watchers) }
                try {
                    poll(watchers)
                    if (sleep > minSleep) sleep--
                    // If the API was down but is no longer, notify the watchers.
                    if (wasDown) {
                        wasDown = false
                        for (watcher in watchers) watcher.callbacks.status(down = false)
                    }
                } catch (_: DownException) {
                    sleep = min(sleep * 2, maxSleep)
                    // If the API is now down, notify the watchers.
                    if (!wasDown) {
                        wasDown = true
                        for (watcher in watchers) watcher.callbacks.status(down = true)
                    }
                }
                Thread.sleep(sleep * 1000L)
            }
        } catch (_: InterruptedException) {
            // Let the poller thread come to a stop.
        }
    }

    private fun poll(watchers: List<GoogleWatcher>) {
        val services = _services.get()

        // We use revision lists to check whether a file has changed in the cloud. We don't use the actual change
        // polling mechanism provided by the Drive API because that often lags behind considerably. We also don't use
        // the headRevisionId field of files.get because that's not available for Google Docs.
        // Here, we first get the revision list for each watched file.
        val revRequester = Requester()
        val revResponses = watchers.associateWith { watcher ->
            // If a service has been removed, make sure to remove it from the watcher as well.
            if (watcher.currentService !in services)
                watcher.currentService = null
            val curService = watcher.currentService
            if (curService == null) {
                // For watchers for which we don't know a working service, ask all available services for the revisions.
                buildList {
                    for (service in services)
                        service.drive(false)?.let { drive ->
                            add(revRequester.queue(service, makeRevRequest(drive, watcher.fileId)))
                        }
                }
            } else {
                // For watchers for which a service worked previously, use that service to get the current revisions.
                // Note: At this point, we know that drive() can't return null because we successfully used curService
                // before in this session, so its drive cache must be populated.
                listOf(revRequester.queue(curService, makeRevRequest(curService.drive(false)!!, watcher.fileId)))
            }
        }
        revRequester.execute()

        // For each watcher, process the response(s) to the revision list requests.
        for ((watcher, watcherRevResponses) in revResponses) {
            val curService = watcher.currentService
            if (curService == null) {
                // For watchers for which we don't know a working service, find the first service which has access to
                // the watched file, and use it to download the current version of the file.
                // If no service has access, let the watcher know it's inaccessible and then remove it. There's no point
                // in repeatedly trying again and wasting API quota.
                val revResponse = watcherRevResponses.find { !it.forbidden }
                if (revResponse == null) {
                    watcher.callbacks.inaccessible()
                    watcher.cancel()
                } else {
                    watcher.currentService = revResponse.service
                    downloadIfChanged(watcher, revResponse)
                }
            } else {
                // For watchers for which a service worked previously, use it to download the current version of the
                // watched file if that changed. If however access to the file is no longer granted, mark the watcher
                // as service-less, which will trigger a new service search in the next polling pass.
                val revResponse = watcherRevResponses.single()
                if (revResponse.forbidden) {
                    watcher.currentService = null
                    watcher.currentHeadRevisionId = null
                } else
                    downloadIfChanged(watcher, revResponse)
            }
        }
    }

    private fun makeRevRequest(drive: Drive, fileId: String) =
        drive.revisions().list(fileId).setFields("revisions(id)")

    private fun downloadIfChanged(watcher: GoogleWatcher, revResponse: Requester.Response<RevisionList>) {
        // Download the file only if it changed since the previous download, as indicated by a changed head revision ID.
        val headRevisionId = revResponse.result!!.revisions.last().id
        if (watcher.currentHeadRevisionId != headRevisionId) {
            val service = watcher.currentService!!
            val requester = Requester()
            // We know that drive() isn't null because we successfully used the service before to get revision lists.
            val response = requester.queue(service, service.drive(false)!!.files().export(watcher.fileId, "text/csv"))
            val bos = ByteArrayOutputStream()
            requester.executeAndDownloadTo(bos)
            if (response.forbidden) {
                // If access to the file is no longer granted, mark the watcher as service-less, which will trigger a
                // new service search in the next polling pass.
                watcher.currentService = null
                watcher.currentHeadRevisionId = null
            } else {
                // Only remember the new head revision ID after we successfully downloaded the file to ensure that the
                // download is retried if something went wrong.
                watcher.currentHeadRevisionId = headRevisionId
                watcher.callbacks.content(CsvFormat.read(bos.toString()))
            }
        }
    }


    private class GoogleWatcher(
        val fileId: String,
        val callbacks: ServiceProvider.WatcherCallbacks,
        var currentService: GoogleService?,
        var currentHeadRevisionId: String?
    ) : ServiceProvider.Watcher {
        override fun cancel() {
            watchersLock.withLock {
                watchers.remove(this)
                if (watchers.isEmpty()) {
                    watchersPoller?.interrupt()
                    watchersPoller = null
                }
            }
        }
    }


    private class GoogleService(override val id: String) : Service {

        override val provider get() = GoogleServiceProvider

        private var cachedDrive: Drive? = null
        private val cachedDriveLock = ReentrantLock()

        fun drive(authorizeIfNeeded: Boolean): Drive? = cachedDriveLock.withLock {
            cachedDrive?.let { return it }

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
            val scopes = setOf(DriveScopes.DRIVE_FILE)
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
                    // The service doesn't have tokens stored locally, so we can remove it (without revoking, as we
                    // don't have any tokens to revoke).
                    forceRemoveService(this)
                    return null
                }

            val drive = Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName("Cinecred")
                .build()
            cachedDrive = drive
            return drive
        }

        override fun upload(name: String, spreadsheet: Spreadsheet, look: SpreadsheetLook): LinkCoords {
            // Get the drive object.
            val drive = drive(false) ?: throw IOException("Service is missing credentials.")
            // Construct the request object.
            val bos = ByteArrayOutputStream()
            OdsFormat.write(bos, spreadsheet, name, look)
            val content = ByteArrayContent("application/vnd.oasis.opendocument.spreadsheet", bos.toByteArray())
            val metadata = File().setName(name).setMimeType("application/vnd.google-apps.spreadsheet")
            val request = drive.files().create(metadata, content).setFields("id")
            // Send the request object.
            val requester = Requester()
            val response = requester.queue(this, request)
            requester.execute()
            if (response.forbidden)
                throw ForbiddenException()
            return mapOf("file" to response.result!!.id)
        }

    }


    /**
     * This class abstracts requests to the Google Drive API. It lets you queue requests and then execute them. Multiple
     * requests are automatically batched to improve performance. The class also handles errors in a unified way,
     * throwing a [DownException] if the API can't be reached and marking for every request whether access is forbidden.
     */
    private class Requester {

        private var single: Single<*>? = null
        private var batch: BatchRequest? = null

        inline fun <reified T : Any> queue(service: GoogleService, request: DriveRequest<T>): Response<T> =
            queue(service, request, T::class.java)

        private fun <T : Any> queue(service: GoogleService, request: DriveRequest<T>, cls: Class<T>): Response<T> {
            val response = Response<T>(service)
            if (single == null && batch == null)
                single = Single(request, cls, response)
            else {
                single?.let { s ->
                    batch = s.request.abstractGoogleClient.batch()
                    batchQueue(s)
                    single = null
                }
                batchQueue(Single(request, cls, response))
            }
            return response
        }

        private fun <T : Any> batchQueue(s: Single<T>) {
            val httpRequest = s.request.buildHttpRequest()
            httpRequest.interceptor?.let { origInterceptor ->
                httpRequest.setInterceptor { r ->
                    try {
                        origInterceptor.intercept(r)
                    } catch (e: IOException) {
                        handleException(s, e)
                    }
                }
            }
            batch!!.queue(httpRequest, s.dataClass, GoogleJsonErrorContainer::class.java,
                object : BatchCallback<T, GoogleJsonErrorContainer> {
                    override fun onSuccess(t: T, responseHeaders: HttpHeaders) {
                        s.response.result = t
                    }

                    override fun onFailure(e: GoogleJsonErrorContainer, responseHeaders: HttpHeaders) {
                        handleNonSuccessfulStatusCode(s, e.error.code)
                    }
                })
        }

        /** @throws DownException */
        fun execute() {
            single?.let { executeSingle(it, null) }
            batch?.let { executeBatch(it) }
        }

        /** @throws DownException */
        fun executeAndDownloadTo(stream: OutputStream) {
            val single = this.single
            requireNotNull(single) { "Only supported with a single queued request." }
            executeSingle(single, stream)
        }

        private fun <T : Any> executeSingle(s: Single<T>, downloadTo: OutputStream?) {
            try {
                when {
                    downloadTo != null -> s.request.executeAndDownloadTo(downloadTo)
                    else -> s.response.result = s.request.execute()
                }
            } catch (e: IOException) {
                handleException(s, e)
            }
        }

        private fun executeBatch(b: BatchRequest) {
            try {
                b.execute()
            } catch (e: IOException) {
                throw DownException()
            }
        }

        private fun handleException(s: Single<*>, e: IOException) {
            when {
                e is TokenResponseException && e.details != null && e.details.error == "invalid_grant" -> {
                    // If the refresh token has been revoked, the service is no longer authorized, so we can remove it.
                    forceRemoveService(s.response.service)
                    s.response.forbidden = true
                }
                e is HttpResponseException -> handleNonSuccessfulStatusCode(s, e.statusCode)
                else -> throw DownException()
            }
        }

        private fun handleNonSuccessfulStatusCode(s: Single<*>, code: Int) {
            when (code) {
                // https://developers.google.com/drive/api/guides/limits
                // https://cloud.google.com/storage/docs/retry-strategy
                403, 408, 429, 500, 502, 503, 504 -> throw DownException()
                else -> s.response.forbidden = true
            }
        }

        class Response<T : Any>(val service: GoogleService) {
            var result: T? = null
            var forbidden: Boolean = false
        }

        private class Single<T : Any>(val request: DriveRequest<T>, val dataClass: Class<T>, val response: Response<T>)

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
            if (error != null)
                throw IOException("Authorization failed: $error")
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

            private fun landingHtml(error: String?): String = StringBuilder().apply {
                val title = when (error) {
                    null -> l10n("ui.preferences.services.authorizationSuccess.title")
                    else -> l10n("ui.preferences.services.authorizationFailure.title")
                }
                val msg = when (error) {
                    null -> l10n("ui.preferences.services.authorizationSuccess.msg")
                    else -> l10n("ui.preferences.services.authorizationFailure.msg", error)
                }
                append("<html><head><meta charset=\"utf-8\"><title>$title</title></head>")
                append("<body><h1>$title</h1><p>$msg</p></body></html>")
            }.toString()

        }

    }

}
