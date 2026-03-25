package com.loadingbyte.cinecred

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject


abstract class Jextract : DefaultTask() {

    @get:Input
    abstract val targetPackage: Property<String>
    @get:Input
    abstract val includeFunctions: ListProperty<String>
    @get:Input
    abstract val includeConstants: ListProperty<String>
    @get:Input
    abstract val includeStructs: ListProperty<String>
    @get:Input
    abstract val includeTypedefs: ListProperty<String>
    @get:Input
    abstract val includeUnions: ListProperty<String>

    @get:InputFile
    abstract val headerFile: RegularFileProperty
    @get:InputDirectory
    @get:Optional
    abstract val includeDir: DirectoryProperty
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val targetPackage = targetPackage.get()
        val headerFile = headerFile.get().asFile
        val outputDir = outputDir.get().asFile

        val cmd = mutableListOf(Tools.jextract())
        cmd += listOf("--target-package", targetPackage)
        cmd += includeFunctions.get().flatMap { listOf("--include-function", it) }
        cmd += includeConstants.get().flatMap { listOf("--include-constant", it) }
        cmd += includeStructs.get().flatMap { listOf("--include-struct", it) }
        cmd += includeTypedefs.get().flatMap { listOf("--include-typedef", it) }
        cmd += includeUnions.get().flatMap { listOf("--include-union", it) }
        // Note: jextract has problems with non-ASCII paths, so instead of using absolute paths, we ensure they are
        // relative to the repository.
        if (includeDir.isPresent)
            cmd += listOf("-I", includeDir.get().asFile.relativeTo(temporaryDir).path)
        cmd += listOf("--output", outputDir.absolutePath, headerFile.relativeTo(temporaryDir).path)

        outputDir.resolve(targetPackage.replace('.', '/')).deleteRecursively()
        execOps.exec { commandLine(cmd).workingDir(temporaryDir) }.rethrowFailure().assertNormalExitValue()

        // jextract interprets size_t and ptrdiff_t as long, which leads to problems on Windows where long is 32-bit.
        // As a hacky solution, we just interpret all C longs as 64-bit. To ensure this doesn't blow up at runtime,
        // we must make sure that there are no longs (only ints and long longs) in the C signatures we use!
        val javaSharedFile = outputDir
            .resolve(targetPackage.replace('.', '/'))
            .resolve("${headerFile.nameWithoutExtension}_h\$shared.java")
        val oldText = javaSharedFile.readText()
        val newText = oldText.replaceFirst(
            "public static final ValueLayout.OfLong C_LONG = " +
                    "(ValueLayout.OfLong) Linker.nativeLinker().canonicalLayouts().get(\"long\");",
            "public static final ValueLayout.OfLong C_LONG = C_LONG_LONG;"
        )
        check(oldText != newText) { "Couldn't find the definition of C_LONG in the generated Java file." }
        javaSharedFile.writeText(newText)
    }

    // We put the includes here to not clutter the buildscript.

    fun addSkcmsIncludes() {
        includeFunctions.addAll(
            "skcms_Matrix3x3_invert",
            "skcms_TransferFunction_eval",
            "skcms_TransferFunction_invert",
            "skcms_sRGB_profile",
            "skcms_XYZD50_profile",
            "skcms_ApproximatelyEqualProfiles",
            "skcms_ParseWithA2BPriority",
            "skcms_GetCHAD",
            "skcms_Transform",
            "skcms_MakeUsableAsDestinationWithSingleCurve",
            "skcms_AdaptToXYZD50",
            "skcms_PrimariesToXYZD50"
        )
        includeConstants.addAll(
            "skcms_Signature_CMYK",
            "skcms_Signature_Gray",
            "skcms_Signature_RGB",
            "skcms_Signature_Lab",
            "skcms_Signature_XYZ",
            "skcms_PixelFormat_G_8",
            "skcms_PixelFormat_RGB_888",
            "skcms_PixelFormat_BGR_888",
            "skcms_PixelFormat_RGBA_8888",
            "skcms_PixelFormat_BGRA_8888",
            "skcms_PixelFormat_RGB_161616LE",
            "skcms_PixelFormat_BGR_161616LE",
            "skcms_PixelFormat_RGBA_16161616LE",
            "skcms_PixelFormat_BGRA_16161616LE",
            "skcms_PixelFormat_RGB_161616BE",
            "skcms_PixelFormat_BGR_161616BE",
            "skcms_PixelFormat_RGBA_16161616BE",
            "skcms_PixelFormat_BGRA_16161616BE",
            "skcms_PixelFormat_RGB_fff",
            "skcms_PixelFormat_BGR_fff",
            "skcms_PixelFormat_RGBA_ffff",
            "skcms_PixelFormat_BGRA_ffff",
            "skcms_AlphaFormat_Opaque",
            "skcms_AlphaFormat_Unpremul",
            "skcms_AlphaFormat_PremulAsEncoded"
        )
        includeStructs.addAll(
            "skcms_Matrix3x3",
            "skcms_Matrix3x4",
            "skcms_TransferFunction",
            "skcms_A2B",
            "skcms_B2A",
            "skcms_CICP",
            "skcms_ICCProfile"
        )
        includeUnions.addAll(
            "skcms_Curve"
        )
    }

    fun addHarfBuzzIncludes() {
        includeFunctions.addAll(
            "hb_blob_create",
            "hb_buffer_add_utf16",
            "hb_buffer_create",
            "hb_buffer_destroy",
            "hb_buffer_get_glyph_infos",
            "hb_buffer_get_glyph_positions",
            "hb_buffer_get_length",
            "hb_buffer_set_cluster_level",
            "hb_buffer_set_direction",
            "hb_buffer_set_language",
            "hb_buffer_set_script",
            "hb_face_create_for_tables",
            "hb_face_destroy",
            "hb_feature_from_string",
            "hb_font_create",
            "hb_font_destroy",
            "hb_font_set_scale",
            "hb_language_from_string",
            "hb_shape"
        )
        includeConstants.addAll(
            "HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES",
            "HB_DIRECTION_LTR",
            "HB_DIRECTION_RTL",
            "HB_FEATURE_GLOBAL_END",
            "HB_FEATURE_GLOBAL_START",
            "HB_MEMORY_MODE_WRITABLE",
            "HB_SCRIPT_ARABIC",
            "HB_SCRIPT_ARMENIAN",
            "HB_SCRIPT_BENGALI",
            "HB_SCRIPT_BOPOMOFO",
            "HB_SCRIPT_BUHID",
            "HB_SCRIPT_CANADIAN_SYLLABICS",
            "HB_SCRIPT_CHEROKEE",
            "HB_SCRIPT_COMMON",
            "HB_SCRIPT_COPTIC",
            "HB_SCRIPT_CYRILLIC",
            "HB_SCRIPT_DESERET",
            "HB_SCRIPT_DEVANAGARI",
            "HB_SCRIPT_ETHIOPIC",
            "HB_SCRIPT_GEORGIAN",
            "HB_SCRIPT_GOTHIC",
            "HB_SCRIPT_GREEK",
            "HB_SCRIPT_GUJARATI",
            "HB_SCRIPT_GURMUKHI",
            "HB_SCRIPT_HAN",
            "HB_SCRIPT_HANGUL",
            "HB_SCRIPT_HANUNOO",
            "HB_SCRIPT_HEBREW",
            "HB_SCRIPT_HIRAGANA",
            "HB_SCRIPT_INHERITED",
            "HB_SCRIPT_INVALID",
            "HB_SCRIPT_KANNADA",
            "HB_SCRIPT_KATAKANA",
            "HB_SCRIPT_KHMER",
            "HB_SCRIPT_LAO",
            "HB_SCRIPT_LATIN",
            "HB_SCRIPT_MALAYALAM",
            "HB_SCRIPT_MONGOLIAN",
            "HB_SCRIPT_MYANMAR",
            "HB_SCRIPT_OGHAM",
            "HB_SCRIPT_OLD_ITALIC",
            "HB_SCRIPT_ORIYA",
            "HB_SCRIPT_RUNIC",
            "HB_SCRIPT_SINHALA",
            "HB_SCRIPT_SYRIAC",
            "HB_SCRIPT_TAGALOG",
            "HB_SCRIPT_TAGBANWA",
            "HB_SCRIPT_TAMIL",
            "HB_SCRIPT_TELUGU",
            "HB_SCRIPT_THAANA",
            "HB_SCRIPT_THAI",
            "HB_SCRIPT_TIBETAN",
            "HB_SCRIPT_YI"
        )
        includeStructs.addAll(
            "hb_feature_t",
            "hb_glyph_info_t",
            "hb_glyph_position_t"
        )
        includeTypedefs.addAll(
            "hb_destroy_func_t",
            "hb_reference_table_func_t"
        )
        includeUnions.addAll(
            "_hb_var_int_t"
        )
    }

    fun addZimgIncludes() {
        includeFunctions.addAll(
            "zimg_get_last_error",
            "zimg_filter_graph_free",
            "zimg_filter_graph_get_tmp_size",
            "zimg_filter_graph_process",
            "zimg_image_format_default",
            "zimg_graph_builder_params_default",
            "zimg_filter_graph_build"
        )
        includeConstants.addAll(
            "ZIMG_API_VERSION",
            "ZIMG_CPU_AUTO_64B",
            "ZIMG_PIXEL_BYTE",
            "ZIMG_PIXEL_WORD",
            "ZIMG_PIXEL_HALF",
            "ZIMG_PIXEL_FLOAT",
            "ZIMG_RANGE_LIMITED",
            "ZIMG_RANGE_FULL",
            "ZIMG_COLOR_GREY",
            "ZIMG_COLOR_RGB",
            "ZIMG_COLOR_YUV",
            "ZIMG_ALPHA_NONE",
            "ZIMG_FIELD_PROGRESSIVE",
            "ZIMG_FIELD_TOP",
            "ZIMG_FIELD_BOTTOM",
            "ZIMG_CHROMA_LEFT",
            "ZIMG_CHROMA_CENTER",
            "ZIMG_CHROMA_TOP_LEFT",
            "ZIMG_CHROMA_TOP",
            "ZIMG_CHROMA_BOTTOM_LEFT",
            "ZIMG_CHROMA_BOTTOM",
            "ZIMG_MATRIX_RGB",
            "ZIMG_MATRIX_BT709",
            "ZIMG_MATRIX_UNSPECIFIED",
            "ZIMG_MATRIX_FCC",
            "ZIMG_MATRIX_BT470_BG",
            "ZIMG_MATRIX_ST170_M",
            "ZIMG_MATRIX_ST240_M",
            "ZIMG_MATRIX_YCGCO",
            "ZIMG_MATRIX_BT2020_NCL",
            "ZIMG_MATRIX_BT2020_CL",
            "ZIMG_MATRIX_ST2085_YDZDX",
            "ZIMG_MATRIX_CHROMATICITY_DERIVED_NCL",
            "ZIMG_MATRIX_CHROMATICITY_DERIVED_CL",
            "ZIMG_MATRIX_BT2100_ICTCP",
            "ZIMG_MATRIX_ST2128_IPT_C2",
            "ZIMG_MATRIX_YCGCO_RE",
            "ZIMG_MATRIX_YCGCO_RO",
            "ZIMG_TRANSFER_BT709",
            "ZIMG_TRANSFER_UNSPECIFIED",
            "ZIMG_TRANSFER_BT470_M",
            "ZIMG_TRANSFER_BT470_BG",
            "ZIMG_TRANSFER_BT601",
            "ZIMG_TRANSFER_ST240_M",
            "ZIMG_TRANSFER_LINEAR",
            "ZIMG_TRANSFER_LOG_100",
            "ZIMG_TRANSFER_LOG_316",
            "ZIMG_TRANSFER_IEC_61966_2_4",
            "ZIMG_TRANSFER_BT1361",
            "ZIMG_TRANSFER_IEC_61966_2_1",
            "ZIMG_TRANSFER_BT2020_10",
            "ZIMG_TRANSFER_BT2020_12",
            "ZIMG_TRANSFER_ST2084",
            "ZIMG_TRANSFER_ST428",
            "ZIMG_TRANSFER_ARIB_B67",
            "ZIMG_PRIMARIES_XYZ_D50",
            "ZIMG_PRIMARIES_BT709",
            "ZIMG_PRIMARIES_UNSPECIFIED",
            "ZIMG_PRIMARIES_BT470_M",
            "ZIMG_PRIMARIES_BT470_BG",
            "ZIMG_PRIMARIES_ST170_M",
            "ZIMG_PRIMARIES_ST240_M",
            "ZIMG_PRIMARIES_FILM",
            "ZIMG_PRIMARIES_BT2020",
            "ZIMG_PRIMARIES_ST428",
            "ZIMG_PRIMARIES_ST431_2",
            "ZIMG_PRIMARIES_ST432_1",
            "ZIMG_PRIMARIES_EBU3213_E",
            "ZIMG_RESIZE_POINT",
            "ZIMG_RESIZE_LANCZOS",
            "ZIMG_BUFFER_MAX"
        )
        includeStructs.addAll(
            "zimg_image_buffer_const",
            "zimg_image_buffer",
            "zimg_image_format",
            "zimg_graph_builder_params"
        )
    }

    fun addNFDIncludes() {
        includeFunctions.addAll(
            "NFD_FreePathU8",
            "NFD_Init",
            "NFD_Quit",
            "NFD_OpenDialogU8",
            "NFD_SaveDialogU8",
            "NFD_PickFolderU8",
            "NFD_GetError",
            "NFD_ClearError"
        )
        includeConstants.addAll(
            "NFD_ERROR",
            "NFD_OKAY",
            "NFD_CANCEL"
        )
        includeTypedefs.addAll(
            "nfdu8filteritem_t"
        )
    }

}
