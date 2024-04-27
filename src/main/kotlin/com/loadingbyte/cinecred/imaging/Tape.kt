package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import java.io.IOException
import java.lang.ref.SoftReference
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.*
import kotlin.math.min


/** An abstraction for a video that can be drawn onto a [DeferredImage] and is later recognized by [DeferredVideo]. */
class Tape private constructor(
    val fileSeq: Boolean,
    val fileOrDir: Path,
    private val fileOrPattern: Path,
    val edlFilename: String,
    firstNumber: Int,
    lastNumber: Int
) : AutoCloseable {

    private val zeroTimecode: Timecode?
        get() = if (fileSeq) availableRange.start else null


    /* ******************************
       ********** METADATA **********
       ****************************** */

    private var metadataInitialized = false
    private var metadataInitException: Exception? = null
    private var _spec: Bitmap.Spec? = null
    private var _fps: FPS? = null
    private var _availableRange: OpenEndRange<Timecode>? = if (!fileSeq) null else
        Timecode.Frames(firstNumber)..<Timecode.Frames(lastNumber + 1)

    /** @throws Exception */
    val spec: Bitmap.Spec
        get() = run { ensureMetadataIsInitialized(); _spec!! }
    /** @throws Exception */
    val fps: FPS?
        get() = run { ensureMetadataIsInitialized(); _fps }
    /** @throws Exception */
    val availableRange: OpenEndRange<Timecode>
        get() = if (fileSeq) _availableRange!! else run { ensureMetadataIsInitialized(); _availableRange!! }

    private fun ensureMetadataIsInitialized() {
        if (metadataInitialized)
            return
        metadataInitException?.let {
            throw IllegalStateException("Metadata of tape '${fileOrDir.name}' cannot be read: $it", it)
        }
        try {
            val start: Timecode.Clock?
            val dur: Timecode.Clock?
            VideoReader(fileOrPattern, zeroTimecode).use { videoReader ->
                _spec = videoReader.spec
                _fps = videoReader.fps
                start = if (fileSeq) null else videoReader.read()!!.timecode as Timecode.Clock
                dur = videoReader.estimatedDuration
            }
            if (!fileSeq)
                VideoReader(fileOrPattern, start!! + (dur ?: Timecode.Clock(24L * 60L * 60L, 1L))).use { videoReader ->
                    var end: Timecode? = null
                    while (true)
                        end = (videoReader.read() ?: break).apply { bitmap.close() /* close immediately */ }.timecode
                    val pad = _fps?.let { Timecode.Clock(it.denominator.toLong(), it.numerator.toLong()) }
                        ?: Timecode.Clock(1, 24)
                    _availableRange = start..<(end!! + pad)
                }
            metadataInitialized = true
        } catch (e: Exception) {
            LOGGER.error("Metadata of tape '{}' cannot be read.", fileOrDir.name, e)
            metadataInitException = e
        }
    }


    /* *****************************
       ********** PREVIEW **********
       ***************************** */

    private var fileSeqPreviewCache: PreviewCache<Picture.Raster?>? = null
    private var containerPreviewCache: PreviewCache<List<RasterPictureAndClock>>? = null

    init {
        if (fileSeq)
            fileSeqPreviewCache = PreviewCache(fileOrDir.name, 500, 50) { startFrames ->
                val reader = VideoReader(fileOrPattern, Timecode.Frames(startFrames))
                object : AbstractPreviewCacheLoader<Picture.Raster?>(reader) {
                    override fun loadNextItem() = reader.read()?.let(::toPreviewPicture)
                }
            }
        else
            containerPreviewCache = PreviewCache(fileOrDir.name, 10, 1) { startSeconds ->
                val reader = VideoReader(fileOrPattern, Timecode.Clock(startSeconds.toLong(), 1L))
                object : AbstractPreviewCacheLoader<List<RasterPictureAndClock>>(reader) {
                    var curSeconds = startSeconds - 1
                    var over: VideoReader.Frame? = null
                    override fun loadNextItem(): List<RasterPictureAndClock> {
                        curSeconds++
                        val item = mutableListOf<RasterPictureAndClock>()
                        while (true) {
                            val readFrame = over?.also { over = null } ?: reader.read() ?: return item
                            val readFrameSeconds = (readFrame.timecode as Timecode.Clock).seconds
                            if (readFrameSeconds > curSeconds) {
                                over = readFrame
                                return item
                            }
                            if (readFrameSeconds == curSeconds)
                                item += RasterPictureAndClock(toPreviewPicture(readFrame), readFrame.timecode)
                            readFrame.bitmap.close()
                        }
                    }
                }
            }
    }

    private class RasterPictureAndClock(val picture: Picture.Raster, val clock: Timecode.Clock)

    private abstract class AbstractPreviewCacheLoader<I>(val reader: VideoReader) : PreviewCache.Loader<I> {

        private val previewSpec: Bitmap.Spec

        init {
            val (tapeW, tapeH) = reader.spec.resolution
            val tapeRep = reader.spec.representation
            val tapeHasAlpha = tapeRep.pixelFormat.hasAlpha
            val maxDim = 128
            val previewRes = if (tapeW > tapeH)
                Resolution(maxDim, maxDim * tapeH / tapeW)
            else
                Resolution(maxDim * tapeW / tapeH, maxDim)
            val previewRep = Picture.Raster.compatibleRepresentation(tapeRep.colorSpace!!.primaries, tapeHasAlpha)
            previewSpec = Bitmap.Spec(previewRes, previewRep)
        }

        // Lazily initialize the converter so that when its creation fails, we're in a try-finally block that closes
        // the loader and consequently the reader.
        private val previewConverter by lazy {
            BitmapConverter(reader.spec, previewSpec, srcAligned = false, approxTransfer = true)
        }

        fun toPreviewPicture(frame: VideoReader.Frame) =
            Picture.Raster(Bitmap.allocate(previewSpec).also { previewConverter.convert(frame.bitmap, it) })

        override fun close() = reader.close()

    }

    /**
     * @return null when the timecode is out of bounds.
     * @throws Exception
     */
    fun getPreviewFrame(timecode: Timecode): Picture.Raster? {
        if (timecode !in availableRange)
            return null

        if (fileSeq) {
            return fileSeqPreviewCache!!.getItem((timecode as Timecode.Frames).frames).get()
        } else {
            val previewCache = containerPreviewCache!!
            var previewFrame: RasterPictureAndClock? = null
            var curSeconds = (timecode as Timecode.Clock).seconds
            while (previewFrame == null) {
                val item = previewCache.getItem(curSeconds).get()
                val idx = item.binarySearchBy(timecode, selector = RasterPictureAndClock::clock)
                when {
                    idx >= 0 -> previewFrame = item[idx]
                    idx < -1 -> previewFrame = item[-idx - 2]
                    else -> curSeconds--
                }
            }
            // For video files, start loading in the next second if it's not already loaded to ensure fluid playback.
            // We do not preload file sequences as (a) they're quicker to load on the fly, and (b) it's harder.
            previewCache.getItem(timecode.seconds + 1)
            return previewFrame.picture
        }
    }

    /**
     * @return null when the timecode does not exist.
     * @throws Exception
     */
    fun toClockTimecode(timecode: Timecode.ExactFramesInSecond): Timecode.Clock? =
        containerPreviewCache!!.getItem(timecode.seconds).get().getOrNull(timecode.frames)?.clock

    override fun close() {
        fileSeqPreviewCache?.close()
        containerPreviewCache?.close()
    }


    companion object {

        val MISSING_MEDIA_TOP_COLOR = colorFromHex("#E44244")
        val MISSING_MEDIA_BOT_COLOR = colorFromHex("#5B171F")

        private val CONTAINER_EXTS =
            VideoContainerFormat.READER.flatMapTo(TreeSet(String.CASE_INSENSITIVE_ORDER)) { it.extensions }

        private val FILE_SEQ_EXTS = sortedSetOf(
            String.CASE_INSENSITIVE_ORDER,
            // Taken from libavformat/img2.c:
            "jpeg", "jpg", "jps", "mpo", "ljpg", "jls", "png", "pns", "mng", "ppm", "pnm", "pgm", "pgmyuv", "pbm",
            "pam", "pfm", "phm", "cri", "pix", "dds", "mpg1-img", "mpg2-img", "mpg4-img", "y", "raw", "bmp", "tga",
            "tiff", "tif", "dng", "sgi", "ptx", "pcd", "pcx", "pic", "pct", "pict", "sun", "ras", "rs", "im1", "im8",
            "im24", "im32", "sunras", "svg", "svgz", "j2c", "jp2", "jpc", "j2k", "dpx", "exr", "pic", "yuv10", "webp",
            "xbm", "xpm", "xface", "xwd", "img", "ximg", "timg", "vbn", "jxl", "qoi", "hdr", "wbmp"
        )

        /**
         * If the given [Path] points to a video file or image sequence, this function returns a [Tape] that can be used
         * to access it.
         *
         * @throws IOException
         */
        fun recognize(fileOrDir: Path): Tape? = when {
            fileOrDir.isRegularFile() ->
                if (fileOrDir.extension !in CONTAINER_EXTS) null else
                    Tape(fileSeq = false, fileOrDir, fileOrDir, fileOrDir.name, -1, -1)
            fileOrDir.isAccessibleDirectory(thatContainsNonHiddenFiles = true) ->
                fileOrDir.useDirectoryEntries { seq -> recognizeFileSeq(fileOrDir, seq) }
            else ->
                null
        }

        private fun recognizeFileSeq(dir: Path, seq: Sequence<Path>): Tape? {
            var zeroPad = false
            var sameLen = -1
            val numbers = TreeSet<Int>()

            fun checkName(prefix: String, suffix: String, name: String): Boolean {
                val i1 = prefix.length
                val i2 = name.length - suffix.length

                // Require that there is an infix part (hopefully numbers) between the prefix and suffix.
                if (i1 >= i2)
                    return false

                // Record whether the numbers are zero-padded.
                if (!zeroPad && name[i1] == '0' && i2 - i1 > 1)
                    zeroPad = true

                // If all numbers have the same length, record that length.
                if (sameLen == -1)
                    sameLen = i2 - i1
                else if (sameLen != -2 && sameLen != i2 - i1)
                    sameLen = -2

                // Require that the infix part consists only of digits.
                // Record the number and require that no number occurs more than once.
                var number = 0
                for (i in i1..<i2) {
                    val c = name[i]
                    if (c !in '0'..'9')
                        return false
                    number = number * 10 + (c - '0')
                }
                return numbers.add(number)
            }

            var ext: String? = null
            var fstName: String? = null
            var prefix: String? = null
            var suffix: String? = null

            for (file in seq) {
                // Don't let thumbnail files or similar sabotage us.
                if (file.isHidden())
                    continue

                // Require that the directory only houses regular files.
                if (!file.isRegularFile())
                    return null

                // Require that all files share the same extension, and that it is a known file seq format extension.
                if (ext == null) {
                    ext = file.extension
                    if (ext !in FILE_SEQ_EXTS)
                        return null
                } else if (file.extension != ext)
                    return null

                // Find the common prefix and suffix of the first two files, excluding digits next to the infix part.
                // Require that all other files exhibit the same prefix and suffix.
                // Pass each filename alongside the prefix and suffix to the checkName() function.
                val name = file.name
                if (fstName == null)
                    fstName = name
                else {
                    if (prefix == null || suffix == null) {
                        prefix = fstName.commonPrefixWith(name).trimEnd { c -> c in '0'..'9' }
                        suffix = fstName.commonSuffixWith(name).trimStart { c -> c in '0'..'9' }
                        if (!checkName(prefix, suffix, fstName))
                            return null
                    } else
                        if (!name.startsWith(prefix) || !name.endsWith(suffix))
                            return null
                    if (!checkName(prefix, suffix, name))
                        return null
                }
            }

            // Require that there are at least two files.
            if (numbers.isEmpty())
                return null

            // If the numbers are zero-padded, they must all have the same length so that we can specify a fmt string.
            if (zeroPad && sameLen == -2)
                return null

            // Require that the numbering is continuous.
            var prevNumber = -1
            for (number in numbers) {
                if (prevNumber != -1 && number - prevNumber != 1)
                    return null
                prevNumber = number
            }

            val d = if (zeroPad) "%0${sameLen}d" else "%d"
            val pattern = dir.resolve("$prefix$d$suffix")
            val edlFilename = "$prefix[${d.format(numbers.first())}-${d.format(numbers.last())}]$suffix"
            return Tape(fileSeq = true, dir, pattern, edlFilename, numbers.first(), numbers.last())
        }

    }


    /** A wrapper around [VideoReader] for obtaining frames at monotonically increasing arbitrary timecodes. */
    inner class SequentialReader(private val startTimecode: Timecode) : AutoCloseable {

        private val videoReader = VideoReader(fileOrPattern, startTimecode)

        private var lastTimecode: Timecode? = null
        private var behind: VideoReader.Frame? = null
        private var ahead: VideoReader.Frame? = null

        override fun close() {
            videoReader.close()
            behind?.run { bitmap.close() }
            ahead?.run { bitmap.close() }
        }

        /**
         * Bitmaps returned by this method must NEVER be [Bitmap.close]d by the caller. They will however automatically
         * be closed when the next frame is read or when the reader is closed. You can keep them around by making views.
         */
        fun read(timecode: Timecode): VideoReader.Frame {
            require(timecode >= startTimecode) { "Timecode is lower than start timecode." }
            require(lastTimecode.let { it == null || timecode >= it }) { "Timecodes are not increasing." }
            lastTimecode = timecode
            while (behind == null || ahead.let { it != null && it.timecode <= timecode }) {
                behind?.run { bitmap.close() }
                behind = ahead
                ahead = videoReader.read()
            }
            return behind!!
        }

        /** After having called [read], this method tells you whether you should extract the first or second field. */
        fun didReadFirstField(): Boolean {
            require(!fileSeq) { "Cannot deduce field based on pure frames timecode." }
            val readTc = lastTimecode!!
            val behindTc = behind!!.timecode
            val aheadTc = ahead?.timecode
            return readTc - behindTc <
                    (if (aheadTc != null) aheadTc - behindTc else availableRange.endExclusive - behindTc) / 2
        }

    }


    data class Embedded(
        /** Note: Accessing the metadata of this tape is guaranteed to not throw exceptions. */
        val tape: Tape,
        val resolution: Resolution,
        val leftMarginFrames: Int,
        val rightMarginFrames: Int,
        val leftFadeFrames: Int,
        val rightFadeFrames: Int,
        val range: OpenEndRange<Timecode>,
        val align: Align
    ) {

        enum class Align { START, MIDDLE, END }

        /** @throws Exception */
        constructor(tape: Tape) : this(tape, tape.spec.resolution, 0, 0, 0, 0, tape.availableRange, Align.START)

        init {
            require(resolution.run { widthPx > 0 && heightPx > 0 })
            require(leftMarginFrames >= 0 && rightMarginFrames >= 0)
            require(leftFadeFrames >= 0 && rightFadeFrames >= 0)
            val avail = tape.availableRange  // This call is what guarantees that the metadata of "tape" is loaded.
            require(range.start.let { it >= avail.start && it < range.endExclusive })
            require(range.endExclusive.let { it > range.start && it <= avail.endExclusive })
        }

        fun withWidthPreservingAspectRatio(width: Int): Embedded {
            val base = tape.spec.resolution
            return copy(resolution = Resolution(width, roundingDiv(base.heightPx * width, base.widthPx)))
        }

        fun withHeightPreservingAspectRatio(height: Int): Embedded {
            val base = tape.spec.resolution
            return copy(resolution = Resolution(roundingDiv(base.widthPx * height, base.heightPx), height))
        }

        fun withInPoint(timecode: Timecode): Embedded = copy(range = timecode..<range.endExclusive)
        fun withOutPoint(timecode: Timecode): Embedded = copy(range = range.start..<timecode)

    }


    private class PreviewCache<I>(
        private val tapeName: String,
        private val ahead: Int,
        private val inertia: Int,
        private val createLoader: (start: Int) -> Loader<I>
    ) {

        interface Loader<I> : AutoCloseable {
            fun loadNextItem(): I
        }

        class ClosedException : IllegalStateException("Tape preview cache has been closed.")

        private val lock = ReentrantLock()
        private val slices = TreeSet<BaseSlice<I>>()
        private var closed = false

        /** The returned future fails with a [ClosedException] if the cache is closed. */
        fun getItem(point: Int): CompletableFuture<I> = lock.withLock {
            if (closed)
                return CompletableFuture.failedFuture(ClosedException())
            require(point >= 0)
            val slice = slices.floor(BaseSlice(point)) as Slice?
            return if (slice == null || point >= slice.stop)
                addSlice(point)
            else {
                val resp = slice.getItemOrSplitSlice(point)
                when (resp.status) {
                    SliceRespStatus.GOT -> resp.future!!
                    SliceRespStatus.CLEARED -> {
                        slices.remove(slice)
                        addSlice(point)
                    }
                    SliceRespStatus.SPLIT -> addSlice(point)
                }
            }
        }

        private fun addSlice(start: Int): CompletableFuture<I> {
            var stop = start + ahead
            slices.higher(BaseSlice(start))?.let { stop = min(stop, it.start) }
            val slice = Slice<I>(start, stop, inertia)
            val future = slice.getItemOrSplitSlice(start).future!!
            slices.add(slice)
            EXECUTOR.submit(throwableAwareTask {
                try {
                    createLoader(start).use { loader ->
                        while (slice.claimNextPointForLoading())
                            slice.publishLoadedItem(loader.loadNextItem())
                    }
                } catch (e: Exception) {
                    LOGGER.error("Error while generating preview for tape '{}'; will close the cache.", tapeName, e)
                    close()
                }
            })
            return future
        }

        fun close() {
            val slices = lock.withLock {
                closed = true
                ArrayList(slices).also { slices.clear() }
            }
            for (slice in slices)
                (slice as Slice).close()
        }

        companion object {
            private val EXECUTOR = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "TapePreviewLoader").apply { isDaemon = true }
            }
        }

        private open class BaseSlice<I>(val start: Int) : Comparable<BaseSlice<I>> {
            override fun compareTo(other: BaseSlice<I>) = start.compareTo(other.start)
        }

        private class Slice<I>(start: Int, var stop: Int, private val inertia: Int) : BaseSlice<I>(start) {

            private val lock = ReentrantLock()
            private var items: MutableList<I>? = ArrayList(stop - start)
            private var softItems: SoftReference<List<I>>? = null
            private var claim = start - 1
            private val pendingFutures = arrayOfNulls<MutableList<CompletableFuture<I>>?>(stop - start)
            private var closed = false

            fun getItemOrSplitSlice(point: Int): SliceResp<I> = lock.withLock {
                if (closed)
                    return SliceResp(SliceRespStatus.GOT, CompletableFuture.failedFuture(ClosedException()))
                require(point in start..<stop)
                val idx = point - start
                val items = this.items
                if (items == null)
                    when (val softItems = this.softItems!!.get()) {
                        null -> SliceResp(SliceRespStatus.CLEARED, null)
                        else -> SliceResp(SliceRespStatus.GOT, CompletableFuture.completedFuture(softItems[idx]))
                    }
                else if (point == start /* prevent empty slices */ || point - claim <= inertia)
                    if (idx < items.size)
                        SliceResp(SliceRespStatus.GOT, CompletableFuture.completedFuture(items[idx]))
                    else {
                        val future = CompletableFuture<I>()
                        (pendingFutures[idx] ?: ArrayList<CompletableFuture<I>>().also {
                            pendingFutures[idx] = it
                        }) += future
                        SliceResp(SliceRespStatus.GOT, future)
                    }
                else {
                    stop = point
                    SliceResp(SliceRespStatus.SPLIT, null)
                }
            }

            fun claimNextPointForLoading(): Boolean = lock.withLock { !closed && ++claim < stop }

            fun publishLoadedItem(item: I) {
                val futures = lock.withLock {
                    if (closed)
                        return
                    items!!.add(item)
                    if (claim == stop - 1) {
                        softItems = SoftReference(items)
                        items = null
                    }
                    val idx = claim - start
                    pendingFutures[idx].also { pendingFutures[idx] = null }
                }
                futures?.forEach { it.complete(item) }
            }

            fun close() {
                val futures = lock.withLock {
                    closed = true
                    buildList {
                        for (futures in pendingFutures)
                            futures?.let(::addAll)
                    }
                }
                for (future in futures)
                    future.completeExceptionally(ClosedException())
            }

        }

        private class SliceResp<I>(val status: SliceRespStatus, val future: CompletableFuture<I>?)
        private enum class SliceRespStatus { GOT, CLEARED, SPLIT }

    }

}
