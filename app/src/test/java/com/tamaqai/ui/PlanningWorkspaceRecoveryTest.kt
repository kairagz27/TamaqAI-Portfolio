package com.tamaqai.ui

import com.tamaqai.ui.screens.PlanningWorkspaceRecovery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningWorkspaceRecoveryTest {
    private fun PlanningWorkspaceRecovery.observe(present: Boolean = false, loading: Boolean = false,
                                                  busy: Boolean = false, error: Boolean = false,
                                                  ownerValid: Boolean = true) =
        shouldRecover(present, loading, busy, error, ownerValid)

    @Test fun resetOfPreviouslyObservedWorkspaceIsRecoveredOnce() {
        val recovery = PlanningWorkspaceRecovery()
        assertFalse(recovery.observe(present = true))
        assertTrue(recovery.observe())
        repeat(10) { assertFalse(recovery.observe()) }
        assertFalse(recovery.observe(present = true))
        // A later independent reset can recover too.
        assertTrue(recovery.observe())
    }

    @Test fun initialMissingRowCanRecoverAfterExistingInitializationOrMutationFinishes() {
        val recovery = PlanningWorkspaceRecovery()
        assertFalse(recovery.observe(loading = true, busy = true))
        assertFalse(recovery.observe(busy = true))
        assertTrue(recovery.observe())
        assertFalse(recovery.observe())
    }

    @Test fun noLoopIfSuccessfulInitializationHasNotCreatedARow() {
        val recovery = PlanningWorkspaceRecovery()
        assertTrue(recovery.observe())
        assertFalse(recovery.observe(loading = true, busy = true))
        repeat(10) { assertFalse(recovery.observe()) }
    }

    @Test fun failedOrChangedOwnerNeverTriggersAutomaticWrites() {
        val recovery = PlanningWorkspaceRecovery()
        repeat(10) {
            assertFalse(recovery.observe(error = true))
            assertFalse(recovery.observe(ownerValid = false))
        }
        // An explicit, successful retry may restore the current owner state.
        assertFalse(recovery.observe(present = true))
        assertTrue(recovery.observe())
    }
}
