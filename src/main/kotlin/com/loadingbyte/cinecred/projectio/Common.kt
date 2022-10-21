package com.loadingbyte.cinecred.projectio

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory


const val STYLING_FILE_NAME = "Styling.toml"

// Do not base the decision on whether a dir is a project dir on the existence of a credits file because that
// file might just be moved around a little by the user at the same time as this function is called.
fun isProjectDir(path: Path): Boolean {
    return path.isDirectory() && path.resolve(STYLING_FILE_NAME).exists()
}
