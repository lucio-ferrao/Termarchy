package com.termux.terminal.compose.gpu

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.os.Build

/** Marks a cell that cannot join a contextual run. */
internal const val NoRun = -1

/** A cell's face: the family's bold font when [bold], or Android's synthetic bold when the family has none. */
internal fun cellTypeface(typeface: Typeface?, bold: Boolean): Typeface {
    val base = typeface ?: Typeface.MONOSPACE
    return if (bold) Typeface.create(base, Typeface.BOLD) else base
}

/**
 * The runs of neighbouring cells shaped together, so contextual alternates (JetBrains Mono's ligatures: `->`, `!=`, `===`)
 * see their neighbours. [faces] holds each cell's face (0 regular, 1 bold), or [NoRun] where the cell cannot join a
 * run; a run also ends where the face changes. Single cells have no context and are left out.
 */
internal fun contextualRuns(faces: IntArray): List<IntRange> {
    val runs = ArrayList<IntRange>()
    var start = 0
    while (start < faces.size) {
        val face = faces[start]
        var end = start + 1
        if (face != NoRun) {
            while (end < faces.size && faces[end] == face) end++
            if (end - start >= 2) runs += start until end
        }
        start = end
    }
    return runs
}

/** Shapes a run of cells as one piece of text. */
internal fun interface TerminalRunShaper {
    /** One glyph id per char of text[start, start + count), or null when the glyphs do not map one to one onto the chars. */
    fun glyphIds(text: CharArray, start: Int, count: Int, paint: Paint): IntArray?
}

/** Android's shaper, from Android 12; before it every cell is drawn on its own, without ligatures. */
internal object PlatformRunShaper : TerminalRunShaper {
    override fun glyphIds(text: CharArray, start: Int, count: Int, paint: Paint): IntArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val glyphs = TextRunShaper.shapeTextRun(text, start, count, start, count, 0f, 0f, false, paint)
        if (glyphs.glyphCount() != count) return null
        return IntArray(count) { glyphs.getGlyphId(it) }
    }
}
