package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

sealed interface WorkspaceDialogs {
    data class NewWorkspace(val defaultRoot: String) : WorkspaceDialogs
    data class RenameWorkspace(val workspaceId: String, val name: String) : WorkspaceDialogs
    data class RenameTerminal(val tabId: String, val name: String?) : WorkspaceDialogs
    data class DeleteWorkspace(val workspaceId: String, val name: String, val bound: Boolean = false) : WorkspaceDialogs
    data object NewSkiffSession : WorkspaceDialogs
    /** [name] is the zmx name, [title] the one shown. */
    data class SkiffSession(val name: String, val title: String, val space: String?) : WorkspaceDialogs
}

/** What the dialogs can ask the workspace to do. Implemented by the workspace route. */
interface WorkspaceDialogActions {
    fun dismiss()
    fun show(dialog: WorkspaceDialogs)
    fun createWorkspace(name: String, root: String)
    fun renameWorkspace(workspaceId: String, name: String)
    fun renameTerminal(tabId: String, name: String?)
    fun deleteWorkspace(workspaceId: String)
    fun newSkiffSession(title: String)
    fun updateSkiffSession(name: String, title: String, space: String?)
    fun endSkiffSession(name: String)
}

/** Name, root, and confirmation dialogs for the workspace. A blank name is rejected for workspaces. */
@Composable
fun WorkspaceDialogHost(dialog: WorkspaceDialogs?, actions: WorkspaceDialogActions) {
    when (dialog) {
        is WorkspaceDialogs.DeleteWorkspace -> DeleteWorkspaceDialog(dialog, actions)
        is WorkspaceDialogs.NewWorkspace -> WorkspaceFieldsDialog(
            title = "New workspace",
            initialName = "",
            initialRoot = dialog.defaultRoot,
            showRoot = true,
            onDismiss = actions::dismiss,
            onConfirm = { name, root -> actions.createWorkspace(name, root) },
        )
        is WorkspaceDialogs.RenameWorkspace -> WorkspaceFieldsDialog(
            title = "Rename workspace",
            initialName = dialog.name,
            initialRoot = "",
            showRoot = false,
            onDismiss = actions::dismiss,
            onConfirm = { name, _ -> actions.renameWorkspace(dialog.workspaceId, name) },
        )
        is WorkspaceDialogs.RenameTerminal -> TerminalNameDialog(
            initialName = dialog.name.orEmpty(),
            onDismiss = actions::dismiss,
            onConfirm = { name -> actions.renameTerminal(dialog.tabId, name) },
        )
        WorkspaceDialogs.NewSkiffSession -> NewSkiffSessionDialog(
            onDismiss = actions::dismiss,
            onConfirm = actions::newSkiffSession,
        )
        is WorkspaceDialogs.SkiffSession -> SkiffSessionDialog(
            dialog = dialog,
            onDismiss = actions::dismiss,
            onSave = { title, space -> actions.updateSkiffSession(dialog.name, title, space) },
            onEnd = { actions.endSkiffSession(dialog.name) },
        )
        null -> Unit
    }
}

@Composable
private fun DeleteWorkspaceDialog(dialog: WorkspaceDialogs.DeleteWorkspace, actions: WorkspaceDialogActions) {
    val text = if (dialog.bound) {
        "\"${dialog.name}\" is removed from this app. Its herdr terminals keep running on the server."
    } else {
        "\"${dialog.name}\" and its terminals will be closed, including remote sessions."
    }
    AlertDialog(
        onDismissRequest = actions::dismiss,
        title = { Text("Delete workspace?") },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { actions.deleteWorkspace(dialog.workspaceId) }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = actions::dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WorkspaceFieldsDialog(
    title: String,
    initialName: String,
    initialRoot: String,
    showRoot: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, root: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var root by remember { mutableStateOf(initialRoot) }
    val canConfirm = name.isNotBlank() && (!showRoot || root.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                if (showRoot) {
                    OutlinedTextField(
                        value = root,
                        onValueChange = { root = it },
                        label = { Text("Root directory") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), root.trim()) },
                enabled = canConfirm,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TerminalNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename terminal") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Leave blank to use the default") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifEmpty { null }) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A new skiff session: just its name, which may be anything. */
@Composable
private fun NewSkiffSessionDialog(onDismiss: () -> Unit, onConfirm: (title: String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title.trim()) }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A skiff session's name and space, and ending it. Closing its tab only disconnects; this ends it on the server. */
@Composable
private fun SkiffSessionDialog(
    dialog: WorkspaceDialogs.SkiffSession,
    onDismiss: () -> Unit,
    onSave: (title: String, space: String?) -> Unit,
    onEnd: () -> Unit,
) {
    var title by remember { mutableStateOf(dialog.title) }
    var space by remember { mutableStateOf(dialog.space.orEmpty()) }
    var confirmingEnd by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmingEnd) "End ${dialog.title}?" else "Session") },
        text = {
            if (confirmingEnd) {
                Text("Whatever runs in it on the server stops.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = space,
                        onValueChange = { space = it },
                        label = { Text("Space") },
                        placeholder = { Text("None") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            if (confirmingEnd) {
                TextButton(onClick = onEnd) { Text("End session") }
            } else {
                TextButton(enabled = title.isNotBlank(), onClick = { onSave(title.trim(), space.trim().ifEmpty { null }) }) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            if (confirmingEnd) {
                TextButton(onClick = { confirmingEnd = false }) { Text("Cancel") }
            } else {
                TextButton(onClick = { confirmingEnd = true }) { Text("End session…") }
            }
        },
    )
}
