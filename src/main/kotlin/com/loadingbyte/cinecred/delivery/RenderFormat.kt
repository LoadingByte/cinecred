package com.loadingbyte.cinecred.delivery


abstract class RenderFormat(
    val fileSeq: Boolean,
    val fileExts: Set<String>,
    val defaultFileExt: String,
    val supportsAlpha: Boolean
) {

    abstract val label: String
    abstract val notice: String?

    init {
        require(defaultFileExt in fileExts)
    }

}
