package com.loadingbyte.cinecred.demo

import java.util.*


fun l10nDemo(key: String): String =
    ResourceBundle.getBundle("l10n.demo").getString(key)
