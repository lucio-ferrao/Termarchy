package com.termux.terminal.compose.gpu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build
import kotlin.math.ceil
import java.util.HashMap

internal const val GlesItalicTextSkewX = -0.35f

/** Hard bounds for one RGBA glyph atlas. */
data class GlyphAtlasLimits(
    val pageSizePx: Int = 512,
    val maxPages: Int = 2,
    val maxEntries: Int = 2048,
    val paddingPx: Int = 1,
    val maxGlyphWidthPx: Int = minOf(256, pageSizePx),
    val maxGlyphHeightPx: Int = minOf(128, pageSizePx)
) {
    init {
        require(pageSizePx >= 32) { "pageSizePx must be at least 32" }
        require(maxPages in 1..8) { "maxPages must be between 1 and 8" }
        require(maxEntries >= 1) { "maxEntries must be positive" }
        require(paddingPx >= 0) { "paddingPx must not be negative" }
        require(maxGlyphWidthPx > paddingPx * 2) { "maxGlyphWidthPx leaves no glyph area" }
        require(maxGlyphHeightPx > paddingPx * 2) { "maxGlyphHeightPx leaves no glyph area" }
        require(maxGlyphWidthPx <= pageSizePx) { "maxGlyphWidthPx exceeds pageSizePx" }
        require(maxGlyphHeightPx <= pageSizePx) { "maxGlyphHeightPx exceeds pageSizePx" }
    }

    companion object {
        fun forGlMaxTextureSize(maxTextureSize: Int): GlyphAtlasLimits {
            val maxPages = if (maxTextureSize >= 64) 2 else 1
            val pageSize = minOf(512, maxTextureSize.coerceAtLeast(32) / maxPages)
            return GlyphAtlasLimits(
                pageSizePx = pageSize,
                maxPages = maxPages,
                maxGlyphWidthPx = minOf(256, pageSize),
                maxGlyphHeightPx = minOf(128, pageSize)
            )
        }
    }
}

/** A complete cache key; UTF-16 text is retained exactly, including surrogate pairs. */
data class GlyphAtlasKey(
    val text: String,
    val foregroundArgb: Int,
    val typeface: Typeface?,
    val fontSizePx: Float,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val fontAscentPx: Float,
    val cellSpan: Int,
    val textScaleX: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikeThrough: Boolean,
    val rasterMode: Int = RASTER_MODE_RGBA,
    /** The glyph [text] takes in [context], or [NO_GLYPH] for a cell drawn on its own. */
    val glyphId: Int = NO_GLYPH,
    /** The run [text] was shaped in, starting at [contextOffset]; the atlas shares one entry per [glyphId], whatever the run. */
    val context: String? = null,
    val contextOffset: Int = 0
) {
    init {
        require(text.isNotEmpty()) { "GlyphAtlasKey text must not be empty" }
        require(context == null || context.startsWith(text, contextOffset)) { "context must contain text at contextOffset" }
        require(cellSpan >= 1) { "cellSpan must be positive" }
        require(fontSizePx > 0f) { "fontSizePx must be positive" }
        require(cellWidthPx > 0f) { "cellWidthPx must be positive" }
        require(cellHeightPx > 0f) { "cellHeightPx must be positive" }
        require(textScaleX > 0f) { "textScaleX must be positive" }
        require(rasterMode == RASTER_MODE_RGBA || rasterMode == RASTER_MODE_MASK) {
            "unsupported raster mode: $rasterMode"
        }
    }

    companion object {
        const val RASTER_MODE_RGBA = 0
        const val RASTER_MODE_MASK = 1
        const val NO_GLYPH = -1
    }
}

/** Atlas lookup data omits foreground for mask glyphs shared by all colors. */
private data class GlyphAtlasLookupKey(
    val text: String,
    val typeface: Typeface?,
    val fontSizePx: Float,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val fontAscentPx: Float,
    val cellSpan: Int,
    val textScaleX: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikeThrough: Boolean,
    val rasterMode: Int,
    val glyphId: Int,
    val foregroundArgb: Int?
)

private fun GlyphAtlasKey.lookupKey(): GlyphAtlasLookupKey = GlyphAtlasLookupKey(
    text = text,
    typeface = typeface,
    fontSizePx = fontSizePx,
    cellWidthPx = cellWidthPx,
    cellHeightPx = cellHeightPx,
    fontAscentPx = fontAscentPx,
    cellSpan = cellSpan,
    textScaleX = textScaleX,
    bold = bold,
    italic = italic,
    underline = underline,
    strikeThrough = strikeThrough,
    rasterMode = rasterMode,
    glyphId = glyphId,
    foregroundArgb = foregroundArgb.takeIf { rasterMode == GlyphAtlasKey.RASTER_MODE_RGBA }
)

/** Atlas coordinates include transparent padding and are valid only for their generation. */
data class GlyphAtlasRegion(
    val pageIndex: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val atlasGeneration: Int,
    /** How the fragment shader should interpret this RGBA atlas region. */
    val rasterMode: Int = GlyphAtlasKey.RASTER_MODE_RGBA,
    /** Screen offset from the terminal cell origin to the atlas quad origin. */
    val drawOffsetX: Float = 0f,
    val drawOffsetY: Float = 0f
) {
    val right: Int
        get() = left + width

    val bottom: Int
        get() = top + height
}

internal data class GlyphAtlasAllocation(
    val region: GlyphAtlasRegion,
    val reset: Boolean
)

/**
 * Deterministic shelf allocator and bounded key cache. It has no GLES or
 * Android bitmap dependency, which keeps capacity and reset behavior JVM-testable.
 */
internal class GlyphAtlasAllocator(
    private val limits: GlyphAtlasLimits
) {
    private val pages = ArrayList<ShelfPage>(limits.maxPages)
    private val entries = HashMap<GlyphAtlasLookupKey, GlyphAtlasRegion>(
        minOf(limits.maxEntries, 64),
        0.75f
    )
    private var generation = 1
    private var usedAreaPx = 0
    private var cacheHits = 0L
    private var cacheMisses = 0L
    private var resetCount = 0L
    private var largestAllocationPx = 0

    fun find(key: GlyphAtlasKey): GlyphAtlasRegion? {
        val region = entries[key.lookupKey()]
        if (region == null) {
            cacheMisses++
        } else {
            cacheHits++
        }
        return region
    }

    fun allocateNew(
        key: GlyphAtlasKey,
        width: Int,
        height: Int,
        drawOffsetX: Float = 0f,
        drawOffsetY: Float = 0f,
        rasterMode: Int = key.rasterMode
    ): GlyphAtlasAllocation? {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        if (width > limits.maxGlyphWidthPx || height > limits.maxGlyphHeightPx) return null

        var didReset = false
        if (entries.size >= limits.maxEntries) {
            reset()
            didReset = true
        }

        var allocation = allocateOnExistingPages(width, height)
        if (allocation == null && pages.size < limits.maxPages) {
            pages += ShelfPage(limits.pageSizePx)
            allocation = allocateOnPage(pages.lastIndex, width, height)
        }
        if (allocation == null) {
            reset()
            didReset = true
            pages += ShelfPage(limits.pageSizePx)
            allocation = allocateOnPage(pages.lastIndex, width, height)
        }
        val placement = allocation ?: return null
        val region = GlyphAtlasRegion(
            pageIndex = placement.pageIndex,
            left = placement.left,
            top = placement.top,
            width = width,
            height = height,
            atlasGeneration = generation,
            rasterMode = rasterMode,
            drawOffsetX = drawOffsetX,
            drawOffsetY = drawOffsetY
        )
        entries[key.lookupKey()] = region
        usedAreaPx += width * height
        largestAllocationPx = maxOf(largestAllocationPx, width * height)
        return GlyphAtlasAllocation(region, didReset)
    }

    fun isCurrent(region: GlyphAtlasRegion): Boolean =
        region.atlasGeneration == generation &&
            region.pageIndex in pages.indices

    fun reset() {
        entries.clear()
        pages.clear()
        usedAreaPx = 0
        generation++
        resetCount++
    }

    fun currentGeneration(): Int = generation

    fun diagnostics(): GlesAtlasDiagnostics = GlesAtlasDiagnostics(
        generation = generation,
        pageCount = pages.size,
        maxPages = limits.maxPages,
        pageSizePx = limits.pageSizePx,
        usedAreaPx = usedAreaPx,
        entryCount = entries.size,
        maxEntries = limits.maxEntries,
        cacheHits = cacheHits,
        cacheMisses = cacheMisses,
        resetCount = resetCount,
        largestAllocationPx = largestAllocationPx
    )

    private fun allocateOnExistingPages(width: Int, height: Int): Placement? {
        for (pageIndex in pages.indices) {
            return allocateOnPage(pageIndex, width, height) ?: continue
        }
        return null
    }

    private fun allocateOnPage(pageIndex: Int, width: Int, height: Int): Placement? {
        val placement = pages[pageIndex].allocate(width, height) ?: return null
        return Placement(pageIndex, placement.left, placement.top)
    }

    private data class Placement(
        val pageIndex: Int,
        val left: Int,
        val top: Int
    )

    private class ShelfPage(private val size: Int) {
        private var cursorX = 0
        private var cursorY = 0
        private var rowHeight = 0

        fun allocate(width: Int, height: Int): Placement? {
            if (width > size || height > size) return null
            if (cursorX + width > size) {
                cursorX = 0
                cursorY += rowHeight
                rowHeight = 0
            }
            if (cursorY + height > size) return null
            val placement = Placement(-1, cursorX, cursorY)
            cursorX += width
            rowHeight = maxOf(rowHeight, height)
            return placement
        }
    }
}

internal data class GlyphRasterGeometry(
    val width: Int,
    val height: Int,
    val drawOffsetX: Float,
    val drawOffsetY: Float,
    val drawOriginX: Float,
    val drawBaselineY: Float
)

internal data class GlyphPaintBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top
}

/**
 * Computes a bitmap rectangle and its bearing from Android's painted bounds.
 * The atlas quad is positioned at the terminal cell origin, not at the bitmap
 * origin, so skewed glyph overhangs cannot be clipped at cell boundaries.
 */
internal fun glyphRasterGeometry(
    bounds: GlyphPaintBounds,
    measuredWidth: Float,
    cellHeightPx: Float,
    fontAscentPx: Float,
    paddingPx: Int
): GlyphRasterGeometry {
    val fallbackWidth = ceil(measuredWidth.coerceAtLeast(1f)).toInt()
    val boundsWidth = bounds.width
    val boundsHeight = bounds.height
    if (boundsWidth <= 0 || boundsHeight <= 0) {
        val width = fallbackWidth + paddingPx * 2
        val height = ceil(cellHeightPx).toInt() + paddingPx * 2
        val drawOriginX = paddingPx.toFloat()
        val drawBaselineY = paddingPx.toFloat() - fontAscentPx
        return GlyphRasterGeometry(
            width = width,
            height = height,
            drawOffsetX = -drawOriginX,
            drawOffsetY = -paddingPx.toFloat(),
            drawOriginX = drawOriginX,
            drawBaselineY = drawBaselineY
        )
    }

    val width = maxOf(boundsWidth, fallbackWidth) + paddingPx * 2
    val height = boundsHeight + paddingPx * 2
    val drawOriginX = paddingPx - bounds.left.toFloat()
    val drawBaselineY = paddingPx - bounds.top.toFloat()
    return GlyphRasterGeometry(
        width = width,
        height = height,
        drawOffsetX = -drawOriginX,
        drawOffsetY = -fontAscentPx - drawBaselineY,
        drawOriginX = drawOriginX,
        drawBaselineY = drawBaselineY
    )
}

/** How many cells a contextual glyph may reach past its own, each side: a ligature like `<!--` reaches back three. */
private const val ContextualReachCells = 4

/**
 * Bounds of the painted pixels in a [width] x [height] bitmap, relative to the pen at ([originX], [baselineY]); empty
 * when nothing was painted.
 */
internal fun inkBounds(pixels: IntArray, width: Int, height: Int, originX: Int, baselineY: Int): GlyphPaintBounds {
    var left = width
    var top = height
    var right = 0
    var bottom = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (pixels[y * width + x] ushr 24 == 0) continue
            if (x < left) left = x
            if (x >= right) right = x + 1
            if (y < top) top = y
            bottom = y + 1
        }
    }
    if (right <= left) return GlyphPaintBounds(0, 0, 0, 0)
    return GlyphPaintBounds(left - originX, top - baselineY, right - originX, bottom - baselineY)
}

internal data class RasterizedGlyph(
    val bitmap: Bitmap,
    val drawOffsetX: Float,
    val drawOffsetY: Float,
    val rasterMode: Int
)

/** Android Paint/Canvas rasterizer; it is called only by the GL renderer thread. */
internal class AndroidGlyphRasterizer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var pixelScratch = IntArray(0)

    @Suppress("LongMethod")
    fun rasterize(key: GlyphAtlasKey, limits: GlyphAtlasLimits): RasterizedGlyph? {
        paint.reset()
        paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        paint.typeface = cellTypeface(key.typeface, key.bold)
        paint.textSize = key.fontSizePx
        val maskRequested = key.rasterMode == GlyphAtlasKey.RASTER_MODE_MASK
        paint.color = if (maskRequested) 0xFFFFFFFF.toInt() else key.foregroundArgb
        paint.textScaleX = key.textScaleX
        paint.textSkewX = if (key.italic) GlesItalicTextSkewX else 0f
        paint.isUnderlineText = key.underline
        paint.isStrikeThruText = key.strikeThrough

        val measuredWidth = paint.measureText(key.text).coerceAtLeast(1f)
        val availableWidth = limits.maxGlyphWidthPx - limits.paddingPx * 2
        if (availableWidth <= 0) return null
        if (measuredWidth > availableWidth) {
            paint.textScaleX *= availableWidth / measuredWidth
        }
        val contextualGlyph = contextualGlyph(key)
        val chars = key.text.toCharArray()
        val draw: (Canvas, Float, Float) -> Unit = contextualGlyph ?: { canvas, x, baseline ->
            canvas.drawTextRun(chars, 0, chars.size, 0, chars.size, x, baseline, false, paint)
        }
        val geometry = glyphRasterGeometry(
            bounds = if (contextualGlyph == null) textBounds(key.text) else drawnBounds(draw, key),
            measuredWidth = paint.measureText(key.text),
            cellHeightPx = key.cellHeightPx,
            fontAscentPx = key.fontAscentPx,
            paddingPx = limits.paddingPx
        )
        if (geometry.width > limits.maxGlyphWidthPx || geometry.height > limits.maxGlyphHeightPx) {
            return null
        }

        val bitmap = Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), geometry.drawOriginX, geometry.drawBaselineY)
        val rasterMode = if (maskRequested && bitmapContainsColor(bitmap)) {
            GlyphAtlasKey.RASTER_MODE_RGBA
        } else {
            key.rasterMode
        }
        return RasterizedGlyph(
            bitmap = bitmap,
            drawOffsetX = geometry.drawOffsetX,
            drawOffsetY = geometry.drawOffsetY,
            rasterMode = rasterMode
        )
    }

    private fun textBounds(text: String): GlyphPaintBounds {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        return GlyphPaintBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    /**
     * Draws the glyph [key] takes in its run. Android shapes a range of text without its neighbours, even when given
     * them as context, so the run is shaped whole and the cell's glyph drawn by id; underline and strikethrough cells
     * never join a run, since glyphs are drawn without them.
     */
    private fun contextualGlyph(key: GlyphAtlasKey): ((Canvas, Float, Float) -> Unit)? {
        val context = key.context ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val chars = context.toCharArray()
        val glyphs = TextRunShaper.shapeTextRun(chars, 0, chars.size, 0, chars.size, 0f, 0f, false, paint)
        if (glyphs.glyphCount() != chars.size) return null
        val glyphId = intArrayOf(glyphs.getGlyphId(key.contextOffset))
        val font = glyphs.getFont(key.contextOffset)
        return { canvas, x, baseline -> canvas.drawGlyphs(glyphId, 0, floatArrayOf(x, baseline), 0, 1, font, paint) }
    }

    /**
     * Paint measures only text, not a glyph drawn by id, so the glyph is drawn on a scratch bitmap and measured from
     * the pixels. A ligature drawn on the run's last cell reaches back over the cells before it.
     */
    private fun drawnBounds(draw: (Canvas, Float, Float) -> Unit, key: GlyphAtlasKey): GlyphPaintBounds {
        val reach = ceil(key.cellWidthPx * ContextualReachCells).toInt()
        val metrics = paint.fontMetricsInt
        val baseline = 1 - metrics.top
        val width = reach * 2 + ceil(key.cellWidthPx * key.cellSpan).toInt()
        val height = baseline + metrics.bottom + 1
        val scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(scratch), reach.toFloat(), baseline.toFloat())
            val pixelCount = width * height
            if (pixelScratch.size < pixelCount) pixelScratch = IntArray(pixelCount)
            scratch.getPixels(pixelScratch, 0, width, 0, 0, width, height)
            return inkBounds(pixelScratch, width, height, originX = reach, baselineY = baseline)
        } finally {
            scratch.recycle()
        }
    }

    private fun bitmapContainsColor(bitmap: Bitmap): Boolean {
        val pixelCount = bitmap.width * bitmap.height
        if (pixelScratch.size < pixelCount) pixelScratch = IntArray(pixelCount)
        bitmap.getPixels(pixelScratch, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        repeat(pixelCount) { index ->
            val pixel = pixelScratch[index]
            if (((pixel ushr 24) and 0xFF) != 0) {
                val red = (pixel ushr 16) and 0xFF
                val green = (pixel ushr 8) and 0xFF
                val blue = pixel and 0xFF
                if (red != green || green != blue) return true
            }
        }
        return false
    }
}

/** GLES-backed atlas that uploads only newly rasterized bounded regions. */
internal class GlesGlyphAtlas(
    private val limits: GlyphAtlasLimits
) {
    private val allocator = GlyphAtlasAllocator(limits)
    private val rasterizer = AndroidGlyphRasterizer()
    private var textureId = 0

    val pageSizePx: Int
        get() = limits.pageSizePx

    val textureWidthPx: Int
        get() = limits.pageSizePx * limits.maxPages

    val maxPages: Int
        get() = limits.maxPages

    val generation: Int
        get() = allocator.currentGeneration()

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun resolve(
        key: GlyphAtlasKey,
        beforeReset: () -> Unit = {}
    ): GlyphAtlasRegion? {
        val existing = allocator.find(key)
        if (existing != null) return existing

        val rasterized = rasterizer.rasterize(key, limits) ?: return null
        val allocation = allocator.allocateNew(
            key = key,
            width = rasterized.bitmap.width,
            height = rasterized.bitmap.height,
            drawOffsetX = rasterized.drawOffsetX,
            drawOffsetY = rasterized.drawOffsetY,
            rasterMode = rasterized.rasterMode
        )
        if (allocation == null) {
            if (!rasterized.bitmap.isRecycled) rasterized.bitmap.recycle()
            return null
        }
        try {
            if (allocation.reset) {
                try {
                    beforeReset()
                } finally {
                    deleteTextures()
                }
            }
            val texture = ensureTexture()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                physicalLeft(allocation.region),
                allocation.region.top,
                rasterized.bitmap
            )
            checkGlError("atlas-upload")
        } catch (error: RuntimeException) {
            reset(beforeReset)
            throw GlesResourceException("atlas texture upload failed", error)
        } finally {
            if (!rasterized.bitmap.isRecycled) rasterized.bitmap.recycle()
        }
        return allocation.region
    }

    fun diagnostics(): GlesAtlasDiagnostics = allocator.diagnostics()

    fun textureId(): Int = textureId

    fun reset(beforeReset: () -> Unit = {}) {
        try {
            try {
                beforeReset()
            } finally {
                deleteTextures()
            }
        } finally {
            allocator.reset()
        }
    }

    fun release() {
        try {
            deleteTextures()
        } finally {
            allocator.reset()
        }
    }

    private fun ensureTexture(): Int {
        if (textureId != 0) return textureId
        val texture = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        textureId = texture[0]
        checkGlError("atlas-generate")
        if (textureId == 0) throw GlesResourceException("atlas texture: glGenTextures returned 0")
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            textureWidthPx,
            limits.pageSizePx,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        checkGlError("atlas-allocate")
        return textureId
    }

    private fun physicalLeft(region: GlyphAtlasRegion): Int =
        region.pageIndex * limits.pageSizePx + region.left

    private fun deleteTextures() {
        if (textureId == 0) return
        try {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            checkGlError("atlas-delete")
        } finally {
            textureId = 0
        }
    }

    private fun checkGlError(stage: String) {
        repeat(8) {
            val error = GLES30.glGetError()
            if (error == GLES30.GL_NO_ERROR) return
            throw GlesResourceException("$stage: 0x${error.toString(16)}")
        }
    }

}
