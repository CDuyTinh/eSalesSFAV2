package com.tinhcd.myesalessfa.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The local store is deliberately small. This app is online-first, so Room
 * holds only two things: the handful of settings a check-in has to be judged
 * against even with no signal, and the outbox.
 */

// -----------------------------------------------------------------------------
// Outbox
// -----------------------------------------------------------------------------

/**
 * A write that must not be lost. A rep standing in a shop with one bar of
 * signal has already done the work; failing the request must not undo it.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val payload: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
) {
    companion object {
        const val TYPE_CHECK_IN = "check_in"
        const val TYPE_CHECK_OUT = "check_out"
        const val TYPE_STEP_RESULT = "step_result"
    }
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC LIMIT :limit")
    suspend fun oldest(limit: Int = 50): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String?)

    @Query("SELECT COUNT(*) FROM outbox")
    fun pendingCount(): Flow<Int>

    /**
     * Lets a read merge in writes that have not landed yet, so a step completed
     * without signal still shows as done.
     */
    @Query("SELECT payload FROM outbox WHERE type = :type")
    suspend fun payloadsOfType(type: String): List<String>
}

// -----------------------------------------------------------------------------
// Config cache
// -----------------------------------------------------------------------------

@Entity(tableName = "app_setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "reason_code")
data class ReasonEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val kind: String,
)

/**
 * The in-call workflow definition, cached so the step list renders instantly
 * and still works in a shop with no signal.
 */
@Entity(tableName = "sales_step")
data class SalesStepEntity(
    @PrimaryKey val formId: String,
    val step: Int,
    val titleKey: String,
    val isRequired: Boolean,
    /** jsonb flattened to "key=value" pairs joined by newlines. */
    val config: String,
)

@Entity(tableName = "translation")
data class TranslationEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(rows: List<SettingEntity>)

    @Query("SELECT value FROM app_setting WHERE key = :key")
    suspend fun setting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReasons(rows: List<ReasonEntity>)

    @Query("DELETE FROM reason_code")
    suspend fun clearReasons()

    @Query("SELECT * FROM reason_code WHERE kind = :kind ORDER BY name")
    suspend fun reasons(kind: String): List<ReasonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(rows: List<SalesStepEntity>)

    @Query("DELETE FROM sales_step")
    suspend fun clearSteps()

    @Query("SELECT * FROM sales_step ORDER BY step ASC")
    suspend fun steps(): List<SalesStepEntity>

    @Query("SELECT * FROM sales_step WHERE formId = :formId")
    suspend fun step(formId: String): SalesStepEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranslations(rows: List<TranslationEntity>)

    @Query("SELECT value FROM translation WHERE key = :key")
    suspend fun translation(key: String): String?
}

@Database(
    entities = [
        OutboxEntity::class,
        SettingEntity::class,
        ReasonEntity::class,
        SalesStepEntity::class,
        TranslationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun configDao(): ConfigDao
}
