package com.sal7one.transiber.voice

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Both public downloads and document imports use this verified installation sink. */
internal class VoiceModels(private val root: File) {
    fun file(asset: VoiceAsset) = File(root,if(asset.id.startsWith("voice-magpie-"))"magpie/${asset.filename}" else asset.filename)
    fun installed(asset: VoiceAsset) = file(asset).let {it.isFile && it.length()==asset.bytes}
    fun ready(voice: String, gender: VoiceGender = VoiceGender.ANY): Boolean {
        val requested = VoiceGenderMapping.supertonicVoice(voice, gender)
        return VoiceCatalog.core.all(::installed) && requested in VoiceCatalog.voices &&
            installed(VoiceCatalog.file("$requested.json"))
    }
    fun import(input: InputStream, expected: VoiceAsset?=null, cancelled: () -> Unit = {}): VoiceAsset {
        root.mkdirs(); expected?.let {file(it).parentFile?.mkdirs()}; val temporary=File.createTempFile("voice-", ".part",root)
        try {
            val digest=MessageDigest.getInstance("SHA-256");var size=0L
            temporary.outputStream().use {out ->
                val buffer=ByteArray(65536)
                while(true) {
                    cancelled();val count=input.read(buffer);if(count<0)break
                    size+=count;require(size<=(expected?.bytes ?: VoiceCatalog.assets.maxOf {it.bytes})){"Voice model file exceeds the supported size"}
                    digest.update(buffer,0,count);out.write(buffer,0,count)
                }
            }
            val hash=digest.digest().joinToString(""){"%02x".format(it)}
            if(size==com.sal7one.hearth.tts.magpie.MagpieCatalog.tokenizerSegmentsBytes && hash==com.sal7one.hearth.tts.magpie.MagpieCatalog.tokenizerSegmentsSha256) {
                require(expected==null){"Expected a different voice component"}
                com.sal7one.hearth.tts.magpie.MagpieModels(File(root,"magpie")).installTokenizerSegments(temporary,cancelled)
                return VoiceAsset("voice-magpie-tokenizer","tokenizer.ranges","tokenizer.ranges",size,hash,com.sal7one.hearth.tts.magpie.MagpieCatalog.source)
            }
            val asset=VoiceCatalog.assets.firstOrNull {it.bytes==size && it.sha256==hash}
                ?: error("Voice file does not match a pinned Hearth speech voice component")
            require(expected==null || expected==asset){"Downloaded voice file does not match the selected asset"}
            cancelled();file(asset).parentFile?.mkdirs();Files.move(temporary.toPath(),file(asset).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            return asset
        }finally {temporary.delete()}
    }
    fun importZip(input: InputStream, cancelled: () -> Unit = {}): Int {
        var count=0;var entries=0;var total=0L;val seen=mutableSetOf<String>()
        ZipInputStream(input).use {zip ->
            while(true) {
                cancelled();val entry=zip.nextEntry ?: break
                require(++entries<=80){"Voice ZIP has too many entries"}
                require(!entry.name.startsWith('/') && entry.name.split('/','\\').none {it==".."}){"Unsafe voice ZIP path"}
                if(entry.isDirectory)continue
                val name=entry.name.substringAfterLast('/')
                if(name in setOf("README.md","LICENSE",".gitattributes")) {var bytes=0;val buffer=ByteArray(4096);while(true){cancelled();val n=zip.read(buffer);if(n<0)break;bytes+=n;require(bytes<=65536){"ZIP document exceeds limit"}};continue}
                require(seen.add(name)){"Duplicate voice file: $name"}
                val expected=VoiceCatalog.supertonicAssets.firstOrNull {it.filename==name} ?: error("Unsupported voice ZIP entry: $name")
                total+=expected.bytes;require(total<=410_000_000){"Voice ZIP exceeds installation bound"}
                import(zip,expected,cancelled);count++
            }
        }
        require(count>0){"Voice ZIP contains no supported model files"}
        return count
    }
    fun verify(voice: String, cancelled: () -> Unit = {}) {
        check(ready(voice)){"Download or import Supertonic 3 and voice $voice first"}
        (VoiceCatalog.core+VoiceCatalog.file("$voice.json")).forEach {asset ->
            val digest=MessageDigest.getInstance("SHA-256")
            file(asset).inputStream().use {input ->val b=ByteArray(65536);while(true){cancelled();val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
            check(digest.digest().joinToString(""){"%02x".format(it)}==asset.sha256){"Installed voice checksum failed: ${asset.filename}"}
        }
    }
}
