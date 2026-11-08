package com.mrndtvndv.term.data.prefs

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.os.Build
import android.util.Log
import com.mrndtvndv.term.data.displayName
import java.io.File

class CustomFontStore(private val context: Context) {
    private val file get() = File(context.filesDir, "font.ttf")

    fun import(uri: Uri): String? =
        runCatching {
            val input = checkNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open $uri" }
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
            context.displayName(uri) ?: "custom_font.ttf"
        }.onFailure { Log.e(TAG, "Failed to copy font", it) }.getOrNull()

    fun clear() {
        file.delete()
    }

    fun loadTypeface(): Typeface? {
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            Typeface.createFromFile(file)
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * The terminal's typeface: the imported font, or Paper Mono (regular and bold, as in the Ghostty config), then Noto
     * Sans Symbols 2 for the symbols those lack (e.g. ⏵ U+23F5 in Claude Code's status line), then the Nerd Font icons
     * (eza --icons), then the system fonts. The first font keeps setting the cell metrics. Before Android 10 there is no
     * symbols fallback.
     */
    fun loadTerminalTypeface(): Typeface {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return loadPrimaryTypeface()
        return try {
            val primary = if (file.isFile && file.length() > 0L) {
                FontFamily.Builder(Font.Builder(file).build()).build()
            } else {
                FontFamily.Builder(Font.Builder(context.assets, PAPER_MONO).build())
                    .addFont(Font.Builder(context.assets, PAPER_MONO_BOLD).build())
                    .build()
            }
            Typeface.CustomFallbackBuilder(primary)
                .addCustomFallback(FontFamily.Builder(Font.Builder(context.assets, SYMBOLS_FONT).build()).build())
                .addCustomFallback(FontFamily.Builder(Font.Builder(context.assets, NERD_SYMBOLS_FONT).build()).build())
                .setSystemFallback("monospace")
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add the symbols fallback", e)
            loadPrimaryTypeface()
        }
    }

    private fun loadPrimaryTypeface(): Typeface = loadTypeface() ?: Typeface.createFromAsset(context.assets, PAPER_MONO)

    private companion object {
        const val TAG = "CustomFontStore"
        /** Paper Mono (paper-design/paper-mono, OFL), the font of the Ghostty config on the PC. */
        const val PAPER_MONO = "fonts/PaperMono-Regular.otf"
        const val PAPER_MONO_BOLD = "fonts/PaperMono-Bold.otf"
        const val SYMBOLS_FONT = "fonts/NotoSansSymbols2-Regular.ttf"
        /** The icons of the Nerd Fonts (file types, git, folders), drawn by eza --icons, starship, lazygit… */
        const val NERD_SYMBOLS_FONT = "fonts/SymbolsNerdFontMono-Regular.ttf"
    }
}
