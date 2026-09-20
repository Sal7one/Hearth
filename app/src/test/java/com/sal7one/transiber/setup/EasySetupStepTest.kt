package com.sal7one.transiber.setup

import org.junit.Assert.*
import org.junit.Test

class EasySetupStepTest {
    @Test fun localBackRetracesEveryStep() {
        val done = EasySetupStep.LOCAL.complete()
        assertTrue(done.finished)
        assertTrue(done.local)
        assertEquals(EasySetupStep.LOCAL, done.back())
        assertEquals(EasySetupStep.CHOICE, done.back()!!.back())
        assertNull(done.back()!!.back()!!.back())
    }
    @Test fun cloudBackRetracesEveryStep() {
        val done = EasySetupStep.CLOUD.complete()
        assertTrue(done.finished)
        assertFalse(done.local)
        assertEquals(EasySetupStep.CLOUD, done.back())
        assertEquals(EasySetupStep.CHOICE, done.back()!!.back())
        assertNull(done.back()!!.back()!!.back())
    }
    @Test fun unfinishedAndRepeatedCompletionCannotInventSteps() {
        assertEquals(EasySetupStep.CHOICE, EasySetupStep.CHOICE.complete())
        for (form in listOf(EasySetupStep.LOCAL, EasySetupStep.CLOUD)) {
            assertFalse(form.finished)
            assertEquals(EasySetupStep.CHOICE, form.back())
            assertEquals(form.complete(), form.complete().complete())
        }
    }
    @Test fun savedEnumRestoresBackDestination() {
        for (step in EasySetupStep.entries) {
            val bytes = java.io.ByteArrayOutputStream()
            java.io.ObjectOutputStream(bytes).use { it.writeObject(step) }
            val restored = java.io.ObjectInputStream(bytes.toByteArray().inputStream()).use { it.readObject() } as EasySetupStep
            assertEquals(step, restored)
            assertEquals(step.back(), restored.back())
        }
    }
}
