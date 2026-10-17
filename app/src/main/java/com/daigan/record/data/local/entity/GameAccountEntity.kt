package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.LoginMethod
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
    )
}
