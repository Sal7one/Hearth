package com.sal7one.transiber.models

import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.caption.CaptionEngineChoice
import org.junit.Assert.*
import org.junit.Test

class TranscribePsyArtifactTest {
    @Test fun recentCheckpointHasPublisherPinnedRawGgufAndHonestReleaseDates() {
        val model = SpeechArtifactCatalog.find("transcribepsy-250m-eu-sub-q8")!!
        assertEquals(CaptionEngineChoice.TRANSCRIBEPSY, model.engine)
        assertEquals("2026-10-14", model.modelReleaseDate)
        assertEquals("2026-10-13", model.artifactDate)
        assertEquals(304879584L, model.bytes)
        assertEquals(model.bytes, model.installedBytes)
        assertEquals("92460e0a8e7dc20f2fb126198bdd9eebb9ba2dc7", model.sourceRevision)
        assertTrue(model.url.contains("/resolve/${model.sourceRevision}/"))
        assertEquals("a93ef324d38e410b5e49389db95d06364eddbb890b74a69f311e482d2fda6f4b", model.sha256)
        assertEquals("Apache-2.0", model.license)
        assertNull(model.archiveRoot)
        val publisher = requireNotNull(model.publisher)
        assertEquals(setOf("model"), publisher.roles.keys)
        assertEquals(SpeechProfile.TRANSCRIBEPSY_250M_EU_SUB, publisher.profile)
        assertFalse(model.languages.contains("ar"))
    }
}
