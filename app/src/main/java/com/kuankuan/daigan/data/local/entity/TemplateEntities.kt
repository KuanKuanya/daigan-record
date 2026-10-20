package com.kuankuan.daigan.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kuankuan.daigan.data.model.ProgressType
import com.kuankuan.daigan.data.model.RecurrenceType
import com.kuankuan.daigan.data.model.TemplateOrigin
import java.math.BigDecimal
import java.time.Instant

/**
 * One editable game template.
 *
 * Templates are **creation defaults**, not business records: orders copy their tasks and
 * refresh snapshot at creation and keep no foreign key back to this table. Deleting a
 * template therefore cannot delete, rewrite or re-date an order, period or receipt.
 *
 * [builtInKey] is the stable identity of a shipped template. A unique index makes
 * double-seeding impossible; the seed marker below remembers that a built-in was
 * already installed once, so deleting it is a user decision and is not undone on the
 * next launch.
 */
@Entity(
    tableName = "game_templates",
    indices = [
        Index(
            value = ["builtInKey"],
            unique = true,
            name = "index_game_templates_builtInKey",
        ),
    ],
)
data class GameTemplateEntity(
    @PrimaryKey val id: String,
    val gameName: String,
    val templateVersion: String,
    val gameVersionNotes: String?,
    val zoneId: String,
    val dailyRefreshMinutes: Int,
    val weeklyRefreshDay: Int,
    val weeklyRefreshMinutes: Int,
    val origin: TemplateOrigin,
    val builtInKey: String?,
    /** Stable display order; built-ins use 0/1/2 in the requested preparation order. */
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(
    tableName = "template_categories",
    foreignKeys = [
        ForeignKey(
            entity = GameTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["templateId"], name = "index_template_categories_templateId")],
)
data class TemplateCategoryEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    val name: String,
    val sortOrder: Int,
)

/**
 * One task option inside a template category. [recurrence] uses the same enum as order
 * tasks; `ONE_TIME` explicitly means "short order only" and is rejected by a hosting
 * order instead of being reinterpreted as a daily occurrence.
 */
@Entity(
    tableName = "template_tasks",
    foreignKeys = [
        ForeignKey(
            entity = GameTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TemplateCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["templateId"], name = "index_template_tasks_templateId"),
        Index(value = ["categoryId"], name = "index_template_tasks_categoryId"),
    ],
)
data class TemplateTaskEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    val categoryId: String,
    val title: String,
    val progressType: ProgressType,
    val startValue: BigDecimal,
    val targetValue: BigDecimal,
    val unit: String?,
    val recurrence: RecurrenceType,
    val weekdays: String?,
    val everyNDays: Int?,
    val sortOrder: Int,
    val monthDay: Int? = null,
)

/**
 * One row per built-in template key that was already installed. The row survives
 * deleting the template, so initialization cannot resurrect a user's deletion or
 * overwrite edited content on a later launch.
 */
@Entity(tableName = "template_seed_state")
data class TemplateSeedStateEntity(
    @PrimaryKey val seedKey: String,
    val appliedAt: Instant,
)
