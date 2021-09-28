package com.loadingbyte.cinecred.project

import java.awt.Font


interface StylingContext {
    fun resolveFont(name: String): Font?
}
