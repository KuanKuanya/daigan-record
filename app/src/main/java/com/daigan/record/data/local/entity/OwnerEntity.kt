package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daigan.record.data.model.Owner
import java.time.Instant

/**
 * Stored owner record ("老板").
 *
 * [number] is unique at the SQLite level, so a concurrent allocation can never
 * produce two owners with the same human readable number.
 */
@Entity(
    tableName = "owners",
    indices = [
        Index(value = ["number"], unique = true),
        Index(value = ["archivedAt"]),
    ],
)
data class OwnerEntity(
    @PrimaryKey val id: String,
    val number: Int,
    val nickname: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant?,
) {
    fun toModel(): Owner = Owner(
        id = id,
        number = number,
        nickname = nickname,
        createdAt = createdAt,
        archived = archivedAt != null,
    )
}

/**
 * Single-row counter used to allocate owner numbers inside a database transaction.
 * Keeping the counter in SQLite (instead of memory) keeps numbers unique across
 * process restarts and prevents reuse of numbers from archived owners.
 */
@Entity(tableName = "owner_number_sequence")
data class OwnerNumberSequenceEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val nextNumber: Int = 1,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
