package com.kuankuan.daigan.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kuankuan.daigan.data.model.Attachment
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun formatGameDate(date: LocalDate): String =
    DateTimeFormatter.ofPattern("yyyy.MM.dd").format(date)

fun formatShortDate(date: LocalDate): String =
    DateTimeFormatter.ofPattern("M 月 d 日", Locale.SIMPLIFIED_CHINESE).format(date)

fun formatInstant(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
        .withZone(zoneId)
        .format(instant)

@Composable
fun CompletionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The whole row is the tap target: the title, subtitle and checkbox are one
            // control. This keeps the checkbox a checkbox for accessibility while making
            // a 48dp-minimum target out of the label, and it means a tap cannot land on a
            // row without carrying that row's occurrence identity.
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            // The row handles the gesture; a second click handler here would toggle twice.
            onCheckedChange = null,
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Screenshot strip used by short-task progress and hosting check-ins.
 *
 * The picker copies bytes immediately; no persistable external URI permission is kept.
 * A canceled picker simply leaves the existing attachments untouched.
 */
@Composable
fun ScreenshotStrip(
    attachments: List<Attachment>,
    fileFor: (Attachment) -> File,
    enabled: Boolean,
    onAdd: (Uri) -> Unit,
    onReplace: (Attachment, Uri) -> Unit,
    onDelete: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    emptyLabel: String = "暂无截图",
) {
    var preview by remember { mutableStateOf<File?>(null) }
    var pendingReplaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Attachment?>(null) }
    var pickerError by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        val replaceId = pendingReplaceId
        pendingReplaceId = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (replaceId == null) {
            onAdd(uri)
        } else {
            attachments.firstOrNull { it.id == replaceId }?.let { onReplace(it, uri) }
        }
    }

    fun launchPicker(replaceId: String?) {
        pendingReplaceId = replaceId
        runCatching {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure { pickerError = "无法打开系统图片选择器" }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "截图 · ${attachments.size} 张",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (enabled) {
                TextButton(onClick = { launchPicker(null) }) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                    Text(text = "添加截图", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        if (attachments.isEmpty()) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment ->
                    val file = remember(attachment.id) { fileFor(attachment) }
                    AttachmentCard(
                        file = file,
                        enabled = enabled,
                        onView = { preview = file },
                        onReplace = { launchPicker(attachment.id) },
                        onDelete = { confirmDelete = attachment },
                    )
                }
            }
        }
        if (pickerError != null) {
            Text(
                text = checkNotNull(pickerError),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    preview?.let { file ->
        ImagePreviewDialog(file = file, onDismiss = { preview = null })
    }

    confirmDelete?.let { attachment ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除这张截图？") },
            text = { Text("只删除这张图片及其数据库记录，任务完成记录仍然保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        onDelete(attachment)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AttachmentCard(
    file: File,
    enabled: Boolean,
    onView: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap by rememberAttachmentBitmap(file)
    Column(
        modifier = Modifier
            .width(150.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                TextButton(onClick = onView, modifier = Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = "查看截图 ${file.name}",
                        modifier = Modifier.size(140.dp, 80.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                Text(
                    text = "无法预览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (enabled) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onReplace) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = "替换截图",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除截图",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ImagePreviewDialog(file: File, onDismiss: () -> Unit) {
    val bitmap by rememberAttachmentBitmap(file)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name) },
        text = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = "截图预览",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("图片文件不可用。")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun rememberAttachmentBitmap(file: File) = produceState<ImageBitmap?>(
    initialValue = null,
    key1 = file.path,
    key2 = file.lastModified(),
) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        }.getOrNull()
    }
}
