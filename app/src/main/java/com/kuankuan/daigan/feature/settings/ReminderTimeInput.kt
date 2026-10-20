package com.kuankuan.daigan.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kuankuan.daigan.notifications.ReminderCategory

/** Parses HH:mm or four numeric digits to minutes after midnight. */
fun parseReminderTimeInput(input: String): Int? {
    val digits = when {
        input.matches(Regex("\\d{4}")) -> input
        input.matches(Regex("\\d{2}:\\d{2}")) -> input.replace(":", "")
        else -> return null
    }
    val hour = digits.substring(0, 2).toInt()
    val minute = digits.substring(2, 4).toInt()
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

@Composable
internal fun ReminderTimeInputDialog(
    category: ReminderCategory,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (ReminderCategory, Int) -> Unit,
) {
    var digits by remember(category, initialMinutes) {
        mutableStateOf("%02d%02d".format(initialMinutes / 60, initialMinutes % 60))
    }
    val minutes = parseReminderTimeInput(digits)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提醒时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(category.label, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = digits,
                    onValueChange = { digits = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.fillMaxWidth().testTag("reminder-time-input"),
                    label = { Text("HH:mm") },
                    placeholder = { Text("0900") },
                    singleLine = true,
                    isError = digits.length == 4 && minutes == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = ReminderTimeVisualTransformation,
                )
                if (digits.length == 4 && minutes == null) {
                    Text("请输入 00:00–23:59", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { minutes?.let { onSave(category, it) } },
                enabled = minutes != null,
                modifier = Modifier.testTag("reminder-time-save"),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("reminder-time-cancel")) {
                Text("取消")
            }
        },
    )
}

private object ReminderTimeVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val display = if (raw.length <= 2) raw else raw.take(2) + ":" + raw.drop(2)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (offset <= 2) offset else (offset + 1).coerceAtMost(display.length)

            override fun transformedToOriginal(offset: Int): Int =
                if (offset <= 2) offset.coerceAtMost(raw.length)
                else (offset - 1).coerceAtMost(raw.length)
        }
        return TransformedText(AnnotatedString(display), mapping)
    }
}
