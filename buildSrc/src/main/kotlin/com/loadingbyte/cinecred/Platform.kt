package com.loadingbyte.cinecred


enum class Platform(
    val label: String,
    val os: OS,
    val arch: Arch,
) {

    WINDOWS("windows", OS.WINDOWS, Arch.X86_64),
    MAC_X86("macX86", OS.MAC, Arch.X86_64),
    MAC_ARM("macARM", OS.MAC, Arch.ARM64),
    LINUX("linux", OS.LINUX, Arch.X86_64);

    val slug: String = os.slug + "-" + arch.slug
    val slugDeps: String = os.slugDeps + "-" + arch.slug


    enum class OS(val slug: String, val slugDeps: String, val nativesExt: String) {
        WINDOWS("windows", "windows", "dll"),
        MAC("mac", "macosx", "dylib"),
        LINUX("linux", "linux", "so")
    }


    enum class Arch(val slug: String, val slugTemurin: String, val slugWix: String, val slugDebian: String) {
        X86_64("x86_64", "x64", "x64", "amd64"),
        ARM64("arm64", "aarch64", "arm64", "arm64")
    }

}
