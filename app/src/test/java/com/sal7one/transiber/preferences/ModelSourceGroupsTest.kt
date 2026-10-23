package com.sal7one.transiber.preferences

import com.sal7one.transiber.downloads.FileDownload
import org.junit.Assert.*
import org.junit.Test

class ModelSourceGroupsTest {
    @Test fun twelveMissingMagpieComponentsBecomeOneBundleAndNeverInstalledIsNotBroken() {
        val requirements=(1..12).map { ModelBindingCheck("voice-magpie-component-$it",emptyList(),false,installationRecorded=false) }
        val groups=modelSourceGroups(requirements)
        assertEquals(1,groups.size)
        assertEquals("MagpieTTS v2607",groups.single().label)
        assertFalse(groups.single().needsRepair)
        assertEquals(12,groups.single().missing.size)
        val lost=groups.single().components.first().copy(installationRecorded=true)
        assertTrue(modelSourceGroups(requirements.drop(1)+lost).single().needsRepair)
    }
    @Test fun restorationReusesTheCompleteOriginalAndQueuesTokenizerOnlyOnce() {
        val missing=listOf(ModelBindingCheck("voice-magpie-tokenizer-a",emptyList(),false),ModelBindingCheck("voice-magpie-tokenizer-b",emptyList(),false))
        val complete=FileDownload(-1,"Tokenizer",8,0,31,31,phase="Installed",modelId="voice-magpie-tokenizer")
        val failed=complete.copy(id=-2,status=16,phase="Failed")
        val legacy=complete.copy(id=5)
        assertEquals(listOf(-1L),recoverableModelDownloads(missing,listOf(complete,complete,failed,legacy)))
        assertTrue(recoverableModelDownloads(missing.map {it.copy(available=true)},listOf(complete)).isEmpty())
    }
}
