package com.tamaqai.ui

import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runners.model.TestTimedOutException
import java.util.concurrent.CountDownLatch

/** A method timeout does not protect a blocking Activity/Compose Rule setup. */
class RuleSetupTimeoutTest {
    @Test fun outerTimeoutAlsoBoundsSetupBeforeTestBody() {
        var bodyEntered=false
        val blockedSetup=TestRule { _,_ -> object:Statement() {
            override fun evaluate() {CountDownLatch(1).await()}
        } }
        val body=object:Statement() {override fun evaluate() {bodyEntered=true}}
        val bounded=RuleChain.outerRule(Timeout.millis(100)).around(blockedSetup)
            .apply(body,Description.createTestDescription(javaClass,"blockedSetup"))
        val error=runCatching {bounded.evaluate()}.exceptionOrNull()
        assertTrue(error is TestTimedOutException)
        assertFalse(bodyEntered)
    }
}
