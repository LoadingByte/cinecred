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

    @get:InputFile
    abstract val headerFile: RegularFileProperty
    @get:InputDirectory
    @get:Optional
    abstract val includeDir: DirectoryProperty
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    /** We put these here to not clutter the buildscript. */
    fun addHarfBuzzIncludes() {
        includeFunctions.apply {
            add("hb_blob_create")
            add("hb_buffer_add_utf16")
            add("hb_buffer_create")
            add("hb_buffer_destroy")
            add("hb_buffer_get_glyph_infos")
            add("hb_buffer_get_glyph_positions")
            add("hb_buffer_get_length")
            add("hb_buffer_set_cluster_level")
            add("hb_buffer_set_direction")
            add("hb_buffer_set_language")
            add("hb_buffer_set_script")
            add("hb_face_create_for_tables")
            add("hb_face_destroy")
            add("hb_feature_from_string")
            add("hb_font_create")
            add("hb_font_destroy")
            add("hb_font_set_scale")
            add("hb_language_from_string")
            add("hb_shape")
        }
        includeConstants.apply {
            add("HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES")
            add("HB_DIRECTION_LTR")
            add("HB_DIRECTION_RTL")
            add("HB_FEATURE_GLOBAL_END")
            add("HB_FEATURE_GLOBAL_START")
            add("HB_MEMORY_MODE_WRITABLE")
            add("HB_SCRIPT_ARABIC")
            add("HB_SCRIPT_ARMENIAN")
            add("HB_SCRIPT_BENGALI")
            add("HB_SCRIPT_BOPOMOFO")
            add("HB_SCRIPT_BUHID")
            add("HB_SCRIPT_CANADIAN_SYLLABICS")
            add("HB_SCRIPT_CHEROKEE")
            add("HB_SCRIPT_COMMON")
            add("HB_SCRIPT_COPTIC")
            add("HB_SCRIPT_CYRILLIC")
            add("HB_SCRIPT_DESERET")
            add("HB_SCRIPT_DEVANAGARI")
            add("HB_SCRIPT_ETHIOPIC")
            add("HB_SCRIPT_GEORGIAN")
            add("HB_SCRIPT_GOTHIC")
            add("HB_SCRIPT_GREEK")
            add("HB_SCRIPT_GUJARATI")
            add("HB_SCRIPT_GURMUKHI")
            add("HB_SCRIPT_HAN")
            add("HB_SCRIPT_HANGUL")
            add("HB_SCRIPT_HANUNOO")
            add("HB_SCRIPT_HEBREW")
            add("HB_SCRIPT_HIRAGANA")
            add("HB_SCRIPT_INHERITED")
            add("HB_SCRIPT_INVALID")
            add("HB_SCRIPT_KANNADA")
            add("HB_SCRIPT_KATAKANA")
            add("HB_SCRIPT_KHMER")
            add("HB_SCRIPT_LAO")
            add("HB_SCRIPT_LATIN")
            add("HB_SCRIPT_MALAYALAM")
            add("HB_SCRIPT_MONGOLIAN")
            add("HB_SCRIPT_MYANMAR")
            add("HB_SCRIPT_OGHAM")
            add("HB_SCRIPT_OLD_ITALIC")
            add("HB_SCRIPT_ORIYA")
            add("HB_SCRIPT_RUNIC")
            add("HB_SCRIPT_SINHALA")
            add("HB_SCRIPT_SYRIAC")
            add("HB_SCRIPT_TAGALOG")
            add("HB_SCRIPT_TAGBANWA")
            add("HB_SCRIPT_TAMIL")
            add("HB_SCRIPT_TELUGU")
            add("HB_SCRIPT_THAANA")
            add("HB_SCRIPT_THAI")
            add("HB_SCRIPT_TIBETAN")
            add("HB_SCRIPT_YI")
        }
        includeStructs.apply {
            add("hb_feature_t")
            add("hb_glyph_info_t")
            add("hb_glyph_position_t")
        }
        includeTypedefs.apply {
            add("hb_destroy_func_t")
            add("hb_reference_table_func_t")
        }
    }

    @TaskAction
    fun run() {
        val targetPackage = targetPackage.get()
        val headerFile = headerFile.get().asFile
        val outputDir = outputDir.get().asFile

        val cmd = mutableListOf(Tools.jextractJava(project), "-Djextract.constants.per.class=1000")
        cmd += listOf("-m", "org.openjdk.jextract/org.openjdk.jextract.JextractTool")
        cmd += listOf("--source", "--target-package", targetPackage)
        cmd += includeFunctions.get().flatMap { listOf("--include-function", it) }
        cmd += includeConstants.get().flatMap { listOf("--include-constant", it) }
        cmd += includeStructs.get().flatMap { listOf("--include-struct", it) }
        cmd += includeTypedefs.get().flatMap { listOf("--include-typedef", it) }
        if (includeDir.isPresent)
            cmd += listOf("-I", includeDir.get().asFile.absolutePath)
        cmd += listOf("--output", outputDir.absolutePath, headerFile.absolutePath)

        outputDir.resolve(targetPackage.replace('.', '/')).deleteRecursively()
        execOps.exec { commandLine(cmd) }.rethrowFailure().assertNormalExitValue()
    }

}
