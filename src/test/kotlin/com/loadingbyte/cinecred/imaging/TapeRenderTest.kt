package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.imaging.Bitmap.Alpha.OPAQUE
import com.loadingbyte.cinecred.imaging.Bitmap.Alpha.STRAIGHT
import com.loadingbyte.cinecred.imaging.Bitmap.Content.INTERLEAVED_FIELDS
import com.loadingbyte.cinecred.imaging.Bitmap.Content.PROGRESSIVE_FRAME
import com.loadingbyte.cinecred.imaging.Bitmap.Scan.*
import com.loadingbyte.cinecred.imaging.ColorSpace.Companion.SRGB
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.setupNatives
import org.bytedeco.ffmpeg.global.avcodec.AV_PROFILE_H264_HIGH_444
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB24
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile


internal class TapeRenderTest {

    @BeforeAll
    fun setup() {
        setupNatives()
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `render deferred video with embedded tape`(case: Case) {
        val fps = FPS(24, 1)
        val videoLength = case.videoFrames.size
        val tapeX = (case.videoWidth - case.tapeWidth) / 2

        val tapePath = if (case.tapeIsImageSeq) createTempDirectory() else createTempFile(suffix = ".mov")
        var rootTape: Tape? = null
        try {
            if (case.tapeIsImageSeq) {
                val bitmapWriter = BitmapWriter.PNG(Bitmap.PixelFormat.Family.RGB, hasAlpha = case.tapeAlpha, SRGB)
                for (frameIdx in 0..<videoLength.coerceAtLeast(2))
                    case.tapeFrame(frameIdx).use { bitmap ->
                        bitmapWriter.convertAndWrite(bitmap, tapePath.resolve("$frameIdx.png"))
                    }
            } else {
                check(!case.tapeAlpha)
                val writerPixFmt = Bitmap.PixelFormat.of(AV_PIX_FMT_RGB24)
                val writerSpec = Bitmap.Spec(
                    case.fullTapeRes, Bitmap.Representation(writerPixFmt, SRGB, OPAQUE), case.tapeScan, case.tapeContent
                )
                VideoWriter(
                    tapePath, writerSpec, fps, "libx264rgb", AV_PROFILE_H264_HIGH_444, mapOf("crf" to "0"), emptyMap()
                ).use { videoWriter ->
                    for (frameIdx in 0..<videoLength)
                        Bitmap.allocate(writerSpec).use { convertedFrame ->
                            case.tapeFrame(frameIdx).use { BitmapConverter.convert(it, convertedFrame) }
                            videoWriter.write(convertedFrame)
                        }
                }
            }
            rootTape = checkNotNull(Tape.recognize(tapePath))
            val rootRep = rootTape.spec.representation
            val tape = rootTape.dependentReinterpretedTape(
                rootRep.range, SRGB, rootRep.yuvCoefficients, rootRep.alpha, case.tapeScan, case.tapeContent
            )

            val defImg = DeferredImage(case.videoWidth.toDouble(), case.tapeHeight.toDouble().toY())
            val embTape = DeferredImage.EmbeddedTape(
                tape,
                cropLeft = case.tapeCrop.left,
                cropRight = case.tapeCrop.right,
                cropTop = case.tapeCrop.top,
                cropBottom = case.tapeCrop.bottom,
                flipV = case.tapeFlipV
            )
            defImg.drawEmbeddedTape(embTape, tapeX.toDouble(), 0.0.toY())

            val video = DeferredVideo(Resolution(case.videoWidth, case.videoHeight), fps)
            when (val behavior = case.tapeBehavior) {
                is Static ->
                    video.playStatic(defImg, videoLength, -behavior.y.toDouble(), 1.0)
                is Scroll ->
                    video.playScroll(
                        defImg, videoLength, behavior.speed.toDouble(),
                        -behavior.firstY.toDouble(), -behavior.lastY.toDouble(), 1.0
                    )
            }

            val videoRep = Bitmap.Representation(Bitmap.PixelFormat.of(AV_PIX_FMT_RGBA), SRGB, STRAIGHT)
            val videoSpec = Bitmap.Spec(video.resolution, videoRep, case.videoScan, case.videoContent)
            val backend = DeferredVideo.BitmapBackend(
                video, emptyList(), listOf(DeferredImage.TAPES), grounding = null, videoSpec
            )

            for ((frameIdx, videoFrame) in case.videoFrames.withIndex())
                backend.materializeFrame(frameIdx).also(::assertNotNull)!!.use { frameBitmap ->
                    val frame = frameBitmap.getI(case.videoWidth, byteOrder = ByteOrder.BIG_ENDIAN)
                    for (x in 0..<case.videoWidth) {
                        val expectedRows = List(case.videoHeight) { y ->
                            if (x in tapeX..<tapeX + case.tapeWidth &&
                                y in videoFrame.offset..<videoFrame.offset + videoFrame.rows.size
                            )
                                videoFrame.rows[y - videoFrame.offset]
                            else
                                "#00000000"
                        }
                        val actualRows = List(case.videoHeight) { y ->
                            case.tapeSourceString(frame[y * case.videoWidth + x])
                        }
                        assertEquals(expectedRows, actualRows) { "Video frame $frameIdx has wrong colors in col $x." }
                    }
                }
        } finally {
            rootTape?.close()
            tapePath.toFile().deleteRecursively()
        }
    }

    fun cases() = sequenceOf(
        Case(
            videoHeight = 6, PROGRESSIVE, tapeHeight = 4, PROGRESSIVE, Static(1),
            Frame(1, "0.0", "0.1", "0.2", "0.3"),
            Frame(1, "1.0", "1.1", "1.2", "1.3"),
            Frame(1, "2.0", "2.1", "2.2", "2.3")
        ),
        Case(
            videoHeight = 6, PROGRESSIVE, tapeHeight = 4, PROGRESSIVE, Scroll(6, -4, 1),
            Frame(5, "0.0"),
            Frame(4, "1.0", "1.1"),
            Frame(3, "2.0", "2.1", "2.2"),
            Frame(2, "3.0", "3.1", "3.2", "3.3"),
            Frame(1, "4.0", "4.1", "4.2", "4.3"),
            Frame(0, "5.0", "5.1", "5.2", "5.3"),
            Frame(0, "6.1", "6.2", "6.3"),
            Frame(0, "7.2", "7.3"),
            Frame(0, "8.3")
        ),
        Case(
            videoHeight = 6, PROGRESSIVE, tapeHeight = 4, PROGRESSIVE, Scroll(6, -4, 2),
            Frame(4, "0.0", "0.1"),
            Frame(2, "1.0", "1.1", "1.2", "1.3"),
            Frame(0, "2.0", "2.1", "2.2", "2.3"),
            Frame(0, "3.2", "3.3")
        ),
        Case(
            videoHeight = 6, PROGRESSIVE, tapeHeight = 4, PROGRESSIVE, Scroll(6, -6, 3),
            Frame(3, "0.0", "0.1", "0.2"),
            Frame(0, "1.0", "1.1", "1.2", "1.3"),
            Frame(0, "2.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, PROGRESSIVE, Static(1),
            Frame(1, "0.0", "0.1", "0.2", "0.3"),
            Frame(1, "1.0", "1.1", "1.2", "1.3"),
            Frame(1, "2.0", "2.1", "2.2", "2.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, INTERLACED_TOP_FIELD_FIRST, Static(1),
            Frame(1, "0.0", "0.1", "0.2", "0.3"),
            Frame(1, "1.0", "0.1", "1.2", "0.3"),
            Frame(1, "2.0", "1.1", "2.2", "1.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, INTERLACED_BOT_FIELD_FIRST, Static(1),
            Frame(1, "0.0", "0.1", "0.2", "0.3"),
            Frame(1, "1.0", "1.1", "1.2", "1.3"),
            Frame(1, "2.0", "2.1", "2.2", "2.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_BOT_FIELD_FIRST, tapeHeight = 4, INTERLACED_TOP_FIELD_FIRST, Static(1),
            Frame(1, "0.0", "0.1", "0.2", "0.3"),
            Frame(1, "1.0", "1.1", "1.2", "1.3"),
            Frame(1, "2.0", "2.1", "2.2", "2.3")
        ),
        Case(
            videoHeight = 8, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, INTERLACED_TOP_FIELD_FIRST, Static(2),
            Frame(2, "0.0", "0.1", "0.2", "0.3"),
            Frame(2, "1.0", "1.1", "1.2", "1.3"),
            Frame(2, "2.0", "2.1", "2.2", "2.3")
        ),
        Case(
            videoHeight = 8, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, INTERLACED_BOT_FIELD_FIRST, Static(2),
            Frame(2, "0.0", "0.1", "0.2", "0.3"),
            Frame(2, "0.0", "1.1", "0.2", "1.3"),
            Frame(2, "1.0", "2.1", "1.2", "2.3")
        ),
        Case(
            videoHeight = 8, INTERLACED_BOT_FIELD_FIRST, tapeHeight = 4, INTERLACED_TOP_FIELD_FIRST, Static(2),
            Frame(2, "0.0", "0.1", "0.2", "0.3"),
            Frame(2, "1.0", "0.1", "1.2", "0.3"),
            Frame(2, "2.0", "1.1", "2.2", "1.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, PROGRESSIVE, Scroll(6, -4, 2),
            Frame(5, "0.1"),
            Frame(3, "1.1", "1.1", "1.3"),
            Frame(1, "2.1", "2.1", "2.3", "2.3"),
            Frame(0, "3.1", "3.3", "3.3"),
            Frame(0, "4.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, INTERLACED_TOP_FIELD_FIRST, Scroll(6, -4, 2),
            Frame(5, "0.1"),
            Frame(3, "1.1", "0.1", "1.3"),
            Frame(1, "2.1", "1.1", "2.3", "1.3"),
            Frame(0, "2.1", "3.3", "2.3"),
            Frame(0, "3.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, INTERLACED_BOT_FIELD_FIRST, Scroll(6, -4, 2),
            Frame(5, "0.1"),
            Frame(3, "1.1", "1.1", "1.3"),
            Frame(1, "2.1", "2.1", "2.3", "2.3"),
            Frame(0, "3.1", "3.3", "3.3"),
            Frame(0, "4.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_BOT_FIELD_FIRST, tapeHeight = 4, PROGRESSIVE, Scroll(6, -4, 2),
            Frame(4, "0.0", "0.0"),
            Frame(2, "1.0", "1.0", "1.2", "1.2"),
            Frame(0, "2.0", "2.0", "2.2", "2.2"),
            Frame(0, "3.2", "3.2")
        ),
        Case(
            videoHeight = 6, INTERLACED_BOT_FIELD_FIRST, tapeHeight = 4, INTERLACED_TOP_FIELD_FIRST, Scroll(6, -4, 2),
            Frame(4, "0.0", "0.0"),
            Frame(2, "1.0", "1.0", "1.2", "1.2"),
            Frame(0, "2.0", "2.0", "2.2", "2.2"),
            Frame(0, "3.2", "3.2")
        ),
        Case(
            videoHeight = 6, INTERLACED_BOT_FIELD_FIRST, tapeHeight = 4, INTERLACED_BOT_FIELD_FIRST, Scroll(6, -4, 2),
            Frame(4, "0.0", "0.0"),
            Frame(2, "1.0", "0.0", "1.2", "0.2"),
            Frame(0, "2.0", "1.0", "2.2", "1.2"),
            Frame(0, "3.2", "2.2")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, PROGRESSIVE, Scroll(6, -6, 3),
            Frame(3, "0.0", "#00000000", "0.2"),
            Frame(1, "1.1", "1.0", "1.3", "1.2"),
            Frame(0, "2.1", "#00000000", "2.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_BOT_FIELD_FIRST, tapeHeight = 4, PROGRESSIVE, Scroll(6, -6, 3),
            Frame(4, "0.1", "0.0"),
            Frame(0, "1.0", "#00000000", "1.2", "1.1", "#00000000", "1.3"),
            Frame(0, "2.3", "2.2")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, PROGRESSIVE, Scroll(6, -6, 4),
            Frame(3, "0.1", "0.0", "0.3"),
            Frame(0, "1.0", "1.3", "1.2")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, INTERLACED_TOP_FIELD_FIRST, Scroll(6, -6, 4),
            Frame(3, "0.1", "0.0", "0.3"),
            Frame(0, "1.0", "1.3", "1.2")
        ),
        Case(
            videoHeight = 6, INTERLACED_TOP_FIELD_FIRST, tapeHeight = 4, INTERLACED_BOT_FIELD_FIRST, Scroll(6, -6, 4),
            Frame(3, "0.1", "0.0", "0.3"),
            Frame(0, "0.0", "1.3", "0.2")
        ),
        Case(
            videoHeight = 6, INTERLACED_BOT_FIELD_FIRST, tapeHeight = 4, PROGRESSIVE, Scroll(6, -6, 4),
            Frame(2, "0.0", "#00000000", "0.2", "0.1"),
            Frame(0, "1.2", "1.1", "#00000000", "1.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_BOT_FIELD_FIRST, tapeHeight = 4, INTERLACED_TOP_FIELD_FIRST, Scroll(6, -6, 4),
            Frame(2, "0.0", "#00000000", "0.2", "0.1"),
            Frame(0, "1.2", "0.1", "#00000000", "0.3")
        ),
        Case(
            videoHeight = 6, INTERLACED_BOT_FIELD_FIRST, tapeHeight = 4, INTERLACED_BOT_FIELD_FIRST, Scroll(6, -6, 4),
            Frame(2, "0.0", "#00000000", "0.2", "0.1"),
            Frame(0, "1.2", "1.1", "#00000000", "1.3")
        )
    ).flatMap { case ->
        if (!(case.videoScan == PROGRESSIVE && case.tapeScan == PROGRESSIVE)) listOf(case) else listOf(
            case,
            case.copy(tapeScan = INTERLACED_TOP_FIELD_FIRST, tapeContent = INTERLEAVED_FIELDS),
            case.copy(tapeScan = INTERLACED_BOT_FIELD_FIRST, tapeContent = INTERLEAVED_FIELDS)
        )
    }.flatMap { case ->
        listOf(case, case.copy(tapeCrop = Crop(3, 5, 4, 2)))
    }.flatMap { case ->
        listOf(
            case,
            case.copy(
                tapeFlipV = true, tapeScan = when (case.tapeScan) {
                    PROGRESSIVE -> PROGRESSIVE
                    INTERLACED_TOP_FIELD_FIRST -> INTERLACED_BOT_FIELD_FIRST
                    INTERLACED_BOT_FIELD_FIRST -> INTERLACED_TOP_FIELD_FIRST
                }
            )
        )
    }.flatMap { case ->
        listOf(case, case.copy(tapeAlpha = true))
    }.flatMap { case ->
        if (case.tapeAlpha) listOf(case) else listOf(case, case.copy(tapeIsImageSeq = false))
    }.toList()


    data class Case(
        val videoWidth: Int,
        val videoHeight: Int,
        val videoScan: Bitmap.Scan,
        val videoContent: Bitmap.Content,
        val tapeWidth: Int,
        val tapeHeight: Int,
        val tapeCrop: Crop,
        val tapeFlipV: Boolean,
        val tapeAlpha: Boolean,
        val tapeScan: Bitmap.Scan,
        val tapeContent: Bitmap.Content,
        val tapeBehavior: Behavior,
        val tapeIsImageSeq: Boolean,
        val videoFrames: List<Frame>
    ) {

        val fullTapeRes =
            Resolution(tapeWidth + tapeCrop.run { left + right }, tapeHeight + tapeCrop.run { top + bottom })

        constructor(
            videoHeight: Int,
            videoScan: Bitmap.Scan,
            tapeHeight: Int,
            tapeScan: Bitmap.Scan,
            tapeBehavior: Behavior,
            vararg videoFrames: Frame
        ) : this(
            videoWidth = 8,
            videoHeight,
            videoScan,
            videoContent = contentFor(videoScan),
            tapeWidth = 4,
            tapeHeight,
            tapeCrop = Crop(0, 0, 0, 0),
            tapeFlipV = false,
            tapeAlpha = false,
            tapeScan,
            tapeContent = contentFor(tapeScan),
            tapeBehavior,
            tapeIsImageSeq = true,
            videoFrames.asList()
        )

        init {
            for (videoFrame in videoFrames)
                require(videoFrame.offset in 0..<videoHeight && videoFrame.rows.size <= videoHeight - videoFrame.offset)
            if (tapeBehavior is Scroll)
                require((tapeBehavior.firstY - tapeBehavior.lastY) % tapeBehavior.speed == 0)
        }

        fun tapeFrame(frameIdx: Int): Bitmap {
            val (w, h) = fullTapeRes
            val pixels = IntArray(w * h) { i ->
                val x = i % w
                var y = i / w
                if (x in tapeCrop.left..<w - tapeCrop.right &&
                    y in tapeCrop.top..<h - tapeCrop.bottom
                ) {
                    y -= tapeCrop.top
                    if (tapeFlipV)
                        y = tapeHeight - 1 - y
                    0xFF or ((1 + frameIdx * tapeHeight + y) shl 8)
                } else
                    0xFF
            }
            val spec = Bitmap.Spec(
                fullTapeRes,
                Bitmap.Representation(Bitmap.PixelFormat.of(AV_PIX_FMT_RGBA), SRGB, STRAIGHT),
                tapeScan, tapeContent
            )
            val bitmap = Bitmap.allocate(spec)
            bitmap.put(pixels, w, byteOrder = ByteOrder.BIG_ENDIAN)
            return bitmap
        }

        fun tapeSourceString(rgba: Int): String {
            if ((rgba and 0xFF) != 0xFF || (rgba and 0xFFFFFF00.toInt()) == 0)
                return "#%08X".format(rgba)
            val serial = ((rgba shr 8) and 0xFFFFFF) - 1
            return "${serial / tapeHeight}.${serial % tapeHeight}"
        }

        companion object {
            private fun contentFor(scan: Bitmap.Scan) =
                if (scan == PROGRESSIVE) PROGRESSIVE_FRAME else INTERLEAVED_FIELDS
        }

    }

    sealed interface Behavior
    data class Static(val y: Int) : Behavior
    data class Scroll(val firstY: Int, val lastY: Int, val speed: Int) : Behavior

    data class Crop(val left: Int, val right: Int, val top: Int, val bottom: Int)

    class Frame(val offset: Int, vararg val rows: String) {
        override fun toString() = "($offset: ${rows.joinToString(" ")})"
    }

}
