package com.sal7one.transiber.models

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test

class ModelBrowseStateTest {
    @Test fun searchAndFilterAreSavedByTheGestureBeforeImmediateNavigation() {
        val saved=mutableMapOf<String,Any>();val search=ModelBrowseState(mutableStateOf("")){ saved["query"]=it }
        val installed=ModelBrowseState(mutableStateOf(false)){ saved["installed"]=it }
        val hardware=ModelBrowseState(mutableStateOf("all")){ saved["speech-hardware"]=it }
        search.value="عربي";installed.value=true;hardware.value="vulkan"
        // No recomposition, lifecycle effect or debounce timer occurs before leaving the page.
        assertEquals("عربي",saved["query"]);assertEquals(true,saved["installed"]);assertEquals("vulkan",saved["speech-hardware"])
    }
    @Test fun untouchedDefaultsAndRepeatedAssignmentsDoNotEnqueueWrites() {
        val writes=mutableListOf<String>();val search=ModelBrowseState(mutableStateOf("all")){writes+=it}
        assertTrue(writes.isEmpty());search.value="all";assertTrue(writes.isEmpty())
        val (_,set)=search;set("active");set("active")
        assertEquals(listOf("active"),writes);assertEquals("active",search.component1())
    }
}
