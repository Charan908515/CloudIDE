package com.cloudide.android.ui.screens.project

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConflictDialog(
    message: String,
    onPullThenPush: () -> Unit,
    onForcePush: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync conflict") },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "• Pull, then push: download remote changes first; remote wins for any file " +
                            "you also edited.\n" +
                            "• Force push: overwrite Drive with your local state. Other devices " +
                            "will lose unsynced edits.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onPullThenPush) { Text("Pull, then push") }
        },
        dismissButton = {
            Column(modifier = Modifier) {
                TextButton(onClick = onForcePush) {
                    Text("Force push", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
