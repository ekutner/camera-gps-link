package org.kutner.cameragpslink.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.kutner.cameragpslink.R

@Composable
fun BondingErrorDialog(
    cameraName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(context.getString(R.string.error_bonding_title))
        },
        text = {
            Column {
                Text(
                    text = context.getString(R.string.error_bonding_message, cameraName),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = context.getString(R.string.error_bonding_step1),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = context.getString(R.string.error_bonding_step2),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = context.getString(R.string.error_bonding_step3),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = context.getString(R.string.error_bonding_step4),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = context.getString(R.string.error_bonding_step5),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.button_ok))
            }
        }
    )
}