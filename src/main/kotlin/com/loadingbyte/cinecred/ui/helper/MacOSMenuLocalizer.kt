package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.l10n
import java.lang.foreign.*
import java.lang.foreign.MemorySegment.NULL
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType.methodType


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


    private fun notNull(ptr: MemorySegment, msg: String): MemorySegment = if (ptr != NULL) ptr else
        throw NullPointerException("Unexpected null pointer returned by native call '$msg'")


    // Handle for objc_getClass():

    private fun getClass(name: String): MemorySegment = Arena.ofConfined().use { arena ->
        notNull(getClass.invokeExact(arena.allocateUtf8String(name)) as MemorySegment, "objc_getClass($name)")
    }

    private val getClass = Linker.nativeLinker().downcallHandle(
        SymbolLookup.loaderLookup().find("objc_getClass").get(),
        FunctionDescriptor.of(ADDRESS, ADDRESS)
    )


    // Handles for objc_msgSend():

    private fun msgVoid(receiver: MemorySegment, name: String, arg: MemorySegment) {
        msgSend_void_ptr.invokeExact(receiver, registerName(name), arg)
    }

    private fun msgLong(receiver: MemorySegment, name: String): Long =
        msgSend_long.invokeExact(receiver, registerName(name)) as Long

    private fun msgPtr(receiver: MemorySegment, name: String): MemorySegment =
        notNull(msgSend_ptr.invokeExact(receiver, registerName(name)) as MemorySegment, name)

    private fun msgPtr(receiver: MemorySegment, name: String, arg: Long): MemorySegment =
        notNull(msgSend_ptr_long.invokeExact(receiver, registerName(name), arg) as MemorySegment, "$name:$arg")

    private fun msgPtr(receiver: MemorySegment, name: String, arg: MemorySegment): MemorySegment =
        notNull(msgSend_ptr_ptr.invokeExact(receiver, registerName(name), arg) as MemorySegment, "$name:$arg")

    private val msgSend_void_ptr = msgSendHandle(null, ADDRESS)
    private val msgSend_long = msgSendHandle(JAVA_LONG)
    private val msgSend_ptr = msgSendHandle(ADDRESS)
    private val msgSend_ptr_long = msgSendHandle(ADDRESS, JAVA_LONG)
    private val msgSend_ptr_ptr = msgSendHandle(ADDRESS, ADDRESS)

    private fun msgSendHandle(resLayout: MemoryLayout?, vararg varargLayouts: MemoryLayout) =
        Linker.nativeLinker().downcallHandle(
            SymbolLookup.loaderLookup().find("objc_msgSend").get(),
            if (resLayout == null) FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, *varargLayouts)
            else FunctionDescriptor.of(resLayout, ADDRESS, ADDRESS, *varargLayouts)
        )


    // Handle for sel_registerName():

    private fun registerName(name: String): MemorySegment = Arena.ofConfined().use { arena ->
        notNull(registerName.invokeExact(arena.allocateUtf8String(name)) as MemorySegment, "sel_regName($name)")
    }

    private val registerName = Linker.nativeLinker().downcallHandle(
        SymbolLookup.loaderLookup().find("sel_registerName").get(),
        FunctionDescriptor.of(ADDRESS, ADDRESS)
    )


    // Handles for dispatching onto the main thread:

    private val dispatchMainQ = SymbolLookup.loaderLookup().find("_dispatch_main_q").get()

    private val dispatchSyncF = Linker.nativeLinker().downcallHandle(
        SymbolLookup.loaderLookup().find("dispatch_sync_f").get(),
        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
    )

    private val dispatchAsyncF = Linker.nativeLinker().downcallHandle(
        SymbolLookup.loaderLookup().find("dispatch_async_f").get(),
        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
    )


    // Upcall stubs for methods in this class:

    private val doInitialize = upcallStub("doInitialize")
    private val doLocalize = upcallStub("doLocalize")

    private fun upcallStub(name: String) = Linker.nativeLinker().upcallStub(
        MethodHandles.dropArguments(
            MethodHandles.lookup().bind(this, name, methodType(Void::class.javaPrimitiveType)),
            0, MemorySegment::class.java
        ),
        FunctionDescriptor.ofVoid(ADDRESS),
        Arena.global()
    )


    // The actual localization code:

    private class MenuItem(val l10nKey: String, val defaultTitleRegex: Regex) {
        var handle: MemorySegment? = null
    }

    private val menuItems = listOf(
        MenuItem("macMenu.about", Regex("About.*")),
        MenuItem("macMenu.preferences", Regex("(Preferences|Settings).*")),
        MenuItem("macMenu.services", Regex("Services")),
        MenuItem("macMenu.hideApp", Regex("Hide [^O].*")),
        MenuItem("macMenu.hideOthers", Regex("Hide Others")),
        MenuItem("macMenu.showAll", Regex("Show All")),
        // When the menu is open while we process it, there's an additional, but hidden "Quit" item with two spaces.
        // We don't want to match that item, hence this regex.
        MenuItem("macMenu.quit", Regex("Quit [^ ]*")),
    )

    init {
        dispatchSyncF.invokeExact(dispatchMainQ, NULL, doInitialize)
    }

    private fun doInitialize() {
        val mainMenuHandle = msgPtr(msgPtr(getClass("NSApplication"), "sharedApplication"), "mainMenu")
        check(msgLong(mainMenuHandle, "numberOfItems") > 0)
        val appMenuHandle = msgPtr(msgPtr(mainMenuHandle, "itemAtIndex:", 0), "submenu")
        for (i in 0..<msgLong(appMenuHandle, "numberOfItems")) {
            val itemHandle = msgPtr(appMenuHandle, "itemAtIndex:", i)
            val title = msgPtr(msgPtr(itemHandle, "title"), "UTF8String").reinterpret(Long.MAX_VALUE).getUtf8String(0L)
            menuItems.find { it.defaultTitleRegex.matches(title) }?.handle = itemHandle
        }
    }

    private fun localize() {
        dispatchAsyncF.invokeExact(dispatchMainQ, NULL, doLocalize)
    }

    private fun doLocalize() {
        for (menuItem in menuItems)
            menuItem.handle?.let { itemHandle ->
                val title = l10n(menuItem.l10nKey)
                val titleHandle = Arena.ofConfined().use { arena ->
                    msgPtr(getClass("NSString"), "stringWithUTF8String:", arena.allocateUtf8String(title))
                }
                msgVoid(itemHandle, "setTitle:", titleHandle)
            }
    }

}
