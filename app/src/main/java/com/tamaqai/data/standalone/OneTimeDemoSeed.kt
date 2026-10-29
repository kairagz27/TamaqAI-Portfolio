package com.tamaqai.data.standalone

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate

/** The anchor is committed before data writes, so interrupted initialization retries the same dates. */
interface DemoSeedReceipt {
    fun completed(): Boolean
    fun anchor(): LocalDate?
    fun begin(date: LocalDate)
    fun complete()
    fun clear()
    fun resetPending(): Boolean
    fun requestReset()
}

/** Seed callbacks must insert missing stable IDs, never replace existing user edits. */
class OneTimeDemoSeed(private val clock: Clock, private val receipt: DemoSeedReceipt) {
    private val lock = Mutex()
    suspend fun initialize(seed: suspend (LocalDate) -> Unit): Boolean = lock.withLock {
        if (receipt.completed()) return@withLock false
        val anchor = receipt.anchor() ?: LocalDate.now(clock).also(receipt::begin)
        seed(anchor)
        receipt.complete()
        true
    }
    suspend fun reset(clear: suspend () -> Unit, seed: suspend (LocalDate) -> Unit) = lock.withLock {
        // A pending reset is durable before clearing anything. Recovery repeats an idempotent clear.
        receipt.requestReset()
        clear()
        receipt.clear()
        val date = LocalDate.now(clock)
        receipt.begin(date)
        seed(date)
        receipt.complete()
    }

    suspend fun recoverReset(clear: suspend () -> Unit, seed: suspend (LocalDate) -> Unit) {
        if (receipt.resetPending()) reset(clear, seed)
    }
}
