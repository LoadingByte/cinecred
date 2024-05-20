package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.BitmapWriter.*
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.project.Project
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT470BG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709
import java.nio.file.Path
import java.util.*


abstract class RenderFormat(
    val label: String,
    val fileSeq: Boolean,
    val fileExts: Set<String>,
    val defaultFileExt: String,
    configAssortment: Config.Assortment,
    val widthMod2: Boolean = false,
    val heightMod2: Boolean = false,
    val minWidth: Int? = null,
    val minHeight: Int? = null
) {

    val configs: List<Config> = configAssortment.configs
    val defaultConfig: Config = configAssortment.defaultConfig

    init {
        require(defaultFileExt in fileExts)
    }

    fun <T> options(property: Property<T>, baseConfig: Config, baseDiscard: Collection<Property<*>>): SequencedSet<T> {
        val allowDiffer = HashSet(baseDiscard)
        allowDiffer += property
        val opts = TreeSet<T>()
        for (config in configs)
            if (property in config) {
                val value = config[property]
                if (value !in opts && config.differsOnlyIn(allowDiffer, baseConfig))
                    opts += value
            }
        return opts
    }

    fun <T> default(property: Property<T>): T =
        defaultConfig.getOrDefault(property)

    abstract fun createRenderJob(
        config: Config,
        project: Project,
        pageDefImages: List<DeferredImage>?,
        video: DeferredVideo?,
        fileOrDir: Path,
        filenamePattern: String?
    ): RenderJob


    class Property<T> private constructor(vararg options: T, default: T) {

        val standardOptions: Array<out T> = options
        val standardDefault = default

        companion object {
            val CHANNELS = Property(*Channels.values(), default = Channels.COLOR)
            val RESOLUTION_SCALING_LOG2 = Property(-2, -1, 0, 1, 2, default = 0)
            val FPS_SCALING = Property(1, 2, 3, 4, default = 1)
            val COLOR_PRESET = Property(*ColorPreset.values(), default = ColorPreset.REC_709)
            val DEPTH = Property(8, default = 8)
            val SCAN = Property(*Bitmap.Scan.values(), default = Bitmap.Scan.PROGRESSIVE)
            val TIFF_COMPRESSION = Property(*TIFF.Compression.values(), default = TIFF.Compression.DEFLATE)
            val DPX_COMPRESSION = Property(*DPX.Compression.values(), default = DPX.Compression.NONE)
            val EXR_COMPRESSION = Property(*EXR.Compression.values(), default = EXR.Compression.ZIP)
            val PRORES_PROFILE = Property(*ProResProfile.values(), default = ProResProfile.PRORES_422)
            val DNXHR_PROFILE = Property(*DNxHRProfile.values(), default = DNxHRProfile.DNXHR_HQ)
        }

    }


    class Config private constructor(private val map: PersistentMap<Property<*>, Any?>) {

        operator fun contains(property: Property<*>): Boolean = property in map
        @Suppress("UNCHECKED_CAST")
        operator fun <T> get(property: Property<T>): T = map.getValue(property) as T
        @Suppress("UNCHECKED_CAST")
        fun <T> getOrDefault(property: Property<T>): T = map.getOrDefault(property, property.standardDefault) as T

        fun differsOnlyIn(properties: Set<Property<*>>, other: Config): Boolean =
            map.none { (p, v) -> p !in properties && other.map[p].let { it != null && it != v } }

        class Assortment private constructor(val configs: List<Config>, val defaultConfig: Config) {

            operator fun plus(assortment: Assortment) = Assortment(configs + assortment.configs, defaultConfig)

            operator fun minus(assortment: Assortment): Assortment {
                val newConfigs = configs.filter { c ->
                    assortment.configs.none { d -> c.map.entries.containsAll(d.map.entries) }
                }
                require(defaultConfig in newConfigs)
                return Assortment(newConfigs, defaultConfig)
            }

            operator fun times(assortment: Assortment): Assortment {
                val newConfigs = configs.flatMap { c -> assortment.configs.map { d -> Config(c.map.putAll(d.map)) } }
                val newDefaultConfigMap = defaultConfig.map.putAll(assortment.defaultConfig.map)
                val newDefaultConfig = newConfigs.first { it.map == newDefaultConfigMap }
                return Assortment(newConfigs, newDefaultConfig)
            }

            companion object {

                fun <T> fixed(property: Property<T>, fixed: T) =
                    make(property, listOf(fixed), fixed)

                fun <T> choice(
                    property: Property<T>,
                    vararg options: T = property.standardOptions,
                    default: T = property.standardDefault.let { if (it in options) it else options[0] }
                ) =
                    make(property, options.toList(), default)

                private fun <T> make(property: Property<T>, options: List<T>, default: T): Assortment {
                    require(default in options)
                    val configs = options.map { option -> Config(persistentHashMapOf(property to option)) }
                    val defaultConfig = configs.first { it[property] == default }
                    return Assortment(configs, defaultConfig)
                }

            }

        }

        class Lookup {

            private val map = HashMap<Property<*>, Any?>()

            operator fun <T> set(property: Property<T>, value: T) {
                map[property] = value
            }

            operator fun minusAssign(properties: Set<Property<*>>) {
                map.keys -= properties
            }

            fun findConfig(format: RenderFormat): Config? =
                format.configs.filter { config ->
                    map.none { (p, v) -> config.map[p].let { it != null && it != v } }
                }.maxByOrNull { config ->
                    config.map.count { (p, v) -> format.default(p) == v }
                }

        }

    }


    enum class Channels { COLOR, COLOR_AND_ALPHA, ALPHA }


    enum class ColorPreset(
        val colorSpace: ColorSpace,
        val yuvCoefficients: Bitmap.YUVCoefficients,
        val yuvRange: Bitmap.Range
    ) {
        LINEAR_REC_709(
            ColorSpace.of(ColorSpace.Primaries.BT709, ColorSpace.Transfer.LINEAR),
            Bitmap.YUVCoefficients.of(AVCOL_SPC_BT709),
            Bitmap.Range.LIMITED
        ),
        REC_709(
            ColorSpace.BT709,
            Bitmap.YUVCoefficients.of(AVCOL_SPC_BT709),
            Bitmap.Range.LIMITED
        ),
        SRGB(
            ColorSpace.SRGB,
            Bitmap.YUVCoefficients.of(AVCOL_SPC_BT470BG),
            Bitmap.Range.FULL
        )
    }


    enum class ProResProfile { PRORES_422_PROXY, PRORES_422_LT, PRORES_422, PRORES_422_HQ, PRORES_4444, PRORES_4444_XQ }
    enum class DNxHRProfile { DNXHR_LB, DNXHR_SQ, DNXHR_HQ, DNXHR_HQX, DNXHR_444 }

}
