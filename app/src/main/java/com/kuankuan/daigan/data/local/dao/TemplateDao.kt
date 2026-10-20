package com.kuankuan.daigan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kuankuan.daigan.data.local.entity.GameTemplateEntity
import com.kuankuan.daigan.data.local.entity.TemplateCategoryEntity
import com.kuankuan.daigan.data.local.entity.TemplateSeedStateEntity
import com.kuankuan.daigan.data.local.entity.TemplateTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {

    @Query(
        "SELECT * FROM game_templates ORDER BY sortOrder ASC, " +
            "gameName COLLATE NOCASE ASC, templateVersion ASC, createdAt ASC",
    )
    fun observeTemplates(): Flow<List<GameTemplateEntity>>

    @Query("SELECT * FROM template_categories ORDER BY sortOrder ASC, id ASC")
    fun observeCategories(): Flow<List<TemplateCategoryEntity>>

    @Query("SELECT * FROM template_tasks ORDER BY sortOrder ASC, id ASC")
    fun observeTasks(): Flow<List<TemplateTaskEntity>>

    @Query("SELECT * FROM game_templates WHERE id = :templateId")
    suspend fun findTemplate(templateId: String): GameTemplateEntity?

    @Query("SELECT * FROM game_templates ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    suspend fun findAllTemplates(): List<GameTemplateEntity>

    @Query("SELECT MAX(sortOrder) FROM game_templates")
    suspend fun maxSortOrder(): Int?

    @Query("SELECT * FROM game_templates WHERE builtInKey = :key")
    suspend fun findTemplateByBuiltInKey(key: String): GameTemplateEntity?

    @Query(
        "SELECT * FROM template_categories WHERE templateId = :templateId " +
            "ORDER BY sortOrder ASC, id ASC",
    )
    suspend fun findCategories(templateId: String): List<TemplateCategoryEntity>

    @Query(
        "SELECT * FROM template_tasks WHERE templateId = :templateId " +
            "ORDER BY sortOrder ASC, id ASC",
    )
    suspend fun findTasks(templateId: String): List<TemplateTaskEntity>

    /** Typed full reads used by backup validation (they run every converter). */
    @Query("SELECT * FROM template_categories")
    suspend fun findAllCategories(): List<TemplateCategoryEntity>

    @Query("SELECT * FROM template_tasks")
    suspend fun findAllTemplateTasks(): List<TemplateTaskEntity>

    @Query("SELECT * FROM template_seed_state")
    suspend fun findAllSeedStates(): List<TemplateSeedStateEntity>

    @Query("SELECT id FROM template_categories")
    suspend fun allCategoryIds(): List<String>

    @Query("SELECT id FROM template_tasks")
    suspend fun allTaskIds(): List<String>

    @Query("SELECT COUNT(*) FROM template_seed_state WHERE seedKey = :key")
    suspend fun seedAppliedCount(key: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTemplate(template: GameTemplateEntity)

    @Update
    suspend fun updateTemplate(template: GameTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategories(categories: List<TemplateCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTasks(tasks: List<TemplateTaskEntity>)

    @Query("DELETE FROM template_tasks WHERE templateId = :templateId")
    suspend fun deleteTasks(templateId: String)

    @Query("DELETE FROM template_categories WHERE templateId = :templateId")
    suspend fun deleteCategories(templateId: String)

    @Query("DELETE FROM game_templates WHERE id = :templateId")
    suspend fun deleteTemplate(templateId: String)

    @Query("SELECT * FROM game_templates WHERE builtInKey IN (:keys)")
    suspend fun findTemplatesByBuiltInKeys(keys: List<String>): List<GameTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSeedState(state: TemplateSeedStateEntity)

    /** A deleted template stops being the default for accounts that pointed at it. */
    @Query("UPDATE game_accounts SET defaultTemplateId = NULL WHERE defaultTemplateId = :templateId")
    suspend fun clearAccountDefault(templateId: String)
}
