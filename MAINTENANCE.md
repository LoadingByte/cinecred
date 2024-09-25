Maintenance
===========

This documentation is relevant only to the maintainers of Cinecred and those who
wish to contribute to its development.


Running
-------

Cinecred requires JDK 21. Gradle's toolchain mechanism enforces that version,
but automatic JDK downloading is disabled.

Depending on your platform, use the command `gradle runOnWindows`,
`gradle runOnMacX86`, `gradle runOnMacARM`, or `gradle runOnLinux` to build the
software, collect all native libraries for the platform, and then run it with
the necessary arguments.


Releasing
---------

This section explains how to build binaries of a new version and to publish them
to the various distribution channels.

1. Run `gradle clean preparePackaging`.
   This will create three folders in `build/packaging/`, one for each OS.
2. Now copy the Windows folder onto a Windows machine and run the `package.bat`
   script there to build a Windows installer.
   Analogously proceed with macOS x86, macOS ARM, and Linux, but use the
   `package.sh` script for these.
    * On Linux, you need the following tools to build DEB and RPM
      packages: `dpkg-deb`, `rpmbuild`, `rpmsign`
    * To sign the RPM, you need the private `cinecred.com` PGP key and the
      following two lines in your `~/.rpmmacros`
      file: `%_signature gpg`, `%_gpg_name cinecred.com`
3. Upload the resulting packaged files from the `out/` folders to the website.
   There are 2 files for Windows, 2 files for macOS x86, 2 files for macOS ARM,
   and 4 files for Linux.
4. The Linux script prepares the AUR repo, but does not push for safety reasons.
   You will have to do that manually.


Compiling Native Libraries
--------------------------

Cinecred depends on quite a lot of native libraries. Some of these automatically
come with JAR dependencies, but others need to be rebuilt manually for each
update, which means:

- Building a dynamic library for each platform, which goes into `src/natives`.
    - On Windows, you need the Build Tools for Visual Studio (both MSVC and
      Clang, which must be explicitly selected in the Visual Studio Installer).
    - On macOS, you need the Xcode command line tools.
    - On Linux, you need both GCC and Clang.
    - To build Skia, you also need Python and Git on all platforms. If there are
      Python errors, just running the Gradle task again usually works. And on
      Windows, the build works best when launched from the Git Bash.
    - For macOS, we build on and for macOS 11 as Skia and JavaCPP do that too.
    - Also on macOS, cross-compiling for ARM on x86 works flawlessly.
    - For Linux, we build on Debian 11 to guarantee a low enough GLIBC version.
- Generating Java bindings using jextract, which go into `src/main/java`.

This process is fully automated using Gradle. On Windows, macOS x86, and Linux
respectively, run

    gradlew[.bat] build(Skia|SkiaCAPI|HarfBuzz|Zimg|NFD|DeckLinkCAPI)For(Windows|MacX86|MacARM|Linux)

to build the native libraries and put them into the source tree. Then, on any
machine, run

    gradlew[.bat] jextract(SkiaCAPI|Skcms|HarfBuzz|Zimg|NFD|DeckLinkCAPI)

to regenerate the Java bindings and also put them into the source tree.
