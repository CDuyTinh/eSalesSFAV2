package com.tinhcd.myesalessfa.data.local

import androidx.room.AutoMigration
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
 * The local store is deliberately small. This app is online-first, so Room holds
 * only what cannot be fetched at the moment it is needed: the settings and
 * workflow definition a visit has to be judged against with no signal, the
 * product catalogue an order is built from, and the outbox.
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
        const val TYPE_ORDER = "order"
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

// -----------------------------------------------------------------------------
// Product catalogue
//
// Cached because a rep takes orders standing in a shop, and re-fetching a few
// hundred products and prices per visit over a 2G connection is not viable.
// Prices are stored as the effective-dated rules the server holds rather than
// as one resolved number per product: the same device serves customers in
// different classes on the same route.
// -----------------------------------------------------------------------------

@Entity(tableName = "product")
data class ProductEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val categoryName: String?,
    /** Category display order, so the catalogue lists the way head office sorted it. */
    val categorySort: Int,
    val baseUom: String,
    val vatBasisPoints: Int,
)

@Entity(tableName = "sale_unit", primaryKeys = ["productId", "uomCode"])
data class SaleUnitEntity(
    val productId: String,
    val uomCode: String,
    val uomName: String,
    val conversionRate: Int,
    val isDefaultSale: Boolean,
    val sortOrder: Int,
)

@Entity(tableName = "price_rule")
data class PriceRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    val uomCode: String,
    /** Null is the list price, applying to any class without its own row. */
    val classId: String?,
    val price: Long,
    /** ISO yyyy-MM-dd. Kept as text so Room needs no date converter. */
    val fromDate: String,
    val toDate: String,
)

@Dao
interface CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProducts(rows: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSaleUnits(rows: List<SaleUnitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPriceRules(rows: List<PriceRuleEntity>)

    @Query("DELETE FROM product")
    suspend fun clearProducts()

    @Query("DELETE FROM sale_unit")
    suspend fun clearSaleUnits()

    @Query("DELETE FROM price_rule")
    suspend fun clearPriceRules()

    @Query("SELECT * FROM product ORDER BY categorySort ASC, name ASC")
    suspend fun products(): List<ProductEntity>

    @Query("SELECT * FROM sale_unit")
    suspend fun saleUnits(): List<SaleUnitEntity>

    @Query("SELECT * FROM price_rule")
    suspend fun priceRules(): List<PriceRuleEntity>

    @Query("SELECT COUNT(*) FROM product")
    suspend fun productCount(): Int
}

@Database(
    entities = [
        OutboxEntity::class,
        SettingEntity::class,
        ReasonEntity::class,
        SalesStepEntity::class,
        TranslationEntity::class,
        ProductEntity::class,
        SaleUnitEntity::class,
        PriceRuleEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // v3 only adds the catalogue tables, so Room can derive the migration. It is
    // spelled out rather than left to the destructive fallback because the outbox
    // now carries orders: dropping it would throw away a sale the rep has already
    // agreed with the customer and cannot reconstruct.
    autoMigrations = [AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun configDao(): ConfigDao
    abstract fun catalogDao(): CatalogDao
}
