package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.util.SystemFileChooser
import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.toPathSafely
import com.loadingbyte.cinecred.natives.nfd.nfd_h.*
import com.loadingbyte.cinecred.natives.nfd.nfdu8filteritem_t
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemorySegment.NULL
import java.lang.foreign.ValueLayout.ADDRESS
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.name


var useAppleScriptFileChooser = false

/** @param initialFolderOrFile A folder when [open] is true, and a file otherwise. */
fun showFileDialog(
    parent: Window?,
    open: Boolean,
    filterName: String,
    filterExts: List<String>,
    initialFolderOrFile: Path?
): Path? {
    val folder: Path?
    val filename: String?
    if (open) {
        folder = findFirstExistingAncestorFolder(initialFolderOrFile)
        filename = null
    } else {
        folder = findFirstExistingAncestorFolder(initialFolderOrFile?.parent)
        filename = initialFolderOrFile?.name
    }

    if (SystemInfo.isMacOS && useAppleScriptFileChooser) {
        val method: String
        val args = mutableListOf<String>()
        if (open) {
            method = "choose file"
            args += "of type {${filterExts.joinToString { "\"$it\"" }}}"
        } else {
            method = "choose file name"
            if (filename != null)
                args += "default name \"$filename\""
        }
        if (folder != null)
            args += "default location \"${folder.absolutePathString()}\""
        return showFileOrFolderDialogViaAppleScript("POSIX path of($method ${args.joinToString(" ")})")
    }

    // FlatLaf uses GTK on Linux, but we want to use our own portals-based library instead.
    if (SystemInfo.isLinux) {
        // Don't block the AWT thread with native calls to avoid rare hangs.
        val ref = AtomicReference<Path?>()
        Thread({
            try {
                if (NFD_Init() == NFD_ERROR()) logCurrentError() else Arena.ofConfined().use { arena ->
                    val outPathPtr = arena.allocate(ADDRESS)
                    val filterHandle = nfdu8filteritem_t.allocate(arena)
                    nfdu8filteritem_t.name(filterHandle, arena.allocateFrom(filterName))
                    nfdu8filteritem_t.spec(filterHandle, arena.allocateFrom(filterExts.joinToString(",")))
                    val defaultPathHandle = folder?.absolutePathString()?.let(arena::allocateFrom) ?: NULL
                    val result = if (open)
                        NFD_OpenDialogU8(outPathPtr, filterHandle, 1, defaultPathHandle)
                    else {
                        val defaultNameHandle = filename?.let(arena::allocateFrom) ?: NULL
                        NFD_SaveDialogU8(outPathPtr, filterHandle, 1, defaultPathHandle, defaultNameHandle)
                    }
                    when (result) {
                        NFD_OKAY() -> ref.set(consumeOutPath(outPathPtr))
                        NFD_CANCEL() -> ref.set(SENTINEL)
                        NFD_ERROR() -> logCurrentError()
                    }
                }
            } finally {
                NFD_Quit()
            }
        }, "FileDialog").apply { start(); join() }
        ref.get()?.let { return if (it === SENTINEL) null else it }
    }

    val fc = SystemFileChooser()
    val desc = "$filterName (${filterExts.joinToString()})"
    fc.addChoosableFileFilter(SystemFileChooser.FileNameExtensionFilter(desc, *filterExts.toTypedArray()))
    fc.isAcceptAllFileFilterUsed = false
    val option = if (open) {
        fc.currentDirectory = folder?.toFile()
        fc.showOpenDialog(parent)
    } else {
        fc.selectedFile = folder?.resolve(filename!!)?.toFile()
        fc.showSaveDialog(parent)
    }
    return if (option == SystemFileChooser.APPROVE_OPTION) fc.selectedFile.toPathSafely() else null
}


fun showFolderDialog(
    parent: Window?,
    initialFolder: Path?
): Path? {
    val folder = findFirstExistingAncestorFolder(initialFolder)

    if (SystemInfo.isMacOS && useAppleScriptFileChooser) {
        val args = mutableListOf<String>()
        if (folder != null)
            args += "default location \"${folder.absolutePathString()}\""
        return showFileOrFolderDialogViaAppleScript("POSIX path of (choose folder ${args.joinToString(" ")})")
    }

    // FlatLaf uses GTK on Linux, but we want to use our own portals-based library instead.
    if (SystemInfo.isLinux) {
        // Don't block the AWT thread with native calls to avoid rare hangs.
        val ref = AtomicReference<Path?>()
        Thread({
            try {
                if (NFD_Init() == NFD_ERROR()) logCurrentError() else Arena.ofConfined().use { arena ->
                    val outPathPtr = arena.allocate(ADDRESS)
                    val defaultPathHandle = folder?.absolutePathString()?.let(arena::allocateFrom) ?: NULL
                    when (NFD_PickFolderU8(outPathPtr, defaultPathHandle)) {
                        NFD_OKAY() -> ref.set(consumeOutPath(outPathPtr))
                        NFD_CANCEL() -> ref.set(SENTINEL)
                        NFD_ERROR() -> logCurrentError()
                    }
                }
            } finally {
                NFD_Quit()
            }
        }, "FileDialog").apply { start(); join() }
        ref.get()?.let { return if (it === SENTINEL) null else it }
    }

    val fc = SystemFileChooser(folder?.toFile())
    fc.fileSelectionMode = SystemFileChooser.DIRECTORIES_ONLY
    val option = fc.showDialog(parent, l10n("ok"))
    return if (option == SystemFileChooser.APPROVE_OPTION) fc.selectedFile.toPathSafely() else null
}


// If the initial folder doesn't exist, the behavior of file dialogs differ: for example, KDE shows the first existing
// parent, while Swing just defaults to the user's home folder. To unify their behavior, we manually resolve the first
// existing parent folder here.
private fun findFirstExistingAncestorFolder(folder: Path?): Path? =
    if (folder == null || folder.isDirectory()) folder else findFirstExistingAncestorFolder(folder.parent)


private fun logCurrentError() {
    val msgHandle = NFD_GetError()
    LOGGER.error(if (msgHandle == NULL) "Unknown native file dialog error occurred." else msgHandle.getString(0L))
    NFD_ClearError()
}


private fun consumeOutPath(outPathPtr: MemorySegment): Path? {
    val outPathHandle = outPathPtr.get(ADDRESS, 0L).reinterpret(Long.MAX_VALUE)
    val outPath = outPathHandle.getString(0L)
    NFD_FreePathU8(outPathHandle)
    return outPath.toPathSafely()
}


private fun showFileOrFolderDialogViaAppleScript(script: String): Path? {
    val process = ProcessBuilder(listOf("osascript", "-")).start()
    process.outputWriter().use { it.write(script) }
    return if (process.waitFor() != 0) null else Path(process.inputReader().readText().removeSuffix("\n"))
}


private val SENTINEL = Path("")
