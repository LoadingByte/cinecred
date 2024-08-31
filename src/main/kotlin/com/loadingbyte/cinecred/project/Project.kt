package com.loadingbyte.cinecred.project

import kotlinx.collections.immutable.PersistentList


class Project(
    val styling: Styling,
    val credits: PersistentList<Credits>
)
