package com.tinhcd.myesalessfa.data.local

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

/**
 * The local store holds one kind of thing: reference data that is read on nearly
 * every screen and changes rarely — settings, the workflow definition, labels,
 * reason codes, questionnaires, and the product catalogue an order is priced from.
 *
 * Nothing transactional lives here. The app is online-only: a route, a visit, an
 * order and a stock count are all read from and written to the server as they
 * happen, and a failed request is reported rather than queued.
 */

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

/**
 * The shell's tab list. Flat, with [parentCode] carrying the nesting, matching
 * both the table it came from and the shape the shell renders.
 */
@Entity(tableName = "menu_item")
data class MenuItemEntity(
    @PrimaryKey val code: String,
    val parentCode: String?,
    val titleKey: String,
    val sortOrder: Int,
)

@Entity(tableName = "translation")
data class TranslationEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * A questionnaire, stored as the JSON the server sent.
 *
 * Deliberately not four relational tables. The definition is only ever read whole —
 * the survey screen wants the entire tree or nothing — so normalising it on the device
 * would buy a join and cost the reassembly. Keyed by form id because that is how a
 * workflow step finds its own questionnaire.
 */
@Entity(tableName = "survey_definition")
data class SurveyDefinitionEntity(
    @PrimaryKey val formId: String,
    val json: String,
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMenu(rows: List<MenuItemEntity>)

    /** Replaced wholesale, so a tab head office retired stops being rendered. */
    @Query("DELETE FROM menu_item")
    suspend fun clearMenu()

    @Query("SELECT * FROM menu_item ORDER BY sortOrder ASC")
    suspend fun menu(): List<MenuItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSurveyDefinitions(rows: List<SurveyDefinitionEntity>)

    @Query("DELETE FROM survey_definition")
    suspend fun clearSurveyDefinitions()

    @Query("SELECT json FROM survey_definition WHERE formId = :formId")
    suspend fun surveyDefinition(formId: String): String?
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

/**
 * Must-stock lists, cached with the rest of the catalogue. Which ones apply to an
 * outlet depends on its channel and shop type, so the resolution happens on the
 * device — that is what lets the stock screen mark required SKUs with no signal.
 */
@Entity(tableName = "msl")
data class MslEntity(
    @PrimaryKey val id: String,
    val code: String,
    /** Null means the list applies to any channel. */
    val channelId: String?,
    val shopTypeId: String?,
    /** ISO yyyy-MM-dd, as for price rules. */
    val fromDate: String,
    val toDate: String,
)

@Entity(tableName = "msl_item", primaryKeys = ["mslId", "productId"])
data class MslItemEntity(
    val mslId: String,
    val productId: String,
    /** Par level in base units. */
    val minBaseQty: Int,
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMsl(rows: List<MslEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMslItems(rows: List<MslItemEntity>)

    @Query("DELETE FROM msl")
    suspend fun clearMsl()

    @Query("DELETE FROM msl_item")
    suspend fun clearMslItems()

    @Query("SELECT * FROM msl")
    suspend fun msl(): List<MslEntity>

    @Query("SELECT * FROM msl_item")
    suspend fun mslItems(): List<MslItemEntity>
}

/**
 * Drops the two tables that existed to let the app work offline: the outbox of
 * unsent writes, and the cached copy of a day's route.
 *
 * Both are gone by decision rather than by accident. The app is online-only, so a
 * write either reaches the server while the rep is standing there or is reported as
 * failed, and a route is fetched fresh because it changes through the day.
 */
@DeleteTable.Entries(
    DeleteTable(tableName = "outbox"),
    DeleteTable(tableName = "route_cache"),
)
class DropOfflineTables : AutoMigrationSpec

@Database(
    entities = [
        SettingEntity::class,
        ReasonEntity::class,
        SalesStepEntity::class,
        TranslationEntity::class,
        ProductEntity::class,
        SaleUnitEntity::class,
        PriceRuleEntity::class,
        MslEntity::class,
        MslItemEntity::class,
        SurveyDefinitionEntity::class,
        MenuItemEntity::class,
    ],
    version = 8,
    exportSchema = true,
    // Everything in here is reference data the app re-fetches on launch, so these
    // migrations are a courtesy rather than a safeguard — nothing kept locally is
    // the only copy of anything any more.
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = DropOfflineTables::class),
        AutoMigration(from = 7, to = 8),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao
    abstract fun catalogDao(): CatalogDao
}
