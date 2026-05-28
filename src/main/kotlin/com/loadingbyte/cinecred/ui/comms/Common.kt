package com.loadingbyte.cinecred.ui.comms


const val MIN_ZOOM = 0.1
const val MAX_ZOOM = 5.0
const val ZOOM_FACTOR = 1.1


enum class DockableId { TOOLBAR, PREVIEW, LOG, STYLING, PLAYBACK, DELIVERY }

data class CreditsId(val fileName: String, val spreadsheetName: String)
