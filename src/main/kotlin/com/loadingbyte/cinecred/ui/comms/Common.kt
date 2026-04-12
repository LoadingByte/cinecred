package com.loadingbyte.cinecred.ui.comms


const val MAX_ZOOM = 3
const val ZOOM_INCREMENT = 0.1


enum class DockableId { TOOLBAR, PREVIEW, LOG, STYLING, PLAYBACK, DELIVERY }

data class CreditsId(val fileName: String, val spreadsheetName: String)
