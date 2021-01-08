package com.loadingbyte.cinecred

import java.text.MessageFormat
import java.util.*


enum class Severity { INFO, WARN, ERROR }


private val L10N = ResourceBundle.getBundle("l10n.strings")
fun l10n(key: String, vararg args: Any?): String = MessageFormat.format(L10N.getString(key), *args)
