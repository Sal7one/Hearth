package com.sal7one.common_jni.voice

import java.text.Normalizer
import java.util.Locale

/** Shared bounds and Unicode preparation for actual Supertonic inputs. */
object VoiceText {
    val supertonicLanguages = "en ko ja ar bg cs da de el es et fi fr hi hr hu id it lt lv nl pl pt ro ru sk sl sv tr uk vi".split(' ').toSet()
    fun chunks(text: String, limit: Int = 180): List<String> {
        require(text.isNotBlank() && text.length <= 5000) { "Read aloud accepts 1–5000 characters" }
        require(limit in 16..300)
        val out=mutableListOf<String>(); var remaining=text.trim()
        while(remaining.isNotEmpty()) {
            if(remaining.length<=limit){out+=remaining;break}
            var sentence=0; var clause=0; var word=0
            for(index in 0 until limit) {
                val c=remaining[index]
                if(c.isWhitespace())word=index
                if(index<limit/3)continue
                if(c=='\n' || isSentenceEnd(remaining,index)) {
                    // A terminator and its closing quotes belong to the same request.
                    var end=index+1
                    while(end<remaining.length && remaining[end] in sentencePunctuation+closingPunctuation)end++
                    if(end<=limit)sentence=end
                } else if(c in clausePunctuation &&
                    !(remaining.getOrNull(index-1)?.isDigit()==true && remaining.getOrNull(index+1)?.isDigit()==true) &&
                    (c in "،؛，、；：" || remaining.getOrNull(index+1)?.isWhitespace()==true))clause=index+1
            }
            var end=sentence.takeIf {it>0} ?: clause.takeIf {it>0} ?: word.takeIf {it>0} ?: limit
            if(Character.isHighSurrogate(remaining[end-1]) && Character.isLowSurrogate(remaining[end]))end--
            // When a hard cut lands inside a closing-punctuation run, carry speech with it.
            if(remaining[end] in closingPunctuation ||
                (remaining[end] in sentencePunctuation && remaining[end-1] in sentencePunctuation)) {
                var before=end
                while(before>0 && remaining[before-1] in sentencePunctuation+closingPunctuation)before--
                if(before>0) {
                    val lastWord=remaining.substring(0,before).indexOfLast(Char::isWhitespace)+1
                    val borrowed=if(lastWord>0)lastWord else before-Character.charCount(remaining.codePointBefore(before))
                    if(borrowed>0)end=borrowed
                }
            }
            end=keepPunctuationTail(remaining,end,limit)
            out+=remaining.substring(0,end).trim();remaining=remaining.substring(end).trimStart()
        }
        return out.filter(String::isNotBlank)
    }
    private const val sentencePunctuation=".!?。！？؟…"
    private const val closingPunctuation="\"'”’»›)]}」』】）》〉"
    private const val clausePunctuation=",;:،؛，、；：—"
    private val abbreviations=setOf("dr","prof","mr","mrs","ms","sr","jr","e.g","i.e","etc","vs")

    private fun isSentenceEnd(text: String,index: Int): Boolean {
        val c=text[index]
        if(c !in sentencePunctuation)return false
        if(c!='.')return true
        // Dots inside numbers, domains and initials are not sentence boundaries.
        val next=text.getOrNull(index+1)
        if(next!=null && !next.isWhitespace() && next !in closingPunctuation+sentencePunctuation)return false
        var start=index
        while(start>0 && (text[start-1].isLetter() || text[start-1]=='.'))start--
        val token=text.substring(start,index).lowercase(Locale.ROOT)
        if(token in abbreviations)return false
        if(token.split('.').all {it.length==1 && it[0].isLetter()})return false
        return true
    }

    /** Rebalance a short punctuation-only tail without dropping it or exceeding the engine bound. */
    private fun keepPunctuationTail(text: String,end: Int,limit: Int): Int {
        fun hasContent(value: String)=value.codePoints().anyMatch {
            Character.isLetterOrDigit(it) || Character.getType(it)==Character.OTHER_SYMBOL.toInt()
        }
        if(hasContent(text.substring(end)))return end
        val prefix=text.substring(0,end).trimEnd()
        val lastWord=prefix.indexOfLast(Char::isWhitespace)+1
        if(lastWord>0 && text.length-lastWord<=limit && hasContent(prefix.substring(lastWord)))return lastWord
        // A single long word has no word boundary to borrow; move its last code point instead.
        var previous=prefix.length
        while(previous>0) {
            val point=prefix.codePointBefore(previous)
            previous-=Character.charCount(point)
            if(Character.isLetterOrDigit(point) || Character.getType(point)==Character.OTHER_SYMBOL.toInt())
                return if(previous>0 && text.length-previous<=limit)previous else end
        }
        return end
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
