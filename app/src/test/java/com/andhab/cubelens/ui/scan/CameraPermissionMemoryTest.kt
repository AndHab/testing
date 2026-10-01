package com.andhab.cubelens.ui.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission journeys Android allows, as seen through the only signals it gives: the request
 * result and whether a rationale is due before and after.
 */
class CameraPermissionMemoryTest {

    /** A request on a fresh [memory]; returns the memory after it and whether Settings is needed. */
    private fun request(
        memory: CameraPermissionMemory,
        granted: Boolean,
        rationaleBefore: Boolean,
        rationaleAfter: Boolean,
    ): Pair<CameraPermissionMemory, Boolean> {
        val after = memory.afterRequest(granted, rationaleBefore, rationaleAfter)
        return after to after.deniedForGood(granted, rationaleAfter)
    }

    @Test
    fun aFreshInstallAsksInTheApp() {
        assertFalse(CameraPermissionMemory().deniedForGood(granted = false, showRationale = false))
    }

    @Test
    fun dismissingTheDialogOnceKeepsAskingInTheApp() {
        val (memory, settings) = request(CameraPermissionMemory(), granted = false, rationaleBefore = false, rationaleAfter = false)
        assertFalse("the dialog would still show", settings)
        assertFalse(memory.refusedBefore)
    }

    @Test
    fun aFirstRefusalKeepsAskingInTheApp() {
        val (memory, settings) = request(CameraPermissionMemory(), granted = false, rationaleBefore = false, rationaleAfter = true)
        assertFalse(settings)
        assertTrue(memory.refusedBefore)
    }

    @Test
    fun aSecondRefusalNeedsSettings() {
        val (first, _) = request(CameraPermissionMemory(), granted = false, rationaleBefore = false, rationaleAfter = true)
        val (_, settings) = request(first, granted = false, rationaleBefore = true, rationaleAfter = false)
        assertTrue(settings)
    }

    @Test
    fun dismissingAfterARefusalKeepsAskingInTheApp() {
        val (first, _) = request(CameraPermissionMemory(), granted = false, rationaleBefore = false, rationaleAfter = true)
        val (_, settings) = request(first, granted = false, rationaleBefore = true, rationaleAfter = true)
        assertFalse(settings)
    }

    @Test
    fun aRefusalForGoodInAnEarlierSessionGoesStraightToSettings() {
        // Next session: only the remembered refusal is left, and no rationale is due.
        val restored = CameraPermissionMemory(refusedBefore = true)
        val seen = restored.observe(granted = false, showRationale = false)
        assertTrue(seen.deniedForGood(granted = false, showRationale = false))
    }

    @Test
    fun twoSilentRefusalsInARowNeedSettings() {
        // Denied in Settings without the app ever asking: the dialog never shows.
        val (first, firstSettings) = request(CameraPermissionMemory(), granted = false, rationaleBefore = false, rationaleAfter = false)
        assertFalse(firstSettings)
        val (_, secondSettings) = request(first, granted = false, rationaleBefore = false, rationaleAfter = false)
        assertTrue(secondSettings)
    }

    @Test
    fun grantingForgetsEarlierRefusals() {
        val refused = CameraPermissionMemory(refusedBefore = true, silentRefusals = 3)
        val granted = refused.observe(granted = true, showRationale = false)
        assertFalse(granted.refusedBefore)
        // A later one-time permission running out asks in the app again.
        assertFalse(granted.deniedForGood(granted = false, showRationale = false))
        assertFalse(refused.afterRequest(granted = true, rationaleBefore = false, rationaleAfter = false).refusedBefore)
    }

    @Test
    fun grantedIsNeverDenied() {
        assertFalse(CameraPermissionMemory(refusedBefore = true, silentRefusals = 5).deniedForGood(granted = true, showRationale = false))
    }
}
