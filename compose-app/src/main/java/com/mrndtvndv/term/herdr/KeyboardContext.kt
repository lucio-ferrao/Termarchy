package com.mrndtvndv.term.herdr

/**
 * What the keyboard is told about the program a terminal runs, through `EditorInfo.privateImeOptions`, so it can adapt
 * (Homekey writes prose for an agent, completes commands without corrections in a shell, and stays out of the way of a
 * full-screen program).
 */
data class KeyboardContext(val kind: Kind, val program: String) {
    enum class Kind { AGENT, SHELL, FULLSCREEN }

    /** The entry for `privateImeOptions`, e.g. `org.ferrao.homekey.context=shell:bash`. */
    val imeOption: String get() = "$IME_OPTION_PREFIX${kind.name.lowercase()}:$program"

    companion object {
        const val IME_OPTION_PREFIX = "org.ferrao.homekey.context="

        /** A terminal whose program is not known (a plain SSH or local shell): commands are typed there. */
        val PLAIN_SHELL = KeyboardContext(Kind.SHELL, "sh")
    }
}

/**
 * The context of a herdr pane: the agent it runs (`claude`, `codex`…), else the program in its foreground (herdr's
 * `foreground_process`) — full-screen when it shows the alternate screen (herdr's `screen`), a command line otherwise (a
 * shell, a REPL); a shell when herdr does not report the program.
 */
fun keyboardContextOf(agent: String?, foregroundProcess: String?, alternateScreen: Boolean): KeyboardContext {
    agent?.let(::programName)?.takeIf { it.isNotEmpty() }?.let { return KeyboardContext(KeyboardContext.Kind.AGENT, it) }
    val name = foregroundProcess?.let(::programName)?.takeIf { it.isNotEmpty() }
    if (alternateScreen) return KeyboardContext(KeyboardContext.Kind.FULLSCREEN, name ?: "app")
    return name?.let { KeyboardContext(KeyboardContext.Kind.SHELL, it) } ?: KeyboardContext.PLAIN_SHELL
}

/** A program name fit for the option: no path, no login-shell dash, only characters that cannot end the entry. */
private fun programName(raw: String): String =
    raw.substringAfterLast('/').removePrefix("-").lowercase().filter { it.isLetterOrDigit() || it in "._+-" }
