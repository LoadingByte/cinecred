package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.isAccessibleDirectory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.notExists


const val STYLING_FILE_NAME = "Styling.toml"


fun isAllowedToBeProjectDir(path: Path): Boolean {
    // Disallow file names that start or end with whitespace.
    val name = path.name
    if (name.trim() != name)
        return false
    // If the path already exists, ensure that it is a directory that can actually be accessed. If we were to
    // just use isDirectory(), we'd not filter out directories which are not accessible due to various reasons.
    return path.notExists() || path.isAccessibleDirectory()
}

// Do not base the decision on whether a dir is a project dir on the existence of a credits file because that
// file might just be moved around a little by the user at the same time as this function is called.
fun isProjectDir(path: Path): Boolean {
    return isAllowedToBeProjectDir(path) && path.resolve(STYLING_FILE_NAME).exists()
}
