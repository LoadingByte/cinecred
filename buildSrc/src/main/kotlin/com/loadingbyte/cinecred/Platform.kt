package com.loadingbyte.cinecred


enum class Platform(val uppercaseLabel: String, val slug: String) {

    WINDOWS("Windows", "windows-x86_64"),
    MAC_OS("MacOS", "macosx-x86_64"),
    LINUX("Linux", "linux-x86_64");

    val lowercaseLabel: String
        get() = uppercaseLabel.decapitalize()

}
