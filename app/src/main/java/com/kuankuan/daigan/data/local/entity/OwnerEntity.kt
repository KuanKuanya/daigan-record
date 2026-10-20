package com.kuankuan.daigan.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kuankuan.daigan.data.model.Owner
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
    /**
     * Identifier of the archive batch that archived this owner together with its
     * accounts. Null while the owner is active, and null for rows archived by a
     * legacy version that could not record provenance.
     */
    val archiveBatchId: String? = null,
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
