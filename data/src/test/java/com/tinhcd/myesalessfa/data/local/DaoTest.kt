package com.tinhcd.myesalessfa.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The reference-data cache on a real (in-memory) SQLite: what the queries
 * order by and filter on, and that a refresh replaces rather than piles up.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DaoTest {

    private lateinit var db: AppDatabase
    private val config get() = db.configDao()
    private val catalog get() = db.catalogDao()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    @Test
    fun `a setting fetched again replaces the old value`() = runTest {
        config.upsertSettings(listOf(SettingEntity("gps_checkin_radius_m", "100")))
        config.upsertSettings(listOf(SettingEntity("gps_checkin_radius_m", "150")))

        assertEquals("150", config.setting("gps_checkin_radius_m"))
        assertNull(config.setting("never_set"))
    }

    // SQLite compares names byte by byte, so a name starting with a Vietnamese
    // capital (Đ, Ă, Â...) sorts after Z. The names here start with Latin letters
    // to test the filter and the order the query asks for, not that collation.
    @Test
    fun `reasons come back for one kind, by name`() = runTest {
        config.upsertReasons(
            listOf(
                ReasonEntity("r1", "FAR2", "Xe hỏng", "gps_out_of_range"),
                ReasonEntity("r2", "FAR1", "Kẹt xe", "gps_out_of_range"),
                ReasonEntity("r3", "CLOSED", "Nghỉ lễ", "outlet_closed"),
            ),
        )

        assertEquals(listOf("Kẹt xe", "Xe hỏng"), config.reasons("gps_out_of_range").map { it.name })
        assertEquals(listOf("r3"), config.reasons("outlet_closed").map { it.id })
    }

    @Test
    fun `the menu is replaced wholesale and read in its order`() = runTest {
        config.upsertMenu(listOf(MenuItemEntity("OLD", null, "menu_old", 1)))
        config.clearMenu()
        config.upsertMenu(
            listOf(
                MenuItemEntity("REPORT", "OTHER", "menu_report", 3),
                MenuItemEntity("ROUTE", null, "menu_route", 1),
                MenuItemEntity("LEAVE", "OTHER", "menu_leave", 2),
            ),
        )

        assertEquals(listOf("ROUTE", "LEAVE", "REPORT"), config.menu().map { it.code })
    }

    @Test
    fun `steps are read in visit order`() = runTest {
        config.upsertSteps(
            listOf(
                SalesStepEntity("order", 3, "step_order", isRequired = true, config = "{}"),
                SalesStepEntity("stock", 1, "step_stock", isRequired = false, config = "{}"),
            ),
        )

        assertEquals(listOf("stock", "order"), config.steps().map { it.formId })
        assertEquals(true, config.step("order")?.isRequired)
    }

    @Test
    fun `products are listed by category, then name`() = runTest {
        catalog.upsertProducts(
            listOf(
                ProductEntity("p3", "BK001", "Bánh quy", "Bánh kẹo", 2, "PCS", 1000),
                ProductEntity("p2", "NGK002", "Nước suối", "Nước giải khát", 1, "PCS", 1000),
                ProductEntity("p1", "NGK001", "Nước ngọt", "Nước giải khát", 1, "PCS", 1000),
            ),
        )

        assertEquals(listOf("p1", "p2", "p3"), catalog.products().map { it.id })
        assertEquals(3, catalog.productCount())
    }

    @Test
    fun `a sale unit is keyed by product and unit together`() = runTest {
        catalog.upsertSaleUnits(
            listOf(
                SaleUnitEntity("p1", "PCS", "Lon", 1, isDefaultSale = true, sortOrder = 1),
                SaleUnitEntity("p1", "CASE", "Thùng", 24, isDefaultSale = false, sortOrder = 2),
                SaleUnitEntity("p2", "PCS", "Chai", 1, isDefaultSale = true, sortOrder = 1),
            ),
        )
        // The same product and unit again: replaces, does not add a fourth row.
        catalog.upsertSaleUnits(listOf(SaleUnitEntity("p1", "CASE", "Thùng", 20, isDefaultSale = false, sortOrder = 2)))

        val units = catalog.saleUnits()
        assertEquals(3, units.size)
        assertEquals(20, units.single { it.productId == "p1" && it.uomCode == "CASE" }.conversionRate)
    }
}
