Maintenance
===========

This documentation is relevant only to the maintainers of Cinecred and those who
wish to contribute to its development.


Running
-------

Cinecred requires JDK 17. Gradle's toolchain mechanism enforces that version,
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
    * To sign the RPM, you need the `repo.loadingbyte.com` PGP key and the
      following two lines in your `~/.rpmmacros`
      file: `%_signature gpg`, `%_gpg_name repo.loadingbyte.com`
3. Collect the resulting packaged files from the respective `out/` folders into
   the `publishing/` folder in this repository.
   There are 2 files for Windows, 2 files for macOS x86, 2 files for macOS ARM,
   and 4 files for Linux.
4. Run the `publish-nexus.sh` script to upload all binaries
   to `repo.loadingbyte.com`.
5. Run the `publish-aur.sh` script to prepare an update of the PKGBUILD script
   hosted on AUR.
   This script does not push to AUR for safety reasons.
   You will have to do that manually.


Compiling Native Libraries
--------------------------

Cinecred depends on quite a lot of native libraries. Some of these automatically
come with JAR dependencies, but others need to be rebuilt manually for each
update, which means:

- Building a dynamic library for each platform, which goes into `src/natives`.
    - For macOS, we build on and for macOS 11 because JavaCPP does that too.
    - Also on macOS, cross-compiling for ARM on x86 works flawlessly.
- Generating Java bindings using jextract, which go into `src/main/java`.
- Updating the license file in `src/main/resources/licenses/libraries`.

### HarfBuzz

The compilations currently present in this repository stem from version 7.1.0.

Download the prepared source code tarball of the latest
[HarfBuzz release](https://github.com/harfbuzz/harfbuzz/releases) and unpack it.
Compile it on each supported platform using the following commands:

    Windows x86_64 (using MSVC):
    cl /LD /O2 /GL /GR- /D"HB_EXTERN=__declspec(dllexport)" <MACROS> <HB_DIR>\src\harfbuzz.cc

    macOS x86_64:
    clang -dynamiclib -s -std=c++11 -target x86_64-apple-macos11 -O2 -fPIC -flto -fno-rtti -fno-exceptions -DHAVE_PTHREAD <MACROS> <HB_DIR>/src/harfbuzz.cc -o libharfbuzz.dylib

    macOS arm64:
    clang -dynamiclib -s -std=c++11 -target arm64-apple-macos11 -O2 -fPIC -flto -fno-rtti -fno-exceptions -DHAVE_PTHREAD <MACROS> <HB_DIR>/src/harfbuzz.cc -o libharfbuzz.dylib

    Linux x86_64:
    gcc -shared -s -std=c++11 -O2 -fPIC -flto -fno-rtti -fno-exceptions -DHAVE_PTHREAD <MACROS> <HB_DIR>/src/harfbuzz.cc -o libharfbuzz.so

Point `<HB_DIR>` to the unpacked Harfbuzz release tarball. Define the following
`<MACROS>`, which, among other things, disable unnecessary calls to OS libraries
for maximum portability:

    Windows:
    /DHB_DISABLE_DEPRECATED /DHB_NDEBUG /DHB_NO_ATEXIT /DHB_NO_ERRNO /DHB_NO_GETENV /DHB_NO_MMAP /DHB_NO_OPEN /DHB_NO_SETLOCALE

    macOS/Linux:
    -DHB_DISABLE_DEPRECATED -DHB_NDEBUG -DHB_NO_ATEXIT -DHB_NO_ERRNO -DHB_NO_GETENV -DHB_NO_MMAP -DHB_NO_OPEN -DHB_NO_SETLOCALE

Side note 1: We don't use `-DHB_LEAN` since that disables a lot of advanced
shaping functionality (like automatic fractions) which we actually wish to
support.

Side note 2: The following macros could be enabled on macOS and Linux, however,
they are only needed in case we create non-writable blobs, which we don't do at
the moment. So for the time being, we omit these options to maximize
portability.

    -DGETPAGESIZE -DHAVE_MPROTECT -DHAVE_SYSCONF -DHAVE_SYS_MMAN_H -DHAVE_UNISTD_H

Finally, generate Java bindings using the following command:

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

### zimg

The compilations currently present in this repository stem from version 3.0.4.

Download the source code tarball of the latest
[zimg release](https://github.com/sekrit-twc/zimg/releases) and unpack it. Edit
`src/zimg/api/zimg.h` and look for the following lines near the beginning:

    ...
    #if defined(_WIN32) || defined(__CYGWIN__)
      #define ZIMG_VISIBILITY
    ...

Change that definition to `#define __declspec(dllexport)`. This will make the
Windows DLL actually export symbols.

Next, navigate a bash shell to `src/zimg/`. There, run the following bash script
four times with the arguments `windows`/`mac`/`mac arm64`/`linux`. Each time,
you obtain a series of commands that build the library on that platform. Those
commands need to be executed in a command prompt on that platform again in
`src/zimg/`.

    #!/bin/bash

    ARCH="${2:-x86_64}"
    if [[ "$1" == windows ]]; then
      COMP="clang-cl /c /EHsc /O2 /GS- -flto /DZIMG_X86 /DZIMG_X86_AVX512 /DNDEBUG /I. -Wno-assume"
      LINK="lld-link /DLL /OUT:zimg.dll"
      OBJ="obj"
      # Note: There are no switches for SSE and SSE2; both are always enabled.
      declare -A SIMD_FLAVORS=([sse]="" [sse2]="" [avx]="/arch:AVX" [f16c_ivb]="/arch:AVX -mf16c" [avx2]="/arch:AVX2" [avx512]="/arch:AVX512" [avx512_vnni]="/arch:AVX512 -mavx512vnni")
    else
      if [[ "$1" == mac ]]; then
        COMP="clang++ -target $ARCH-apple-macos11"
        LINK="clang++ -target $ARCH-apple-macos11 -dynamiclib -o libzimg.dylib"
      elif [[ "$1" == linux ]]; then
        COMP="g++"
        LINK="g++ -shared -o libzimg.so"
      fi
      COMP="$COMP -c -std=c++14 -O2 -fPIC -flto -fvisibility=hidden $([[ "$ARCH" == x86_64 ]] && echo "-DZIMG_X86 -DZIMG_X86_AVX512" || echo "-DZIMG_ARM") -DNDEBUG -I."
      LINK="$LINK -s"
      OBJ="o"
      if [[ "$ARCH" == x86_64 ]]; then
        declare -A SIMD_FLAVORS=([sse]="-msse" [sse2]="-msse2" [avx]="-mavx -mtune=sandybridge" [f16c_ivb]="-mavx -mf16c -mtune=ivybridge" [avx2]="-mavx2 -mf16c -mfma -mtune=haswell" [avx512]="-mavx512f -mavx512cd -mavx512vl -mavx512bw -mavx512dq -mtune=skylake-avx512" [avx512_vnni]="-mavx512f -mavx512cd -mavx512vl -mavx512bw -mavx512dq -mavx512vnni -mtune=cascadelake")
      fi
    fi

    shopt -s expand_aliases
    alias FINDCPP="find -not -path '*/$([[ "$ARCH" == x86_64 ]] && echo "arm" || echo "x86")/*' -name '*.cpp'"

    echo $COMP $(FINDCPP $(for flavor in ${!SIMD_FLAVORS[@]}; do echo -not -name "*$flavor.cpp"; done))
    for flavor in ${!SIMD_FLAVORS[@]}; do
      echo $COMP ${SIMD_FLAVORS[$flavor]} $(FINDCPP -name "*$flavor.cpp")
    done
    echo $LINK $(FINDCPP -printf '%f\n' | sed "s/\.cpp/.$OBJ/")

Side note: The above bash script is derived from the GNU autotools build process
officially provided by zlib (and from the MSVC project files). However, as that
process is extremely cumbersome on Windows when it comes to dependencies added
by MinGW-w64, we decided to instead manually employ Window's native CL compiler,
without a proper build system.

Side note 2: On Linux, the compiled libraries will depend on `libstdc++`
and `libgcc_s`. We are fine with these dependencies because the FFmpeg libraries
would declare them anyway.

Finally, generate Java bindings using the following command:

    jextract --source -d <OUT_DIR> --target-package com.loadingbyte.cinecred.natives.zimg <ZIMG_DIR>/src/zimg/api/zimg.h \
        --include-function zimg_filter_graph_build \
        --include-function zimg_filter_graph_free \
        --include-function zimg_filter_graph_get_tmp_size \
        --include-function zimg_filter_graph_process \
        --include-function zimg_get_last_error \
        --include-function zimg_graph_builder_params_default \
        --include-function zimg_image_format_default \
        --include-macro ZIMG_ALPHA_NONE \
        --include-macro ZIMG_ALPHA_STRAIGHT \
        --include-macro ZIMG_API_VERSION \
        --include-macro ZIMG_BUFFER_MAX \
        --include-macro ZIMG_CHROMA_LEFT \
        --include-macro ZIMG_COLOR_RGB \
        --include-macro ZIMG_COLOR_YUV \
        --include-macro ZIMG_CPU_AUTO_64B \
        --include-macro ZIMG_MATRIX_BT470_BG \
        --include-macro ZIMG_MATRIX_BT709 \
        --include-macro ZIMG_MATRIX_RGB \
        --include-macro ZIMG_PIXEL_BYTE \
        --include-macro ZIMG_PIXEL_WORD \
        --include-macro ZIMG_PRIMARIES_709 \
        --include-macro ZIMG_RANGE_FULL \
        --include-macro ZIMG_RANGE_LIMITED \
        --include-macro ZIMG_TRANSFER_BT709 \
        --include-macro ZIMG_TRANSFER_IEC_61966_2_1 \
        --include-struct zimg_image_buffer \
        --include-struct zimg_image_buffer_const \
        --include-struct zimg_graph_builder_params \
        --include-struct zimg_image_format

It is then necessary to replace all occurrences of `C_LONG` with `C_LONG_LONG`,
as the former is 32-bit even on 64-bit Windows machines, while the original C
source code actually specifies 64-bit `size_t` and `ptrdiff_t` types.
