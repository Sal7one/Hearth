package com.sal7one.common_jni.voice

import java.text.Normalizer

/** Shared bounds and Unicode preparation for actual Supertonic inputs. */
object VoiceText {
    val supertonicLanguages = "en ko ja ar bg cs da de el es et fi fr hi hr hu id it lt lv nl pl pt ro ru sk sl sv tr uk vi".split(' ').toSet()
    fun chunks(text: String, limit: Int = 180): List<String> {
        require(text.isNotBlank() && text.length <= 5000) { "Read aloud accepts 1–5000 characters" }
        require(limit in 16..300)
        val out=mutableListOf<String>(); var remaining=text.trim()
        while(remaining.isNotEmpty()) {
            if(remaining.length<=limit){out+=remaining;break}
            var end=remaining.take(limit).indexOfLast { it in ".!?。！？\n؛؟" }.takeIf {it>=limit/3}?.plus(1)
                ?: remaining.take(limit).indexOfLast(Char::isWhitespace).takeIf {it>0} ?: limit
            if(Character.isHighSurrogate(remaining[end-1]) && Character.isLowSurrogate(remaining[end]))end--
            out+=remaining.substring(0,end).trim();remaining=remaining.substring(end).trimStart()
        }
        return out.filter(String::isNotBlank)
    }
    fun prepare(text: String, language: String): String {
        require(language in supertonicLanguages) { "Supertonic 3 does not support $language" }
        require(text.isNotBlank() && text.length<=300)
        var value=Normalizer.normalize(text,Normalizer.Form.NFKD)
        value=value.codePoints().filter {it !in 0x1F000..0x1FAFF && it !in 0x2600..0x27BF}.toArray().let { String(it,0,it.size) }
        for((from,to) in listOf("–" to "-","‑" to "-","—" to "-","_" to " ","“" to "\"","”" to "\"","‘" to "'","’" to "'","´" to "'","`" to "'","[" to " ","]" to " ","|" to " ","/" to " ","#" to " ","→" to " ","←" to " ","@" to " at ","e.g.," to "for example, ","i.e.," to "that is, ")) value=value.replace(from,to)
        value=value.replace(Regex("[♥☆♡©\\\\]"),"").replace(Regex("\\s+([,.!?;:'])"),"$1")
        value=value.replace(Regex("([\"'])\\1+"),"$1").replace(Regex("\\s+")," ").trim()
        require(value.isNotBlank()) { "No speakable text remains after voice normalization" }
        if(value.last() !in ".!?;:,'\"“”‘’)\u005d}…。」』】〉》›»؟")value+="."
        return "<$language>$value</$language>"
    }
    fun ids(text: String, language: String, indexer: LongArray): LongArray = prepare(text,language).codePoints().toArray().map { point ->
        require(point in indexer.indices && indexer[point]>=0) { "Supertonic cannot encode character U+${point.toString(16)}" };indexer[point]
    }.toLongArray().also {require(it.size<=1024){"Voice input exceeds the token bound"}}
}
