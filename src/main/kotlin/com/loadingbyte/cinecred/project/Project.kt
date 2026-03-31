package com.loadingbyte.cinecred.project

import kotlinx.collections.immutable.PersistentList
import java.net.URI


class Project(
    val styling: Styling,
    val creditsBooks: PersistentList<CreditsBook>
)


class CreditsBook(
    val fileName: String,
    val uri: URI,
    val credits: PersistentList<Credits>
)
