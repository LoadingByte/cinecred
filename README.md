Cinecred
========

Create beautiful film credit sequences—without pain.

Visit the website at https://loadingbyte.com/cinecred/ for further information about the nature of this software, as well as manifold download options.

## Running

This software requires JDK 17. Make sure you are using that version when building and manually running it.

To run the main class at `com.loadingbyte.cinecred.Main`, the following VM arguments are required:

    --add-modules jdk.incubator.foreign
    --add-opens java.base/java.lang=ALL-UNNAMED
    --add-opens java.desktop/java.awt.font=ALL-UNNAMED
    --add-opens java.desktop/sun.font=ALL-UNNAMED
    --enable-native-access=ALL-UNNAMED

## Internal: Upgrading HarfBuzz

The following information is only relevant for upgrading the native HarfBuzz library to a newer version.
The compilations currently present in this repository stem from version 3.0.0.

Download the prepared source code tarball of the latest [HarfBuzz release](https://github.com/harfbuzz/harfbuzz/releases) and unpack it.
Compile it on each supported platform using the following commands, which have proven to produce the smallest binaries possible:

    Windows x86_64 (using MSVC):
    cl /LD /O2s /GL /GR- /D"HB_EXTERN=__declspec(dllexport)" <MACROS> <HB_DIR>\src\harfbuzz.cc

    macOS x86_64:
    clang -dynamiclib -std=c++11 -Os -fPIC -flto -fno-rtti -fno-exceptions -DHAVE_PTHREAD <MACROS> <HB_DIR>/src/harfbuzz.cc -o libharfbuzz.dylib

    Linux x86_64:
    clang -shared -fuse-ld=lld -Os -fPIC -flto -fno-rtti -fno-exceptions -DHAVE_PTHREAD <MACROS> <HB_DIR>/src/harfbuzz.cc -o libharfbuzz.so

Point `<HB_DIR>` to the unpacked Harfbuzz release tarball.
Define the following `<MACROS>`, which, among other things, disable unnecessary calls to OS libraries for maximum portability:

    Windows:
    /DHB_DISABLE_DEPRECATED /DHB_NDEBUG /DHB_NO_ATEXIT /DHB_NO_ERRNO /DHB_NO_GETENV /DHB_NO_MMAP /DHB_NO_OPEN /DHB_NO_SETLOCALE

    macOS/Linux:
    -DHB_DISABLE_DEPRECATED -DHB_NDEBUG -DHB_NO_ATEXIT -DHB_NO_ERRNO -DHB_NO_GETENV -DHB_NO_MMAP -DHB_NO_OPEN -DHB_NO_SETLOCALE

Side note 1: We don't use `-DHB_LEAN` since that disables a lot of advanced shaping functionality (like automatic fractions) which we actually wish to support.

Side note 2: The following macros could be enabled on macOS and Linux, however, they are only needed in case we create non-writable blobs, which we don't do at the moment.
So for the time being, we omit these options to maximize portability.

    -DGETPAGESIZE -DHAVE_MPROTECT -DHAVE_SYSCONF -DHAVE_SYS_MMAN_H -DHAVE_UNISTD_H

Now collect the resulting binaries in the `resources/natives` directory.
Next, regenerate the Java bindings using the following command, and then replace the current bindings with the newly generated code:

    jextract --source -d <OUT_DIR> --target-package com.loadingbyte.cinecred.natives.harfbuzz -I <HB_DIR>/src/ <HB_DIR>/src/hb.h \
        --include-function hb_blob_create \
        --include-function hb_buffer_add_utf16 \
        --include-function hb_buffer_create \
        --include-function hb_buffer_destroy \
        --include-function hb_buffer_get_glyph_infos \
        --include-function hb_buffer_get_glyph_positions \
        --include-function hb_buffer_get_length \
        --include-function hb_buffer_set_cluster_level \
        --include-function hb_buffer_set_direction \
        --include-function hb_buffer_set_language \
        --include-function hb_buffer_set_script \
        --include-function hb_face_create_for_tables \
        --include-function hb_face_destroy \
        --include-function hb_feature_from_string \
        --include-function hb_font_create \
        --include-function hb_font_destroy \
        --include-function hb_font_set_scale \
        --include-function hb_language_from_string \
        --include-function hb_shape \
        --include-macro HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES \
        --include-macro HB_DIRECTION_LTR \
        --include-macro HB_DIRECTION_RTL \
        --include-macro HB_FEATURE_GLOBAL_END \
        --include-macro HB_FEATURE_GLOBAL_START \
        --include-macro HB_MEMORY_MODE_WRITABLE \
        --include-macro HB_SCRIPT_ARABIC \
        --include-macro HB_SCRIPT_ARMENIAN \
        --include-macro HB_SCRIPT_BENGALI \
        --include-macro HB_SCRIPT_BOPOMOFO \
        --include-macro HB_SCRIPT_BUHID \
        --include-macro HB_SCRIPT_CANADIAN_SYLLABICS \
        --include-macro HB_SCRIPT_CHEROKEE \
        --include-macro HB_SCRIPT_COMMON \
        --include-macro HB_SCRIPT_COPTIC \
        --include-macro HB_SCRIPT_CYRILLIC \
        --include-macro HB_SCRIPT_DESERET \
        --include-macro HB_SCRIPT_DEVANAGARI \
        --include-macro HB_SCRIPT_ETHIOPIC \
        --include-macro HB_SCRIPT_GEORGIAN \
        --include-macro HB_SCRIPT_GOTHIC \
        --include-macro HB_SCRIPT_GREEK \
        --include-macro HB_SCRIPT_GUJARATI \
        --include-macro HB_SCRIPT_GURMUKHI \
        --include-macro HB_SCRIPT_HAN \
        --include-macro HB_SCRIPT_HANGUL \
        --include-macro HB_SCRIPT_HANUNOO \
        --include-macro HB_SCRIPT_HEBREW \
        --include-macro HB_SCRIPT_HIRAGANA \
        --include-macro HB_SCRIPT_INHERITED \
        --include-macro HB_SCRIPT_INVALID \
        --include-macro HB_SCRIPT_KANNADA \
        --include-macro HB_SCRIPT_KATAKANA \
        --include-macro HB_SCRIPT_KHMER \
        --include-macro HB_SCRIPT_LAO \
        --include-macro HB_SCRIPT_LATIN \
        --include-macro HB_SCRIPT_MALAYALAM \
        --include-macro HB_SCRIPT_MONGOLIAN \
        --include-macro HB_SCRIPT_MYANMAR \
        --include-macro HB_SCRIPT_OGHAM \
        --include-macro HB_SCRIPT_OLD_ITALIC \
        --include-macro HB_SCRIPT_ORIYA \
        --include-macro HB_SCRIPT_RUNIC \
        --include-macro HB_SCRIPT_SINHALA \
        --include-macro HB_SCRIPT_SYRIAC \
        --include-macro HB_SCRIPT_TAGALOG \
        --include-macro HB_SCRIPT_TAGBANWA \
        --include-macro HB_SCRIPT_TAMIL \
        --include-macro HB_SCRIPT_TELUGU \
        --include-macro HB_SCRIPT_THAANA \
        --include-macro HB_SCRIPT_THAI \
        --include-macro HB_SCRIPT_TIBETAN \
        --include-macro HB_SCRIPT_YI \
        --include-struct hb_feature_t \
        --include-struct hb_glyph_info_t \
        --include-struct hb_glyph_position_t \
        --include-typedef hb_destroy_func_t \
        --include-typedef hb_reference_table_func_t

Finally, don't forget to update the license information in the `resources/licences` directory.

## Internal: Releasing

The following information is only relevant for building binaries of a new version and publishing them to the various distribution channels.

1. Run `gradle clean preparePackaging`.
   This will create three folders in `build/packaging/`, one for each OS.
2. Now copy the Windows folder onto a Windows machine and run the `package.bat` script there to build a Windows installer.
   Analogously proceed with macOS and Linux, but use the `package.sh` script for these.
   * Note: On Linux, you will need tools for building DEB and RPM packages, or the script won't work.
   * Note: On Linux, you will need the `repo.loadingbyte.com` PGP key to sign the RPM package.
3. Collect the resulting packaged files into the `publishing/` folder in this repository.
   There is 1 file for Windows and 1 file for macOS. There are 4 files for Linux.
4. Run the `publish-nexus.sh` script to upload all binaries to `repo.loadingbyte.com`.
5. Run the `publish-aur.sh` script to prepare an update of the PKGBUILD script hosted on AUR.
   This script does not push to AUR for safety reasons.
   You will have to do that manually.
