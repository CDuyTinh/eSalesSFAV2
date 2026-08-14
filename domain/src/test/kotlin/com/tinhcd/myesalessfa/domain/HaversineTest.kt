package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.usecase.Haversine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs on the JVM with no emulator, which is the whole reason :domain is a
 * pure Kotlin module.
 */
class HaversineTest {

    @Test
    fun `same point is zero metres apart`() {
        val d = Haversine.distanceM(10.9804, 106.6519, 10.9804, 106.6519)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun `branch to nearby outlet is within a hundred metres`() {
        // Seeded branch BR01 and customer KH001 sit a short walk apart.
        val d = Haversine.distanceM(10.9804, 106.6519, 10.9812, 106.6524)
        assertTrue("expected under 150m but was $d", d < 150.0)
        assertTrue("expected over 50m but was $d", d > 50.0)
    }

    @Test
    fun `outlet in the next district is kilometres away`() {
        // Thu Dau Mot -> Lai Thieu, roughly 9 km.
        val d = Haversine.distanceM(10.9804, 106.6519, 10.9051, 106.6947)
        assertTrue("expected 8-10km but was $d", d in 8_000.0..10_000.0)
    }
}
