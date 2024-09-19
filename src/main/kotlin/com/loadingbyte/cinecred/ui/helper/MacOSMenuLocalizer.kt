package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.l10n
import java.lang.foreign.*
import java.lang.foreign.MemorySegment.NULL
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT


/**
 * By default, the macOS application menu is in English, and there doesn't seem to be a way to get macOS to localize it
 * automatically. Instead, this class manually alters the names of the menu entries. The translated names have been
 * taken from https://github.com/martnst/localize-mainmenu/tree/master/languages and are stored in our usual l10n files.
 */
class MacOSMenuLocalizer {

    companion object {

        private var instance: MacOSMenuLocalizer? = null
        private var initialized = false

        fun localize() {
            if (!SystemInfo.isMacOS)
                return
            if (!initialized) {
                initialized = true
                try {
                    instance = MacOSMenuLocalizer()
                } catch (e: Exception) {
                    LOGGER.error("Failed to initialize the macOS menu localizer.", e)
                }
            }
            try {
                instance?.localize()
            } catch (e: Exception) {
                LOGGER.error("Failed to localize the macOS menu.", e)
            }
        }

    }


    private fun getClass(name: String) =
        Arena.ofConfined().use { arena -> objc_getClass.invokeExact(arena.allocateUtf8String(name)) as MemorySegment }

    private val objc_getClass = Linker.nativeLinker().downcallHandle(
        Linker.nativeLinker().defaultLookup().find("objc_getClass").get(),
        FunctionDescriptor.of(ADDRESS, ADDRESS)
    )


    private fun msgNil(receiver: MemorySegment, name: String, arg0: MemorySegment) {
        objc_msgSend_nil_ptr.invokeExact(receiver, registerName(name), arg0)
    }

    private fun msgInt(receiver: MemorySegment, name: String) =
        objc_msgSend_int.invokeExact(receiver, registerName(name)) as Int

    private fun msgPtr(receiver: MemorySegment, name: String) =
        objc_msgSend_ptr.invokeExact(receiver, registerName(name)) as MemorySegment

    private fun msgPtr(receiver: MemorySegment, name: String, arg0: Int) =
        objc_msgSend_ptr_int.invokeExact(receiver, registerName(name), arg0) as MemorySegment

    private fun msgPtr(receiver: MemorySegment, name: String, arg0: MemorySegment) =
        objc_msgSend_ptr_ptr.invokeExact(receiver, registerName(name), arg0) as MemorySegment

    private val objc_msgSend_nil_ptr = objc_msgSend(null, ADDRESS)
    private val objc_msgSend_int = objc_msgSend(JAVA_INT)
    private val objc_msgSend_ptr = objc_msgSend(ADDRESS)
    private val objc_msgSend_ptr_int = objc_msgSend(ADDRESS, JAVA_INT)
    private val objc_msgSend_ptr_ptr = objc_msgSend(ADDRESS, ADDRESS)

    private fun objc_msgSend(resLayout: MemoryLayout?, vararg varargLayouts: MemoryLayout) =
        Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("objc_msgSend").get(),
            if (resLayout == null) FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, *varargLayouts)
            else FunctionDescriptor.of(resLayout, ADDRESS, ADDRESS, *varargLayouts),
            Linker.Option.firstVariadicArg(2)
        )


    private fun registerName(name: String) =
        Arena.ofConfined().use { ar -> sel_registerName.invokeExact(ar.allocateUtf8String(name)) as MemorySegment }

    private val sel_registerName = Linker.nativeLinker().downcallHandle(
        Linker.nativeLinker().defaultLookup().find("sel_registerName").get(),
        FunctionDescriptor.of(ADDRESS, ADDRESS)
    )


    private class MenuItem(val l10nKey: String, val defaultTitleRegex: Regex) {
        var handle: MemorySegment? = null
    }

    private val menuItems = listOf(
        MenuItem("macMenu.about", Regex("About.*")),
        MenuItem("macMenu.preferences", Regex("Preferences.*")),
        MenuItem("macMenu.services", Regex("Services")),
        MenuItem("macMenu.hideApp", Regex("Hide [^O].*")),
        MenuItem("macMenu.hideOthers", Regex("Hide Others")),
        MenuItem("macMenu.showAll", Regex("Show All")),
        // When the menu is open while we process it, there's an additional, but hidden "Quit" item with two spaces.
        // We don't want to match that item, hence this regex.
        MenuItem("macMenu.quit", Regex("Quit [^ ]*")),
    )


    init {
        val appHandle = msgPtr(getClass("NSApplication"), "sharedApplication")
        val appMenuHandle = msgPtr(msgPtr(msgPtr(appHandle, "mainMenu"), "itemAtIndex:", 0), "submenu")
        for (i in 0..<msgInt(appMenuHandle, "numberOfItems").coerceAtMost(11)) {
            val itemHandle = msgPtr(appMenuHandle, "itemAtIndex:", i)
            if (itemHandle == NULL)
                break
            val title = msgPtr(msgPtr(itemHandle, "title"), "UTF8String").reinterpret(Long.MAX_VALUE).getUtf8String(0L)
            menuItems.find { it.defaultTitleRegex.matches(title) }?.handle = itemHandle
        }
    }

    private fun localize() {
        for (menuItem in menuItems)
            menuItem.handle?.let { itemHandle ->
                val title = l10n(menuItem.l10nKey)
                val titleHandle = Arena.ofConfined().use { arena ->
                    msgPtr(getClass("NSString"), "stringWithUTF8String:", arena.allocateUtf8String(title))
                }
                msgNil(itemHandle, "setTitle:", titleHandle)
            }
    }

}
