package com.kuankuan.daigan.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuankuan.daigan.ui.components.SavedTimeZone
import com.kuankuan.daigan.ui.components.TimeZoneOptions
import com.kuankuan.daigan.ui.components.TimeZoneSelector
import com.kuankuan.daigan.ui.theme.DaiganHandbookTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeZoneSelectorTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val selected = mutableStateOf("Asia/Shanghai")

    @Before fun clear() {
        context.getSharedPreferences("time-zone-options", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() = clear()

    @Test fun onlyChinaAndNewYorkArePresetAndDeletionPersistsWithoutChangingCurrentZone() {
        assertEquals(listOf(SavedTimeZone("中国时间", "Asia/Shanghai"), SavedTimeZone("美国东部时间", "America/New_York")), TimeZoneOptions(context).load())
        compose.setContent { DaiganHandbookTheme { TimeZoneSelector(selected.value, { selected.value = it }) } }
        compose.onNodeWithText("中国时间").performClick()
        compose.onAllNodesWithText("删除")[0].performClick()
        compose.onNodeWithText("关闭").performClick()
        assertEquals("Asia/Shanghai", selected.value)
        assertEquals(listOf("America/New_York"), TimeZoneOptions(context).load().map { it.id })
    }

    @Test fun addCustomZoneSelectsAndPersistsIt() {
        compose.setContent { DaiganHandbookTheme { TimeZoneSelector(selected.value, { selected.value = it }) } }
        compose.onNodeWithText("中国时间").performClick()
        compose.onNodeWithText("添加时区").performClick()
        compose.onNodeWithText("名称").performTextInput("日本时间")
        compose.onNodeWithText("搜索时区").performTextInput("Asia/Tokyo")
        compose.onNodeWithText("保存").performClick()
        assertEquals("Asia/Tokyo", selected.value)
        assertEquals(SavedTimeZone("日本时间", "Asia/Tokyo"), TimeZoneOptions(context).load().last())
        compose.onNodeWithText("日本时间").assertExists()
    }

    @Test fun invalidZoneCannotBeSaved() {
        compose.setContent { DaiganHandbookTheme { TimeZoneSelector(selected.value, { selected.value = it }) } }
        compose.onNodeWithText("中国时间").performClick()
        compose.onNodeWithText("添加时区").performClick()
        compose.onNodeWithText("搜索时区").performTextInput("invalid-zone")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("请选择有效时区").assertExists()
        assertEquals(2, TimeZoneOptions(context).load().size)
        assertEquals("Asia/Shanghai", selected.value)
    }
}
