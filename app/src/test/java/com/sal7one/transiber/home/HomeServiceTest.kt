package com.sal7one.transiber.home

import org.junit.Assert.*
import org.junit.Test

class HomeServiceTest {
    @Test fun savedIdentityRestoresTheSameServiceAndUnknownIdsAreSafe() {
        HomeService.entries.forEach { service -> assertEquals(service, HomeService.restore(service.id)) }
        assertEquals(HomeService.CAPTIONS, HomeService.restore(null))
        assertEquals(HomeService.CAPTIONS, HomeService.restore("removed-service"))
        assertEquals(HomeService.entries.size, HomeService.entries.map { it.id }.toSet().size)
    }
    @Test fun usingSettingsDoesNotReplaceTheLastService() {
        for (page in listOf(1, 2, 3, 4, 5, 6, 8, 9, 12, 13, 14)) assertNull(HomeService.forPage(page, false))
        assertEquals(HomeService.CONVERSATION, HomeService.forPage(7, false))
        assertEquals(HomeService.FACE, HomeService.forPage(7, true))
        assertEquals(HomeService.TEXT, HomeService.forPage(11, false))
        assertEquals(HomeService.CAMERA, HomeService.forPage(10, false))
    }
}
