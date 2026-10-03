package dev.shrimpscript.porthole.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRuleTest {
    @Test
    fun aSessionNeverGivesWayToTheFailureCard() {
        assertFalse(failureCardOnRetry(attempt = 9, inSession = true, onList = false, listHasRows = false))
    }

    @Test
    fun theListStaysWhileItHasRows() {
        assertFalse(failureCardOnRetry(attempt = 9, inSession = false, onList = true, listHasRows = true))
        assertTrue(failureCardOnRetry(attempt = 3, inSession = false, onList = true, listHasRows = false))
    }

    @Test
    fun aBlipIsNeverAFailure() {
        assertFalse(failureCardOnRetry(attempt = 2, inSession = false, onList = false, listHasRows = false))
        assertTrue(failureCardOnRetry(attempt = 3, inSession = false, onList = false, listHasRows = false))
    }
}
