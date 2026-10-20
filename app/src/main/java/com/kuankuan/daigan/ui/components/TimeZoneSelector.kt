package com.kuankuan.daigan.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedTimeZone(val label: String, val id: String)

internal class TimeZoneOptions(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("time-zone-options", Context.MODE_PRIVATE)

    fun load(): List<SavedTimeZone> {
        val raw = preferences.getString("zones", null)
            ?: return listOf(SavedTimeZone("中国时间", "Asia/Shanghai"), SavedTimeZone("美国东部时间", "America/New_York"))
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                SavedTimeZone(entry.getString("label"), ZoneId.of(entry.getString("id")).id)
            }
        }.getOrDefault(emptyList())
    }

    fun save(zones: List<SavedTimeZone>) {
        val array = JSONArray()
        zones.forEach { array.put(JSONObject().put("label", it.label).put("id", it.id)) }
        preferences.edit().putString("zones", array.toString()).apply()
    }
}

@Composable
fun TimeZoneSelector(value: String, onValue: (String) -> Unit, error: String? = null) {
    val context = LocalContext.current
    val options = remember(context) { TimeZoneOptions(context) }
    var zones by remember { mutableStateOf(options.load()) }
    var showPicker by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<String?>(null) }
    val allZones = remember { ZoneId.getAvailableZoneIds().sorted() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { zones = options.load(); showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(zones.firstOrNull { it.id == value }?.label ?: value.ifBlank { "选择时区" })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("时区") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(zones, key = { it.id }) { zone ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f).clickable { onValue(zone.id); showPicker = false }.padding(vertical = 12.dp)) {
                                Text(zone.label, style = MaterialTheme.typography.bodyMedium)
                                Text(zone.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { zones = zones.filterNot { it.id == zone.id }; options.save(zones) }) { Text("删除") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                label = ""; search = ""; chosen = null; validation = null; showPicker = false; showAdd = true
            }) { Text("添加时区") } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("关闭") } },
        )
    }
    if (showAdd) {
        val matches = remember(search) { allZones.filter { it.contains(search.trim(), ignoreCase = true) } }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加时区") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(value = search, onValueChange = { search = it; chosen = null }, label = { Text("搜索时区") }, singleLine = true)
                    LazyColumn(modifier = Modifier.height(180.dp)) {
                        items(matches, key = { it }) { id ->
                            TextButton(onClick = { chosen = id; search = id; validation = null }) { Text(id) }
                        }
                    }
                    validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = {
                val id = chosen ?: search.trim().takeIf { it in allZones }
                when {
                    id == null -> validation = "请选择有效时区"
                    zones.any { it.id == id } -> validation = "该时区已存在"
                    else -> {
                        zones = zones + SavedTimeZone(label.trim().ifBlank { id }, id)
                        options.save(zones); onValue(id); showAdd = false
                    }
                }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
        )
    }
}
