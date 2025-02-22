package com.loadingbyte.cinecred.common


fun parseTaggedString(str: String, visitPlain: (String) -> Unit, visitTag: (String) -> Unit) {
    var idx = 0

    while (true) {
        val tagStartIdx = str.indexOfUnescaped("{{", startIdx = idx)
        if (tagStartIdx == -1)
            break
        val tagEndIdx = str.indexOfUnescaped("}}", startIdx = tagStartIdx + 2)
        if (tagEndIdx == -1)
            break

        if (tagStartIdx != idx)
            visitPlain(str.substring(idx, tagStartIdx).unescape())

        visitTag(str.substring(tagStartIdx + 2, tagEndIdx).unescape().trim())

        idx = tagEndIdx + 2
    }

    if (idx != str.length)
        visitPlain(str.substring(idx).unescape())
}

private fun String.indexOfUnescaped(seq: String, startIdx: Int): Int {
    val idx = indexOf(seq, startIdx)
    if (idx <= 0)
        return idx

    return if (countPreceding('\\', idx) % 2 == 0)
        idx
    else
        indexOfUnescaped(seq, idx + seq.length)
}

private fun String.unescape(): String {
    var escIdx = indexOfAny(listOf("\\{{", "\\}}"))
    if (escIdx == -1)
        if (isEmpty() || last() != '\\')
            return this
        else
            escIdx = lastIndex

    val numBackslashes = countPreceding('\\', escIdx + 1)
    return substring(0, escIdx - (numBackslashes - 1) / 2) + substring(escIdx + 1).unescape()
}

// Here, idx is exclusive.
private fun String.countPreceding(char: Char, idx: Int): Int {
    val actualIdx = idx.coerceIn(0, length)
    for (precedingIdx in (actualIdx - 1) downTo 0)
        if (this[precedingIdx] != char)
            return actualIdx - precedingIdx - 1
    return actualIdx
}
