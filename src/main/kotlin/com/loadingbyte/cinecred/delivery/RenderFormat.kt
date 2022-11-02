package com.loadingbyte.cinecred.delivery


open class RenderFormat(
    val label: String,
    val fileSeq: Boolean,
    val fileExts: Set<String>,
    val defaultFileExt: String,
    val supportsAlpha: Boolean
) {
    init {
        require(defaultFileExt in fileExts)
    }
}
