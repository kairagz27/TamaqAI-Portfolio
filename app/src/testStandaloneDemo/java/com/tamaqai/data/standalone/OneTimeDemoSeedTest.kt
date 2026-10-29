package com.tamaqai.data.standalone

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class OneTimeDemoSeedTest {
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }
    private class Receipt : DemoSeedReceipt {
        var done = false
        var date: LocalDate? = null
        var pending = false
        override fun completed() = done
        override fun anchor() = date
        override fun begin(date: LocalDate) { this.date = date }
        override fun complete() { done = true; pending = false }
        override fun clear() { date = null; done = false }
        override fun resetPending() = pending
        override fun requestReset() { pending = true }
    }
    private val day = Instant.parse("2026-10-23T12:00:00Z")

    @Test fun nextDayAndNewInstanceDoNotReseedOrOverwriteEdits() = runTest {
        val clock = MovingClock(day)
        val receipt = Receipt()
        var saved = ""
        OneTimeDemoSeed(clock, receipt).initialize { saved = "seed-$it" }
        saved = "user edit"
        clock.now = day.plusSeconds(86_400)
        val seeded = OneTimeDemoSeed(clock, receipt).initialize { saved = "unexpected" }
        assertFalse(seeded)
        assertEquals("user edit", saved)
        assertEquals(LocalDate.parse("2026-10-23"), receipt.date)
    }

    @Test fun simultaneousInitializationHasOneSeed() = runTest {
        val engine = OneTimeDemoSeed(Clock.fixed(day, ZoneOffset.UTC), Receipt())
        var writes = 0
        val first = async { engine.initialize { yield(); writes++ } }
        val second = async { engine.initialize { yield(); writes++ } }
        assertEquals(setOf(true, false), setOf(first.await(), second.await()))
        assertEquals(1, writes)
    }

    @Test fun interruptedInitializationKeepsOriginalAnchorAcrossDateChange() = runTest {
        val clock = MovingClock(day)
        val receipt = Receipt()
        val rows = mutableMapOf<String, String>()
        try {
            OneTimeDemoSeed(clock, receipt).initialize { date ->
                rows.putIfAbsent("meal", date.toString())
                error("interrupted")
            }
            fail("Expected seed failure")
        } catch (_: IllegalStateException) { }
        rows["meal"] = "user edit"
        clock.now = day.plusSeconds(86_400)
        OneTimeDemoSeed(clock, receipt).initialize { date -> rows.putIfAbsent("meal", date.toString()) }
        assertEquals("user edit", rows["meal"])
        assertEquals(LocalDate.parse("2026-10-23"), receipt.date)
        assertTrue(receipt.done)
    }

    @Test fun resetIsExplicitAndUsesCurrentDate() = runTest {
        val receipt = Receipt()
        val clock = MovingClock(day)
        val engine = OneTimeDemoSeed(clock, receipt)
        val dates = mutableListOf<LocalDate>()
        engine.initialize { dates += it }
        clock.now = day.plusSeconds(86_400)
        var clears = 0
        engine.reset({ clears++; dates.clear() }, { dates += it })
        assertEquals(1, clears)
        assertEquals(listOf(LocalDate.parse("2026-10-24")), dates)
        assertTrue(receipt.done)
        assertFalse(receipt.pending)
    }

    @Test fun interruptedResetIsDurableAndRecoveredBeforeOrdinaryInitialization() = runTest {
        val receipt = Receipt()
        val clock = Clock.fixed(day, ZoneOffset.UTC)
        val engine = OneTimeDemoSeed(clock, receipt)
        engine.initialize { }
        try {
            engine.reset({ error("disk failure during clear") }, { fail("must not seed yet") })
            fail("Expected reset failure")
        } catch (_: IllegalStateException) { }
        assertTrue(receipt.pending)
        var cleared = false
        var seeded = 0
        val recovered = OneTimeDemoSeed(clock, receipt)
        recovered.recoverReset({ cleared = true }, { assertTrue(cleared); seeded++ })
        assertFalse(recovered.initialize { fail("No duplicate seed after reset recovery") })
        assertEquals(1, seeded)
        assertFalse(receipt.pending)
    }

    @Test fun plannedMealIdentityIsStableAndIndependentOfPortionOrDate() {
        val id = LocalPlanStore.planMealUid("plan", "meal")
        assertEquals(id, LocalPlanStore.planMealUid("plan", "meal"))
        assertNotEquals(id, LocalPlanStore.planMealUid("plan", "other"))
    }
}
