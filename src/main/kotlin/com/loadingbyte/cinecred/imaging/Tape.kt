package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.io.IOException
import java.lang.ref.SoftReference
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.swing.UIManager
import kotlin.concurrent.withLock
import kotlin.io.path.*
import kotlin.jvm.optionals.getOrNull
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.min


/** An abstraction for a video that can be drawn onto a [DeferredImage] and is later recognized by [DeferredVideo]. */
class Tape private constructor(
    val fileSeq: Boolean,
    val fileOrDir: Path,
    private val fileOrPattern: Path,
    private val firstNumber: Int,
    private val lastNumber: Int,
    parent: Tape?,
    private val reinterpretation: Reinterpretation?
) : AutoCloseable {

    /* ******************************
       ********** METADATA **********
       ****************************** */

    private class Metadata(val spec: Bitmap.Spec, val fps: FPS?, val availableRange: OpenEndRange<Timecode>)

    /** @throws IllegalStateException */
    val spec: Bitmap.Spec
        get() = metadata.spec
    /** @throws IllegalStateException */
    val fps: FPS?
        get() = metadata.fps
    /** @throws IllegalStateException */
    val availableRange: OpenEndRange<Timecode>
        get() = fileSeqAvailableRange ?: metadata.availableRange

    /** @throws IllegalStateException */
    fun loadMetadata() {
        metadata
    }

    fun loadMetadataInBackground() {
        if (!metadataLazy.isInitialized())
            GLOBAL_THREAD_POOL.submit { metadataLazy.value }
    }

    private val fileSeqAvailableRange: OpenEndRange<Timecode>? = if (!fileSeq) null else
        Timecode.Frames(firstNumber)..<Timecode.Frames(lastNumber + 1)

    private val metadata: Metadata
        get() = when (val m = metadataLazy.value) {
            is Throwable -> throw IllegalStateException("Metadata of tape '${fileOrDir.name}' cannot be read.", m)
            else -> m as Metadata
        }

    private val metadataLazy: Lazy<Any> = lazy {
        if (parent != null && reinterpretation != null)
            return@lazy when (val m = parent.metadataLazy.value) {
                is Metadata -> Metadata(reinterpretation.ofFrameSpec(m.spec), m.fps, m.availableRange)
                else -> m
            }
        try {
            val spec: Bitmap.Spec
            val fps: FPS?
            val start: Timecode.Clock?
            val dur: Timecode.Clock?
            VideoReader(fileOrPattern, if (fileSeq) Timecode.Frames(firstNumber) else null).use { videoReader ->
                spec = videoReader.spec
                start = if (fileSeq) null else videoReader.read()!!.apply { bitmap.close() }.timecode as Timecode.Clock
                dur = videoReader.estimatedDuration
                fps = if (fileSeq ||
                    // Try to detect variable framerates by looking at the timecode differences between the first couple
                    // of frames. If VFR is detected, set fps to null.
                    (sequenceOf(start!!) + generateSequence { videoReader.read()?.apply { bitmap.close() }?.timecode })
                        .take(20).zipWithNext { tc1, tc2 -> tc2 - tc1 }.zipWithNext { d1, d2 -> d1 != d2 }.any { it }
                ) null else videoReader.fps
            }
            val availableRange: OpenEndRange<Timecode>
            if (!fileSeq)
                VideoReader(fileOrPattern, start!! + (dur ?: Timecode.Clock(24L * 60L * 60L, 1L))).use { videoReader ->
                    var end: Timecode? = null
                    while (true)
                        end = (videoReader.read() ?: break).apply { bitmap.close() /* close immediately */ }.timecode
                    val pad = fps?.let { Timecode.Clock(it.denominator.toLong(), it.numerator.toLong()) }
                        ?: Timecode.Clock(1, 24)
                    availableRange = start..<(end!! + pad)
                }
            else
                availableRange = fileSeqAvailableRange!!
            Metadata(spec, fps, availableRange)
        } catch (e: Exception) {
            LOGGER.error("Metadata of tape '{}' cannot be read.", fileOrDir.name, e)
            e
        }
    }

    private val reinterpretedTapes =
        Collections.synchronizedMap(object : LinkedHashMap<Reinterpretation, Tape>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Reinterpretation, Tape>) = size > 4
        })

    /** The returned tape is "dependent" because it will be closed when this parent tape is closed. */
    fun dependentReinterpretedTape(
        range: Bitmap.Range,
        colorSpace: ColorSpace,
        yuvCoefficients: Bitmap.YUVCoefficients?,
        alpha: Bitmap.Alpha,
        scan: Bitmap.Scan,
        content: Bitmap.Content
    ): Tape {
        val viewSettings = Reinterpretation(range, colorSpace, yuvCoefficients, alpha, scan, content)
        return reinterpretedTapes.computeIfAbsent(viewSettings) {
            Tape(fileSeq, fileOrDir, fileOrPattern, firstNumber, lastNumber, parent = this, viewSettings)
        }
    }


    /* *****************************
       ********** PREVIEW **********
       ***************************** */

    private var fileSeqPreviewCache: PreviewCache<Optional<Picture.Raster>>? = null
    private var containerPreviewCache: PreviewCache<List<RasterPictureAndClock>>? = null

    init {
        val reinterpretParentPreview = parent != null && reinterpretation != null && try {
            val parentRep = parent.spec.representation
            reinterpretation.range == parentRep.range &&
                    reinterpretation.yuvCoefficients == parentRep.yuvCoefficients
        } catch (_: IllegalStateException) {
            // The tape is unreadable, so it doesn't matter what we return here.
            true
        }
        if (reinterpretParentPreview) {
            if (fileSeq)
                fileSeqPreviewCache = MappingPreviewCache(parent.fileSeqPreviewCache!!, mapItem = { optional ->
                    optional.map(reinterpretation::ofPreview)
                }, closeItem = { optional -> optional.getOrNull()?.close() })
            else
                containerPreviewCache = MappingPreviewCache(parent.containerPreviewCache!!, mapItem = { list ->
                    list.map { elem -> RasterPictureAndClock(reinterpretation.ofPreview(elem.picture), elem.clock) }
                }, closeItem = { list -> for (elem in list) elem.picture.close() })
        } else if (fileSeq)
            fileSeqPreviewCache = LoadingPreviewCache(fileOrDir.name, 500, 50, createLoader = { startFrames ->
                val reader = VideoReader(fileOrPattern, Timecode.Frames(startFrames))
                object : AbstractPreviewCacheLoader<Optional<Picture.Raster>>(reader, reinterpretation) {
                    override fun loadNextItem() =
                        reader.read()
                            ?.let { f -> reinterpretation?.ofFrame(f) ?: f }
                            ?.let { f -> toPreviewPicture(f).also { f.bitmap.close() } }
                            .let(Optional<*>::ofNullable)
                }
            }, closeItem = { optional -> optional.getOrNull()?.close() })
        else
            containerPreviewCache = LoadingPreviewCache(fileOrDir.name, 10, 1, createLoader = { startSeconds ->
                val reader = VideoReader(fileOrPattern, Timecode.Clock(startSeconds.toLong(), 1L))
                object : AbstractPreviewCacheLoader<List<RasterPictureAndClock>>(reader, reinterpretation) {
                    var curSeconds = startSeconds - 1
                    var over: VideoReader.Frame? = null
                    override fun loadNextItem(): List<RasterPictureAndClock> {
                        curSeconds++
                        val item = mutableListOf<RasterPictureAndClock>()
                        while (true) {
                            val readFrame = over?.also { over = null }
                                ?: reader.read()?.let { f -> reinterpretation?.ofFrame(f) ?: f }
                                ?: return item
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
            }, closeItem = { list -> for (elem in list) elem.picture.close() })
    }

    private class RasterPictureAndClock(val picture: Picture.Raster, val clock: Timecode.Clock)

    private abstract class AbstractPreviewCacheLoader<I>(
        private val reader: VideoReader,
        private val reinterpretation: Reinterpretation?
    ) : LoadingPreviewCache.Loader<I> {

        private val pictureSpec: Bitmap.Spec
        private var tape2picture: BitmapConverter? = null

        init {
            val tapeSpec = reader.spec.let { reinterpretation?.ofFrameSpec(it) ?: it }
            val (tapeW, tapeH) = tapeSpec.resolution
            val tapeRep = tapeSpec.representation
            val maxDim = previewResolution.coerceAtLeast(16)
            val previewRes = when {
                tapeW <= maxDim && tapeH <= maxDim ->
                    Resolution(tapeW, tapeH)
                tapeW > tapeH ->
                    Resolution(maxDim, roundingDiv(maxDim * tapeH, tapeW).coerceAtLeast(1))
                else ->
                    Resolution(roundingDiv(maxDim * tapeW, tapeH).coerceAtLeast(1), maxDim)
            }
            val pictureRep = Picture.Raster.compatibleRepresentation(tapeRep.colorSpace!!, tapeRep.alpha)
            pictureSpec = Bitmap.Spec(previewRes, pictureRep)
            setupSafely({
                tape2picture = BitmapConverter(tapeSpec, pictureSpec, srcAligned = false, approxTransfer = true)
            }, ::close)
        }

        fun toPreviewPicture(frame: VideoReader.Frame): Picture.Raster {
            val picturePreviewBitmap = Bitmap.allocate(pictureSpec)
            tape2picture!!.convert(frame.bitmap, picturePreviewBitmap)
            return Picture.Raster(picturePreviewBitmap)
        }

        override fun close() {
            reader.close()
            tape2picture?.close()
        }

    }

    /** The returned future resolves to null when the timecode is out of bounds, and may fail with any exception. */
    fun getPreviewFrame(timecode: Timecode): CompletableFuture<Picture.Raster?> {
        if (timecode !in availableRange)
            return CompletableFuture.completedFuture(null)

        if (fileSeq) {
            return fileSeqPreviewCache!!.getItem((timecode as Timecode.Frames).frames).thenApply { it.getOrNull() }
        } else {
            val previewFrame = getContainerPreviewFrame(timecode, (timecode as Timecode.Clock).seconds)
            // For video files, start loading in the next second if it's not already loaded to ensure fluid playback.
            // We do not preload file sequences as (a) they're quicker to load on the fly, and (b) it's harder.
            containerPreviewCache!!.getItem(timecode.seconds + 1)
            return previewFrame
        }
    }

    private fun getContainerPreviewFrame(timecode: Timecode, curSeconds: Int): CompletableFuture<Picture.Raster?> =
        containerPreviewCache!!.getItem(curSeconds).thenCompose { item ->
            val idx = item.binarySearchBy(timecode, selector = RasterPictureAndClock::clock)
            when {
                idx >= 0 -> CompletableFuture.completedFuture(item[idx].picture)
                idx < -1 -> CompletableFuture.completedFuture(item[-idx - 2].picture)
                // If the first frame in this second actually happens after the requested timecode (or if this second
                // doesn't even contain any frame), we need to look in the previous second.
                else -> getContainerPreviewFrame(timecode, curSeconds - 1)
            }
        }

    /**
     * @return null when the timecode does not exist.
     * @throws Exception
     */
    fun toClockTimecode(timecode: Timecode.ExactFramesInSecond): Timecode.Clock? =
        containerPreviewCache!!.getItem(timecode.seconds).get().getOrNull(timecode.frames)?.clock

    /** Also closes all tapes created via [dependentReinterpretedTape]. */
    override fun close() {
        reinterpretedTapes.values.forEach(Tape::close)
        fileSeqPreviewCache?.close()
        containerPreviewCache?.close()
    }


    companion object {

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
         */
        fun recognize(fileOrDir: Path): Tape? = when {
            fileOrDir.isRegularFile() ->
                if (fileOrDir.extension !in CONTAINER_EXTS) null else
                    Tape(fileSeq = false, fileOrDir, fileOrDir, -1, -1, null, null)
            fileOrDir.isAccessibleDirectory(thatContainsNonHiddenFiles = true) ->
                try {
                    fileOrDir.useDirectoryEntries { seq -> recognizeFileSeq(fileOrDir, seq) }
                } catch (e: IOException) {
                    LOGGER.warn("Could not look for an image sequence tape in '{}'.", fileOrDir, e)
                    null
                }
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

            val pattern = dir.resolve(prefix + (if (zeroPad) "%0${sameLen}d" else "%d") + suffix)
            return Tape(fileSeq = true, dir, pattern, numbers.first(), numbers.last(), null, null)
        }

        @Volatile var previewResolution: Int = 128

        private val PREVIEW_OUTLINE = run {
            val font = Font.system(UIManager.getFont("defaultFont").getFontName(Locale.ROOT))
                ?: Font.bundled("Source Sans Pro Regular")!!
            GlyphString.of(l10n("imaging.tapePreview"), font.case())
                .appendOutlineTo(Path2D.Float(), 0.0, 0.0)
        }
        private val PREVIEW_BOUNDS = PREVIEW_OUTLINE.bounds2D

        fun previewIndicator(x: Double, y: Double, width: Double, height: Double): Shape {
            val diag = hypot(width, height)
            val maxTextH = hypot(height, height * height / width)
            val textH = (diag * maxTextH) / (diag + maxTextH * PREVIEW_BOUNDS.width / PREVIEW_BOUNDS.height)
            val textTransform = AffineTransform().apply {
                translate(x + width / 2, y + height / 2)
                rotate(-atan(height / width))
                scale(textH / PREVIEW_BOUNDS.height)
                translate(-PREVIEW_BOUNDS.centerX, -PREVIEW_BOUNDS.centerY)
            }
            return PREVIEW_OUTLINE.transformedBy(textTransform)
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
                ahead = null  // Prevent double close if the following read throws and close is called as a consequence.
                ahead = videoReader.read()?.let { f -> reinterpretation?.ofFrame(f) ?: f }
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


    private data class Reinterpretation(
        val range: Bitmap.Range,
        val colorSpace: ColorSpace,
        val yuvCoefficients: Bitmap.YUVCoefficients?,
        val alpha: Bitmap.Alpha,
        val scan: Bitmap.Scan,
        val content: Bitmap.Content
    ) {

        private fun ofFrameRep(rep: Bitmap.Representation): Bitmap.Representation =
            Bitmap.Representation(rep.pixelFormat, range, colorSpace, yuvCoefficients, rep.chromaLocation, alpha)

        fun ofFrameSpec(spec: Bitmap.Spec): Bitmap.Spec =
            Bitmap.Spec(spec.resolution, ofFrameRep(spec.representation), scan, content)

        fun ofFrame(frame: VideoReader.Frame): VideoReader.Frame =
            VideoReader.Frame(frame.bitmap.reinterpretedView(ofFrameSpec(frame.bitmap.spec)), frame.timecode)
                .also { frame.bitmap.close() }

        fun ofPreview(picture: Picture.Raster): Picture.Raster {
            val (res, rep) = picture.bitmap.spec
            val newRep = Bitmap.Representation(rep.pixelFormat, rep.range, colorSpace, alpha)
            return Picture.Raster(picture.bitmap.reinterpretedView(Bitmap.Spec(res, newRep)))
        }

    }


    private interface PreviewCache<I : Any> : AutoCloseable {
        /** The returned future fails with a [ClosedException] if the cache is closed. */
        fun getItem(point: Int): CompletableFuture<I>
    }

    private class ClosedException : IllegalStateException("Tape preview cache has been closed.")


    private class MappingPreviewCache<I : Any>(
        private val delegate: PreviewCache<I>,
        private val mapItem: (I) -> I,
        private val closeItem: (I) -> Unit
    ) : PreviewCache<I> {

        private val cache = ConcurrentHashMap<Int, I>()

        override fun getItem(point: Int): CompletableFuture<I> {
            cache[point]?.let { return CompletableFuture.completedFuture(it) }
            return delegate.getItem(point).thenApply { item ->
                cache.computeIfAbsent(point) { mapItem(item) }
            }
        }

        override fun close() {
            delegate.close()
            cache.values.forEach(closeItem)
            cache.clear()
        }

    }


    private class LoadingPreviewCache<I : Any>(
        private val tapeName: String,
        private val ahead: Int,
        private val inertia: Int,
        private val createLoader: (start: Int) -> Loader<I>,
        private val closeItem: (I) -> Unit
    ) : PreviewCache<I> {

        interface Loader<I> : AutoCloseable {
            fun loadNextItem(): I
        }

        private val lock = ReentrantLock()
        private val slices = TreeSet<BaseSlice<I>>()
        private var closed = false

        override fun getItem(point: Int): CompletableFuture<I> = lock.withLock {
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
            GLOBAL_THREAD_POOL.submit(throwableAwareTask {
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

        override fun close() {
            val slices = lock.withLock {
                closed = true
                slices.toMutableList().also { slices.clear() }
            }
            for (slice in slices)
                (slice as Slice).close(closeItem)
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
                        (pendingFutures[idx] ?: mutableListOf<CompletableFuture<I>>().also {
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

            fun close(closeItem: (I) -> Unit) {
                lock.withLock { closed = true }
                (items ?: softItems?.get())?.forEach(closeItem)
                for (futures in pendingFutures)
                    futures?.forEach { future -> future.completeExceptionally(ClosedException()) }
            }

        }

        private class SliceResp<I>(val status: SliceRespStatus, val future: CompletableFuture<I>?)
        private enum class SliceRespStatus { GOT, CLEARED, SPLIT }

    }

}
