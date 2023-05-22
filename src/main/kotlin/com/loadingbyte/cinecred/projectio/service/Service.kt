package com.loadingbyte.cinecred.projectio.service

import com.loadingbyte.cinecred.common.CONFIG_DIR
import com.loadingbyte.cinecred.common.readToml
import com.loadingbyte.cinecred.common.writeToml
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList


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

    val id: String
    val label: String
    val services: List<Service>

    /** @throws IOException */
    fun addService(serviceId: String)
    /** @throws IOException */
    fun removeService(service: Service)

    /** @throws IOException Only if the coordinates are ill-formatted. Otherwise, calls the appropriate callback. */
    fun watch(linkCoords: LinkCoords, callbacks: WatcherCallbacks): Watcher

    interface Watcher {
        fun cancel()
    }

    interface WatcherCallbacks {
        fun content(spreadsheet: Spreadsheet)
        fun status(down: Boolean)
        fun inaccessible()
    }

}


interface Service {

    val id: String
    val provider: ServiceProvider

    /**
     * @throws ForbiddenException
     * @throws DownException
     * @throws IOException
     */
    fun upload(name: String, spreadsheet: Spreadsheet, look: SpreadsheetLook): LinkCoords

}


class ForbiddenException : IOException()
class DownException : IOException()


typealias LinkCoords = Map<String, String>


data class ProviderAndLinkCoords(val providerId: String, val linkCoords: LinkCoords) {

    /** @throws IOException */
    fun write(file: Path) {
        val toml = mapOf(
            "provider" to providerId,
            "link" to linkCoords
        )
        writeToml(file, toml)
    }


    companion object {

        const val FILE_EXT = "cinecredlink"

        /** @throws IOException */
        fun read(file: Path): ProviderAndLinkCoords {
            val toml = readToml(file)
            val providerId = toml["provider"] as? String ?: ""
            val linkCoords = buildMap {
                (toml["link"] as? Map<*, *>)?.forEach { (k, v) ->
                    if (k is String && v is String)
                        put(k, v)
                }
            }
            return ProviderAndLinkCoords(providerId, linkCoords)
        }

    }

}
