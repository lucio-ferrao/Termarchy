package com.mrndtvndv.term.server

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellCompletionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun commandsAreTrimmedDeduplicatedAndSorted() {
        assertEquals(listOf("git", "ls", "make"), parseShellCommands("ls\ngit\n  make \nls\n\nbad name\n"))
    }

    @Test
    fun listingKeepsFoldersWithTheirSlash() {
        assertEquals(listOf("src/", "README.md", ".git/"), parseFolderListing("src/\nREADME.md\n.git/\n"))
    }

    @Test
    fun listingScriptListsHiddenEntriesAndMarksFolders() {
        val folder = temp.newFolder("it's here")
        File(folder, "notes.txt").writeText("")
        File(folder, ".hidden").writeText("")
        File(folder, "src").mkdir()

        val output = runSh(folderListingScript(folder.path))

        assertEquals(setOf(".hidden", "notes.txt", "src/"), parseFolderListing(output).toSet())
    }

    @Test
    fun extrasLeaveOutEmptyLists() {
        assertEquals(mapOf(IME_COMMANDS_KEY to listOf("ls")), shellCompletionsImeExtras(listOf("ls"), emptyList()))
        assertEquals(emptyMap<String, List<String>>(), shellCompletionsImeExtras(emptyList(), emptyList()))
    }

    @Test
    fun onlyShellsComplete() {
        assertTrue(isCompletingShell("bash"))
        assertTrue(isCompletingShell("sh"))
        assertFalse(isCompletingShell("python3"))
    }

    private fun runSh(script: String): String {
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return output
    }
}
