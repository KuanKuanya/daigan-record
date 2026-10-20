package com.kuankuan.daigan.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kuankuan.daigan.data.model.GameAccount
import com.kuankuan.daigan.data.model.LoginMethod
import java.time.Instant

/**
 * A game account owned by one [OwnerEntity].
 *
 * The foreign key uses RESTRICT: owner rows can never be deleted while accounts
 * still reference them. Records are archived instead of deleted.
 *
 * [password] is null unless [loginMethod] is [LoginMethod.PASSWORD]. Verification
 * codes are never stored.
 */
@Entity(
    tableName = "game_accounts",
    foreignKeys = [
        ForeignKey(
            entity = OwnerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ownerId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["ownerId"]),
        Index(value = ["ownerId", "archivedAt"]),
    ],
)
data class GameAccountEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val gameName: String,
    val server: String,
    val uid: String,
    val loginAccount: String,
    val loginMethod: LoginMethod,
    val password: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant?,
    /**
     * Set only when the account was archived as part of an owner archive batch, and
     * cleared when the account is restored. Accounts archived on their own always
     * keep this null, so timestamp equality is never used as an identity.
     */
    val archiveBatchId: String? = null,
    /**
     * Optional template preselected when a new order is started for this account.
     *
     * Deliberately **not** a foreign key: deleting a template must never require
     * touching or cascading through business rows. The template repository clears this
     * value in the same transaction when its template is deleted, and a dangling value
     * is treated as "no default" on read.
     */
    val defaultTemplateId: String? = null,
) {
    fun toModel(): GameAccount = GameAccount(
        id = id,
        ownerId = ownerId,
        gameName = gameName,
        server = server,
        uid = uid,
        loginAccount = loginAccount,
        loginMethod = loginMethod,
        password = password,
        createdAt = createdAt,
        archived = archivedAt != null,
        archivedWithOwner = archiveBatchId != null,
        defaultTemplateId = defaultTemplateId,
    )
}
