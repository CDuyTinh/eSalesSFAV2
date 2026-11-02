package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.AppUpdateNeed
import com.tinhcd.myesalessfa.domain.model.AppUpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdatePolicyTest {

    private fun policy(latest: Int?, min: Int?) = AppUpdatePolicy(latest, min, url = null, note = null)

    @Test
    fun `a build at the latest needs nothing`() {
        assertEquals(AppUpdateNeed.NONE, policy(latest = 3, min = 2).check(3))
    }

    @Test
    fun `a build ahead of the latest needs nothing`() {
        // A test build installed before the setting is raised.
        assertEquals(AppUpdateNeed.NONE, policy(latest = 3, min = 2).check(4))
    }

    @Test
    fun `a build behind the latest is offered the update`() {
        assertEquals(AppUpdateNeed.OPTIONAL, policy(latest = 3, min = 2).check(2))
    }

    @Test
    fun `a build behind the minimum must update`() {
        assertEquals(AppUpdateNeed.REQUIRED, policy(latest = 3, min = 2).check(1))
    }

    @Test
    fun `the minimum alone is enough to require it`() {
        assertEquals(AppUpdateNeed.REQUIRED, policy(latest = null, min = 2).check(1))
    }

    @Test
    fun `nothing configured needs nothing`() {
        assertEquals(AppUpdateNeed.NONE, policy(latest = null, min = null).check(1))
    }
}
