package com.loadingbyte.cinecred.imaging

import sun.font.ScriptRun
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.text.Bidi
import java.text.BreakIterator
import java.text.CharacterIterator
import java.util.*
import kotlin.math.max
import kotlin.math.min


class GlyphString<U> private constructor(
    /**
     * The segments are ordered visually from left to right, and hence might not match the order of the input chars.
     * In addition, there might be more segments than runs.
     */
    val segments: List<Segment<U>>,
    val width: Double,
    private val chars: CharArray,
    private val charsLim0: Int,
    private val charsLim1: Int,
    private val runs: List<Run<U>>,
    private val locale: Locale,
    private val bidi: Bidi?
) {

    fun appendOutlineTo(path: Path2D.Float, dx: Double, dy: Double): Path2D.Float {
        for (segment in segments)
            segment.appendOutlineTo(path, segment.baseX + dx, dy)
        return path
    }

    fun sub(startCharIdx: Int, endCharIdx: Int): GlyphString<U> {
        val subBidi = bidi?.createLineBidi(startCharIdx, endCharIdx)
        return layout(chars, charsLim0 + startCharIdx, charsLim0 + endCharIdx, runs, locale, subBidi)
    }

    /** Fully justifies the text to exactly fit the supplied width. */
    fun justified(justificationWidth: Double): GlyphString<U> {
        if (justificationWidth < width)
            return this

        // A slot is a glyph index ahead of which we will inject a gap in order to attain the justification width.
        val slots = HashMap<Segment<U>, MutableSet<Int>>()

        // Add a slot to each whitespace glyph.
        for (seg in segments) {
            val segSlots = HashSet<Int>()
            slots[seg] = segSlots
            for (glyphIdx in 0..<seg.glyphCount)
                if (seg.getGlyphBoxRightX(glyphIdx) - seg.getGlyphBoxLeftX(glyphIdx) > 0.001 &&
                    chars[charsLim0 + seg.baseCharIndex + seg.getCharIndex(glyphIdx)].isWhitespace()
                )
                    segSlots += glyphIdx
        }

        // If the line contains no whitespace, add a slot to each idiographic char (i.e., CJK chars), and to each
        // non-idiographic char that directly follows an idiographic char to make the gaps balanced.
        var numSlots = slots.values.sumOf { it.size }
        if (numSlots == 0) {
            var sawFirst = false
            var carry = false
            for (seg in segments) {
                val segSlots = slots.getValue(seg)
                for (glyphIdx in 0..<seg.glyphCount)
                    if (seg.getGlyphBoxRightX(glyphIdx) - seg.getGlyphBoxLeftX(glyphIdx) > 0.001) {
                        if (!sawFirst) {
                            sawFirst = true
                            continue
                        }
                        val ideographic = Character.isIdeographic(
                            Character.codePointAt(chars, charsLim0 + seg.baseCharIndex + seg.getCharIndex(glyphIdx))
                        )
                        if (ideographic || carry)
                            segSlots += glyphIdx
                        carry = ideographic
                    }
            }
            numSlots = slots.values.sumOf { it.size }
        }

        // If we still couldn't find any slot, don't justify at all.
        if (numSlots == 0)
            return this

        // Otherwise, compute the gap that needs to be injected at each slot, and then modify the glyph positions in the
        // segments accordingly.
        val gap = (justificationWidth - width) / numSlots
        val justifiedSegments = buildList {
            var shift = 0.0
            for (seg in segments) {
                val justifiedSeg = seg.withBaseOffsetAndInjectedGaps(shift, slots.getValue(seg), gap)
                shift += justifiedSeg.width - seg.width
                add(justifiedSeg)
            }
        }
        return GlyphString(justifiedSegments, justificationWidth, chars, charsLim0, charsLim1, runs, locale, bidi)
    }

    fun breakIntoLines(lineWidth: Double): List<Triple<GlyphString<U>, Int, Int>> {
        val lines = mutableListOf<Triple<GlyphString<U>, Int, Int>>()

        val breakIter = BreakIterator.getLineInstance(locale)
        breakIter.text = CharArrayIterator(chars, charsLim0, charsLim1, charsLim0)

        var startCharIdx = charsLim0
        while (startCharIdx < charsLim1) {
            // Find the first char that would be clipped by lineWidth, assuming that we start the line at startCharIdx.
            // Notice that this measurement ignores inter-segment tracking, which is extremely tricky to predict in the
            // presence of potentially wild bidi reordering. We take care of that tracking later.
            val clippedCharIdx = findLowestClippedCharIndexIgnoringInterSegmentTracking(startCharIdx, lineWidth)

            // Find the char where we can end the current line.
            //   - If no char is clipped, end the line at the end of the string.
            //   - If the range between the clipped char (inclusive) and the following newline char (exclusive) is only
            //     whitespace, end the line before the following newline char.
            //   - If the newline char that is or precedes the clipped char lies behind the line's start char, end the
            //     line before that newline char.
            //   - If the line's first char is not already clipped, end the line before the clipped char. This will
            //     split a word in the middle, but there's nothing else we can do.
            //   - Otherwise, end the line after just one char.
            var endCharIdx: Int
            if (clippedCharIdx == null)
                endCharIdx = charsLim1
            else {
                val followingBreak = breakIter.following(clippedCharIdx)
                if (chars.isWhitespaceBetween(clippedCharIdx, followingBreak))
                    endCharIdx = followingBreak
                else {
                    endCharIdx = breakIter.preceding(clippedCharIdx + 1)
                    if (endCharIdx <= startCharIdx)
                        endCharIdx = max(
                            clippedCharIdx,
                            Character.offsetByCodePoints(chars, charsLim0, charsLim1 - charsLim0, startCharIdx, 1)
                        )
                }
            }

            // Remember that our clipped char index might actually be too high in view of inter-segment tracking. Hence,
            // we now do a brute-force check to see whether the line ending at our determined end index actually fits
            // into the allotted line width. If not, we iteratively look for a preceding newline char to end our string.
            // If we run out of newline chars behind the line's start, we iteratively remove individual chars until
            // either the string fits or we just have one char left.
            while (true) {
                val lineStartCharIdx = chars.indexOfFirstNonWhitespace(startCharIdx)
                val lineEndCharIdx = chars.indexOfLastNonWhitespace(endCharIdx - 1) + 1
                if (lineStartCharIdx == -1 || lineEndCharIdx == -1 || lineStartCharIdx >= lineEndCharIdx)
                    break
                val line = sub(lineStartCharIdx - charsLim0, lineEndCharIdx - charsLim0)
                val i = Character.offsetByCodePoints(chars, charsLim0, charsLim1 - charsLim0, lineStartCharIdx, 1)
                if (i == lineEndCharIdx || line.width < lineWidth) {
                    lines += Triple(line, lineStartCharIdx, lineEndCharIdx)
                    break
                }
                val precedingBreak = breakIter.preceding(endCharIdx)
                if (precedingBreak > startCharIdx)
                    endCharIdx = precedingBreak
                else
                    endCharIdx--
            }

            startCharIdx = endCharIdx
        }

        return lines
    }

    private fun findLowestClippedCharIndexIgnoringInterSegmentTracking(minCharIdx: Int, lineWidth: Double): Int? {
        var minCharIdx = minCharIdx
        var remainingLineWidth = lineWidth

        while (true) {
            // Find the segment and lowest actual char index that's at least as big as minCharIdx. Note that some chars
            // are dropped during shaping, and the "at least as big" rule protects us against that.
            val (startCharIdx, seg) = findLowestCharIndex(minCharIdx) ?: return null

            // Find the leftmost (LTR) or rightmost (RTL) edge of any glyph in the segment.
            var outermostX = if (seg.ltr) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
            var startGlyphIdx = -1
            for (glyphIdx in if (seg.ltr) 0..<seg.glyphCount else seg.glyphCount - 1 downTo 0) {
                val glyphCharIdx = charsLim0 + seg.baseCharIndex + seg.getCharIndex(glyphIdx)
                if (glyphCharIdx == startCharIdx) {
                    outermostX = when (seg.ltr) {
                        true -> min(outermostX, seg.getGlyphBoxLeftX(glyphIdx))
                        else -> max(outermostX, seg.getGlyphBoxRightX(glyphIdx))
                    }
                    if (startGlyphIdx == -1)
                        startGlyphIdx = glyphIdx
                } else if (glyphCharIdx > startCharIdx)
                    break
            }

            // Iterate through all glyphs and find whether it's right (LTR) or left (RTL) edge is further away from the
            // above found outermost edge of the segment than remainingLineWidth. If yes, we've found the first char
            // that clips.
            for (glyphIdx in if (seg.ltr) startGlyphIdx..<seg.glyphCount else startGlyphIdx downTo 0) {
                val occupiedWidth = when (seg.ltr) {
                    true -> seg.getGlyphBoxRightX(glyphIdx) - outermostX
                    else -> outermostX - seg.getGlyphBoxLeftX(glyphIdx)
                }
                if (occupiedWidth > remainingLineWidth)
                    return charsLim0 + seg.baseCharIndex + seg.getCharIndex(glyphIdx)
            }

            // If the entire segment fits, subtract its glyph edge-to-edge width from the remaining line width, and
            // earmark the following char (which will be in another segment) to be processed next.
            if (seg.ltr) {
                minCharIdx = charsLim0 + seg.baseCharIndex + seg.getCharIndex(seg.glyphCount - 1) + 1
                remainingLineWidth -= seg.getGlyphBoxRightX(seg.glyphCount - 1) - outermostX
            } else {
                minCharIdx = charsLim0 + seg.baseCharIndex + seg.getCharIndex(0) + 1
                remainingLineWidth -= outermostX - seg.getGlyphBoxLeftX(0)
            }
        }
    }

    private fun findLowestCharIndex(minCharIdx: Int): Pair<Int, Segment<U>>? {
        var foundCharIdx = Int.MAX_VALUE
        var foundSeg: Segment<U>? = null
        for (seg in segments) {
            val segMinCharIdx = charsLim0 + seg.baseCharIndex
            val segMaxCharIdx = segMinCharIdx + seg.getCharIndex(if (seg.ltr) seg.glyphCount - 1 else 0)
            if (segMaxCharIdx >= minCharIdx && segMinCharIdx < foundCharIdx)
                for (glyphIdx in if (seg.ltr) 0..<seg.glyphCount else seg.glyphCount - 1 downTo 0) {
                    val glyphCharIdx = segMinCharIdx + seg.getCharIndex(glyphIdx)
                    if (glyphCharIdx >= minCharIdx) {
                        foundCharIdx = glyphCharIdx
                        foundSeg = seg
                        break
                    }
                }
        }
        return if (foundSeg == null) null else Pair(foundCharIdx, foundSeg)
    }


    companion object {

        fun of(string: String, fontCase: Font.Case): GlyphString<*> {
            val run = Run(0, fontCase, true, true, emptyList(), 1.0, 0.0, null)
            return layout(string.toCharArray(), 0, string.length, listOf(run), Locale.ROOT, null)
        }

        fun <U> of(string: String, runs: List<Run<U>>, locale: Locale): GlyphString<U> {
            return layout(string.toCharArray(), 0, string.length, runs, locale, null)
        }

        private fun <U> layout(
            chars: CharArray,
            charsLim0: Int,
            charsLim1: Int,
            runs: List<Run<U>>,
            locale: Locale,
            forceBidi: Bidi?
        ): GlyphString<U> {
            require(charsLim0 in 0..chars.size) { "Lower chars limit is out of bounds." }
            require(charsLim1 in 0..chars.size) { "Upper chars limit is out of bounds." }
            require(charsLim0 <= charsLim1) { "Lower chars limit must be <= upper chars limit." }

            if (charsLim1 - charsLim0 == 0)
                return GlyphString(emptyList(), 0.0, chars, charsLim0, charsLim1, runs, locale, null)

            require(runs.isNotEmpty()) { "If the string is not empty, there must be at least one run." }
            require(runs.first().startCharIdx <= charsLim0) { "The first run must start before the first char." }
            require(runs.last().startCharIdx < chars.size) { "The last run must start before the string ends." }
            for (runIdx in 0..<runs.size - 1)
                require(runs[runIdx].startCharIdx < runs[runIdx + 1].startCharIdx) { "The runs must be in ascending logical order." }

            val bidi = forceBidi ?: if (!Bidi.requiresBidi(chars, charsLim0, charsLim1)) null else
                Bidi(chars, charsLim0, null, 0, charsLim1 - charsLim0, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)

            val jobs = mutableListOf<SegmentJob<U>>()
            if (bidi != null && bidi.isMixed) {
                val numBidiRuns = bidi.runCount
                val bidiLevels = ByteArray(numBidiRuns) { bidiRunIdx -> bidi.getRunLevel(bidiRunIdx).toByte() }
                val bidiRunIndices = Array(numBidiRuns) { bidiRunIdx -> bidiRunIdx }
                Bidi.reorderVisually(bidiLevels, 0, bidiRunIndices, 0, numBidiRuns)
                for (bri in bidiRunIndices) {
                    val ltr = bidi.getRunLevel(bri) and 1 == 0
                    makeJobsForBidiRun(
                        jobs, chars, runs, charsLim0 + bidi.getRunStart(bri), charsLim0 + bidi.getRunLimit(bri), ltr
                    )
                }
            } else {
                val ltr = bidi == null || bidi.baseIsLeftToRight()
                makeJobsForBidiRun(jobs, chars, runs, charsLim0, charsLim1, ltr)
            }

            val segments = mutableListOf<Segment<U>>()
            var x = 0.0
            for ((jobIdx, job) in jobs.withIndex()) {
                val run = job.run
                // Find the inter-segment tracking to the left and right. Each one is defined as the maximal tracking of
                // the current or the respective neighboring segment. Then add half of those inter-segment trackings to
                // this segment; the neighboring segments will also receive the same half trackings, so in the end, they
                // sum up to the whole inter-segment trackings.
                val padLeft = if (jobIdx == 0) 0.0 else max(run.hsTrck, jobs[jobIdx - 1].run.hsTrck) / 2.0
                val padRight = if (jobIdx == jobs.lastIndex) 0.0 else max(run.hsTrck, jobs[jobIdx + 1].run.hsTrck) / 2.0
                val shapingResult = run.fontCase.shape(
                    chars, job.charsLim0, job.charsLim1 - job.charsLim0, job.ltr, job.script, locale,
                    run.kerning, run.ligatures, run.features, run.tracking, padLeft, 0.0
                )
                val segWidth = shapingResult.boxes.last() * run.hScaling + padRight
                val segString = String(chars, job.charsLim0, job.charsLim1 - job.charsLim0)
                segments += Segment(segString, x, segWidth, job.charsLim0 - charsLim0, job.ltr, run, shapingResult)
                x += segWidth
            }

            return GlyphString(segments, x, chars, charsLim0, charsLim1, runs, locale, bidi)
        }

        private fun <U> makeJobsForBidiRun(
            jobs: MutableList<SegmentJob<U>>,
            chars: CharArray,
            runs: List<Run<U>>,
            bidiRunStartCharIdx: Int,
            bidiRunEndCharIdx: Int,
            ltr: Boolean
        ) {
            for ((runIdx, run) in runs.withIndex()) {
                val runStartCharIdx = run.startCharIdx.coerceAtLeast(bidiRunStartCharIdx)
                if (runStartCharIdx >= bidiRunEndCharIdx)
                    break
                val runEndCharIdx = when {
                    runIdx == runs.lastIndex -> bidiRunEndCharIdx
                    else -> runs[runIdx + 1].startCharIdx.coerceAtMost(bidiRunEndCharIdx)
                }
                @Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")
                if (runEndCharIdx > bidiRunStartCharIdx) {
                    val scriptItr = ScriptRun(chars, runStartCharIdx, runEndCharIdx - runStartCharIdx)
                    while (scriptItr.next())
                        jobs += SegmentJob(run, scriptItr.scriptStart, scriptItr.scriptLimit, ltr, scriptItr.scriptCode)
                }
            }
        }

        private val Run<*>.hsTrck get() = tracking * hScaling

        private fun CharArray.isWhitespaceBetween(startIdx: Int, endIdx: Int): Boolean {
            for (idx in startIdx..<endIdx) if (!get(idx).isWhitespace()) return false
            return true
        }

        private fun CharArray.indexOfFirstNonWhitespace(fromIdx: Int): Int {
            for (idx in fromIdx..<size) if (!get(idx).isWhitespace()) return idx
            return -1
        }

        private fun CharArray.indexOfLastNonWhitespace(fromIdx: Int): Int {
            for (idx in fromIdx downTo 0) if (!get(idx).isWhitespace()) return idx
            return -1
        }

    }


    class Run<U>(
        val startCharIdx: Int,
        val fontCase: Font.Case,
        val kerning: Boolean,
        val ligatures: Boolean,
        val features: List<Font.Feature>,
        val hScaling: Double,
        val tracking: Double,
        val userData: U
    )


    class Segment<U>(
        val string: String,
        val baseX: Double,
        val width: Double,
        val baseCharIndex: Int,
        val ltr: Boolean,
        private val run: Run<U>,
        private val sr: Font.Case.ShapingResult
    ) {

        val fontCase: Font.Case get() = run.fontCase
        val userData: U get() = run.userData

        val glyphCount: Int get() = sr.glyphs.size
        val hasMissingGlyph: Boolean get() = sr.glyphs.any { it == 0 }
        val hasNonMissingGlyph: Boolean get() = sr.glyphs.any { it != 0 }
        fun getGlyph(glyphIdx: Int): Int = sr.glyphs[glyphIdx]
        fun getGlyphPositionXPreHScaling(glyphIdx: Int): Double = sr.positions[glyphIdx * 2]
        fun getGlyphPositionY(glyphIdx: Int): Double = sr.positions[glyphIdx * 2 + 1]
        fun getGlyphBoxLeftX(glyphIdx: Int): Double = sr.boxes[glyphIdx * 2] * run.hScaling
        fun getGlyphBoxRightX(glyphIdx: Int): Double = sr.boxes[glyphIdx * 2 + 1] * run.hScaling
        fun getCharIndex(glyphIdx: Int): Int = sr.charIndices[glyphIdx]

        fun getOutlineBounds(dx: Double, dy: Double): Rectangle2D {
            val sx = run.hScaling
            val glyphs = sr.glyphs
            val pos = sr.positions
            var bounds: Rectangle2D? = null
            for (i in 0..<glyphCount) {
                val glyphBounds = fontCase.getGlyphBounds(glyphs[i])
                val dx2 = pos[i * 2] * sx + dx
                val dy2 = pos[i * 2 + 1] + dy
                var minX = glyphBounds.minX * sx + dx2
                var minY = glyphBounds.minY + dy2
                var maxX = glyphBounds.maxX * sx + dx2
                var maxY = glyphBounds.maxY + dy2
                if (bounds == null)
                    bounds = Rectangle2D.Double()
                else {
                    minX = min(minX, bounds.minX)
                    minY = min(minY, bounds.minY)
                    maxX = max(maxX, bounds.maxX)
                    maxY = max(maxY, bounds.maxY)
                }
                bounds.setRect(minX, minY, maxX - minX, maxY - minY)
            }
            return bounds ?: Rectangle2D.Double()
        }

        fun appendOutlineTo(path: Path2D.Float, dx: Double, dy: Double): Path2D.Float {
            val sx = run.hScaling
            val glyphs = sr.glyphs
            val pos = sr.positions
            for (i in 0..<glyphCount) {
                val transform = AffineTransform.getTranslateInstance(pos[i * 2] * sx + dx, pos[i * 2 + 1] + dy)
                    .apply { scale(sx, 1.0) }
                path.append(fontCase.getGlyphOutline(glyphs[i]).getPathIterator(transform), false)
            }
            return path
        }

        fun withBaseOffsetAndInjectedGaps(baseOffset: Double, beforeGlyphIndices: Set<Int>, gap: Double): Segment<U> {
            val oldPos = sr.positions
            val oldBoxes = sr.boxes
            val newPos = DoubleArray(oldPos.size)
            val newBoxes = DoubleArray(oldBoxes.size)
            var shift = 0.0
            for (glyphIdx in 0..<glyphCount) {
                if (glyphIdx in beforeGlyphIndices)
                    shift += gap
                newPos[glyphIdx * 2 + 0] = oldPos[glyphIdx * 2 + 0] + shift
                newPos[glyphIdx * 2 + 1] = oldPos[glyphIdx * 2 + 1]
                newBoxes[glyphIdx * 2 + 0] = newBoxes[glyphIdx * 2 + 0] + shift
                newBoxes[glyphIdx * 2 + 1] = newBoxes[glyphIdx * 2 + 1] + shift
            }
            val newSR = Font.Case.ShapingResult(sr.glyphs, newPos, newBoxes, sr.charIndices)
            return Segment(
                string, baseX + baseOffset, width + beforeGlyphIndices.size * gap, baseCharIndex, ltr, run, newSR
            )
        }

    }


    private class SegmentJob<U>(
        val run: Run<U>,
        val charsLim0: Int,
        val charsLim1: Int,
        val ltr: Boolean,
        val script: Int
    )


    private class CharArrayIterator(
        private val chars: CharArray,
        private val charsLim0: Int,
        private val charsLim1: Int,
        private var pos: Int
    ) : CharacterIterator {

        override fun first(): Char {
            pos = charsLim0
            return current()
        }

        override fun last(): Char {
            pos = if (charsLim0 != charsLim1) charsLim1 - 1 else charsLim1
            return current()
        }

        override fun current() = if (pos in charsLim0..<charsLim1) chars[pos] else CharacterIterator.DONE

        override fun next() =
            if (pos < charsLim1 - 1) {
                chars[++pos]
            } else {
                pos = charsLim1
                CharacterIterator.DONE
            }

        override fun previous() = if (pos > charsLim0) chars[--pos] else CharacterIterator.DONE

        override fun setIndex(position: Int): Char {
            require(position in charsLim0..charsLim1)
            pos = position
            return current()
        }

        override fun getBeginIndex() = charsLim0
        override fun getEndIndex() = charsLim1
        override fun getIndex() = pos
        override fun clone() = CharArrayIterator(chars, charsLim0, charsLim1, pos)

    }

}
