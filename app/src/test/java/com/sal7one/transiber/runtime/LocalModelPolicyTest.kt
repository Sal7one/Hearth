package com.sal7one.transiber.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelPolicyTest {
    @Test fun phoneAllowsEachIndependentStage() {
        listOf("speech recognition", "translation", "speech voices", "model import").forEach {
            LocalModelPolicy.requireAllowed(false, it)
        }
    }

    @Test fun tvRejectsEveryStageWithAnActionableError() {
        listOf("speech recognition", "translation", "speech voices", "model import").forEach { stage ->
            val failure = runCatching { LocalModelPolicy.requireAllowed(true, stage) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains(stage))
            assertTrue(failure?.message.orEmpty().contains("cloud"))
        }
    }
}
