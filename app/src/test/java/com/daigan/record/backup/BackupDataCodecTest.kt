package com.daigan.record.backup

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.fail
import org.junit.Test

class BackupDataCodecTest {
    @Test fun singletonSettingsWithWrongIdentityAreRejected() {
        val settings = checkNotNull(BackupTableCatalog.table("reminder_settings"))
        val dataset = BackupDataSet(BackupTableCatalog.includedTables.map { table ->
            BackupDataSet.TableRows(table, if (table == settings)
                listOf(listOf(1L, 0L, 10L, 0L, 20L, 0L, 30L, 1000L).map { JsonPrimitive(it) }) else emptyList())
        })
        try { BackupDataCodec.decode(BackupDataCodec.encode(dataset)); fail("Wrong singleton ID accepted") }
        catch (_: BackupException) { }
    }

    @Test fun integersThatWouldTruncateInRoomAreRejected() {
        val settings = checkNotNull(BackupTableCatalog.table("reminder_settings"))
        val dataset = BackupDataSet(BackupTableCatalog.includedTables.map { table ->
            BackupDataSet.TableRows(table, if (table == settings)
                listOf(listOf(0L, 1L, 4294967419L, 0L, 20L, 0L, 30L, 1000L).map { JsonPrimitive(it) }) else emptyList())
        })
        try { BackupDataCodec.decode(BackupDataCodec.encode(dataset)); fail("Truncating Int accepted") }
        catch (_: BackupException) { }
    }

    @Test fun nonBooleanIntegerForReminderSwitchIsRejected() {
        val settings = checkNotNull(BackupTableCatalog.table("reminder_settings"))
        val dataset = BackupDataSet(BackupTableCatalog.includedTables.map { table ->
            BackupDataSet.TableRows(table, if (table == settings)
                listOf(listOf(0L, 2L, 10L, 0L, 20L, 0L, 30L, 1000L).map { JsonPrimitive(it) }) else emptyList())
        })
        try { BackupDataCodec.decode(BackupDataCodec.encode(dataset)); fail("Invalid boolean accepted") }
        catch (_: BackupException) { }
    }
}
