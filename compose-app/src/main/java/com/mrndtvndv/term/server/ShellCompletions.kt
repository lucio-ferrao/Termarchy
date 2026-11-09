package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.workspace.shellQuote
import kotlinx.coroutines.CancellationException

/**
 * What Keyarchy completes in a shell, as `EditorInfo.extras` string arrays: the commands of the remote bash (for the
 * first word of a line) and the entries of the pane's folder, folders ending in `/` (for the words after it).
 */
const val IME_COMMANDS_KEY = "org.ferrao.homekey.commands"
const val IME_FILES_KEY = "org.ferrao.homekey.files"

/** The shells whose command line the commands and files complete; a REPL or another prompt gets neither. */
private val SHELL_PROGRAMS = setOf("bash", "sh", "zsh", "fish", "dash", "ksh", "ash")

private const val MAX_FOLDER_ENTRIES = 2000

/**
 * Every command the user's bash completes (`compgen -c`: PATH, aliases, functions, builtins), as a login terminal has
 * them. The interactive shell may warn about job control on stderr, which is dropped.
 */
private const val SHELL_COMMANDS_SCRIPT = "bash -lic 'compgen -c' </dev/null 2>/dev/null"

fun isCompletingShell(program: String): Boolean = program in SHELL_PROGRAMS

internal fun folderListingScript(folder: String): String =
    "cd ${shellQuote(folder)} && ls -Ap | head -n $MAX_FOLDER_ENTRIES"

/** One command per line, without duplicates (a name can be both an alias and a file), in order. */
internal fun parseShellCommands(output: String): List<String> =
    output.lineSequence().map(String::trim).filter { it.isNotEmpty() && it.none(Char::isWhitespace) }
        .distinct().sorted().toList()

/** `ls -Ap` prints one entry per line, folders with a trailing `/`. */
internal fun parseFolderListing(output: String): List<String> = output.lineSequence().filter { it.isNotEmpty() }.toList()

/** The extras for the keyboard; empty lists are left out. */
fun shellCompletionsImeExtras(commands: List<String>, files: List<String>): Map<String, List<String>> = buildMap {
    if (commands.isNotEmpty()) put(IME_COMMANDS_KEY, commands)
    if (files.isNotEmpty()) put(IME_FILES_KEY, files)
}

/** The commands of the remote bash; empty when it cannot be asked. */
internal suspend fun SshSession.fetchShellCommands(): List<String> =
    completionOutput(SHELL_COMMANDS_SCRIPT)?.let(::parseShellCommands).orEmpty()

/** The entries of [folder] on the remote host; empty when it cannot be listed. */
suspend fun SshSession.listFolder(folder: String): List<String> =
    completionOutput(folderListingScript(folder))?.let(::parseFolderListing).orEmpty()

/** Completions are a convenience: a failed command (no bash, a folder gone) just leaves the keyboard without them. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private suspend fun SshSession.completionOutput(script: String): String? = try {
    execCommand(script)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
