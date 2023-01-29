package com.loadingbyte.cinecred


enum class Platform(val uppercaseLabel: String, val slug: String, val nativesExt: String) {

    WINDOWS("Windows", "windows-x86_64", "dll"),
    MAC_OS("MacOS", "macosx-x86_64", "dylib"),
    LINUX("Linux", "linux-x86_64", "so");

    val lowercaseLabel: String
        get() = uppercaseLabel.decapitalize()

}
