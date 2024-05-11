package com.loadingbyte.cinecred.ui.helper

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
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.name


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

    if (NFD_Init() == NFD_ERROR())
        logCurrentError()
    else
        try {
            Arena.ofConfined().use { arena ->
                val outPathPtr = arena.allocate(ADDRESS)
                val filterHandle = nfdu8filteritem_t.allocate(arena)
                nfdu8filteritem_t.`name$set`(filterHandle, arena.allocateUtf8String(filterName))
                nfdu8filteritem_t.`spec$set`(filterHandle, arena.allocateUtf8String(filterExts.joinToString(",")))
                val defaultPathHandle = folder?.absolutePathString()?.let(arena::allocateUtf8String) ?: NULL
                val result = if (open)
                    NFD_OpenDialogU8(outPathPtr, filterHandle, 1, defaultPathHandle)
                else {
                    val defaultNameHandle = filename?.let(arena::allocateUtf8String) ?: NULL
                    NFD_SaveDialogU8(outPathPtr, filterHandle, 1, defaultPathHandle, defaultNameHandle)
                }
                when (result) {
                    NFD_OKAY() -> return consumeOutPath(outPathPtr)
                    NFD_CANCEL() -> return null
                    NFD_ERROR() -> logCurrentError()
                }
            }
        } finally {
            NFD_Quit()
        }

    val fc = JFileChooser()
    val desc = "$filterName (${filterExts.joinToString()})"
    fc.addChoosableFileFilter(FileNameExtensionFilter(desc, *filterExts.toTypedArray()))
    val option = if (open) {
        fc.currentDirectory = folder?.toFile()
        fc.showOpenDialog(parent)
    } else {
        fc.selectedFile = folder?.resolve(filename!!)?.toFile()
        fc.showSaveDialog(parent)
    }
    return if (option == JFileChooser.APPROVE_OPTION) fc.selectedFile.toPathSafely() else null
}


fun showFolderDialog(
    parent: Window?,
    initialFolder: Path?
): Path? {
    val folder = findFirstExistingAncestorFolder(initialFolder)

    if (NFD_Init() == NFD_ERROR())
        logCurrentError()
    else
        try {
            Arena.ofConfined().use { arena ->
                val outPathPtr = arena.allocate(ADDRESS)
                val defaultPathHandle = folder?.absolutePathString()?.let(arena::allocateUtf8String) ?: NULL
                when (NFD_PickFolderU8(outPathPtr, defaultPathHandle)) {
                    NFD_OKAY() -> return consumeOutPath(outPathPtr)
                    NFD_CANCEL() -> return null
                    NFD_ERROR() -> logCurrentError()
                }
            }
        } finally {
            NFD_Quit()
        }

    val fc = JFileChooser(folder?.toFile())
    fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    // Remove the file type selection.
    val bottomPanel = (fc.getComponent(0) as JPanel).getComponent(3) as JPanel
    bottomPanel.remove(2)  // Remove the file type panel.
    bottomPanel.remove(1)  // Remove the vertical strut.
    val option = fc.showDialog(parent, l10n("ok"))
    return if (option == JFileChooser.APPROVE_OPTION) fc.selectedFile.toPathSafely() else null
}


// If the initial folder doesn't exist, the behavior of file dialogs differ: for example, KDE shows the first existing
// parent, while Swing just defaults to the user's home folder. To unify their behavior, we manually resolve the first
// existing parent folder here.
private fun findFirstExistingAncestorFolder(folder: Path?): Path? =
    if (folder == null || folder.isDirectory()) folder else findFirstExistingAncestorFolder(folder.parent)


private fun logCurrentError() {
    val msgHandle = NFD_GetError()
    LOGGER.error(if (msgHandle == NULL) "Unknown native file dialog error occurred." else msgHandle.getUtf8String(0L))
    NFD_ClearError()
}


private fun consumeOutPath(outPathPtr: MemorySegment): Path? {
    val outPathHandle = outPathPtr.get(ADDRESS, 0L).reinterpret(Long.MAX_VALUE)
    val outPath = outPathHandle.getUtf8String(0L)
    NFD_FreePathU8(outPathHandle)
    return outPath.toPathSafely()
}
