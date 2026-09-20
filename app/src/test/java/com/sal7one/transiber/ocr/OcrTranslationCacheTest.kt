package com.sal7one.transiber.ocr

import com.sal7one.common_jni.ocr.OcrLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OcrTranslationCacheTest {
    @Test fun layoutWhitespaceAndUnicodeCompositionReuseTranslationWithoutFuzzyWords() {
        val cache=OcrTranslationCache()
        cache.put("明日 学校 に行く", "Go to school tomorrow")
        assertEquals("Go to school tomorrow",cache["明日\n学校に行く"])
        cache.put("Café costs 12 dollars.","fixture")
        assertEquals("fixture",cache["  Cafe\u0301\ncosts\u00a012  dollars.  "])
        for(text in listOf("Café costs 13 dollars.","Café costs 12 dollars?","Café does not cost 12 dollars.","café costs 12 dollars."))assertNull(cache[text])
    }
    @Test fun fullDensePageSurvivesScrollingAndRemapsToNewCoordinatesWithoutInference()=runBlocking {
        val cache=OcrTranslationCache();var calls=0
        val original=List(64) {i->OcrLine(20,i*30,100,25,"Box $i",1f)}
        suspend fun translate(text: String): String { calls++;return "translated $text".also {cache.put(text,it)} }
        OcrPageTranslation.run(original,{true},::translate,{},cached={cache[it]})
        var restored=emptyList<TranslatedOcrBox>()
        val scrolled=original.map {it.copy(y=it.y-15,text="  ${it.text}\n")}
        OcrPageTranslation.run(scrolled,{true},::translate,{restored=it},cached={cache[it]})
        assertEquals(64,calls);assertEquals(scrolled,restored.map {it.line})
        assertEquals(original.map {"translated ${it.text}"},restored.map {it.translation})
    }
    @Test fun cachedBoxesAppearBeforeSlowNewTextAndRemainInSourceOrder()=runBlocking {
        val cache=OcrTranslationCache();cache.put("known","known translation")
        val lines=listOf("new","known").mapIndexed {i,t->OcrLine(0,i*30,100,20,t,1f)}
        val updates=mutableListOf<List<TranslatedOcrBox>>()
        OcrPageTranslation.run(lines,{true},{
            assertEquals("known translation",updates.single().single().translation)
            "new translation"
        },{updates+=it},cached={cache[it]})
        assertEquals(lines,updates.last().map {it.line})
        assertEquals(listOf(1,2),updates.map {it.size})
    }
    @Test fun normalizedDuplicatesShareOneInferenceAndStalePageNeverPublishesCache()=runBlocking {
        val cache=OcrTranslationCache();var calls=0
        val lines=listOf("明日 学校","明日\n学校").mapIndexed {i,t->OcrLine(i*150,0,100,50,t,1f)}
        OcrPageTranslation.run(lines,{true},{text->calls++;"school tomorrow".also {cache.put(text,it)}},{},cached={cache[it]})
        assertEquals(1,calls)
        OcrPageTranslation.run(lines,{false},{error("stale inference")},{error("stale cache publication")},cached={cache[it]})
    }
    @Test fun boundedLruRetainsRecentlyUsedTextAndDoesNotCacheFailures() {
        val cache=OcrTranslationCache(maxEntries=2,maxCharacters=20)
        cache.put("a","one");cache.put("b","two");assertEquals("one",cache["a"])
        cache.put("c","three");assertNull(cache["b"]);assertEquals("one",cache["a"])
        cache.put("a"," ");assertEquals("one",cache["a"])
        cache.put("huge","x".repeat(30));assertNull(cache["huge"]);assertEquals("one",cache["a"])
        cache.put("d","1234567890");assertNull(cache["c"]);assertEquals("one",cache["a"])
        assertNull(OcrTranslationCache()["a"]) // Another language/provider session shares no values.
    }
}
