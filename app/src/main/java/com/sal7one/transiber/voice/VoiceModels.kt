package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.ming.MingCatalog
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Both public downloads and document imports use this verified installation sink. */
internal class VoiceModels(private val root: File, private val permitFamily: (String) -> Unit = {}, private val permitOmniVoice: () -> Unit = {}, private val permitMagpie: () -> Unit = {}) {
    fun file(asset: VoiceAsset) = File(root,if(asset.family != "supertonic")"${asset.family}/${asset.installedPath}" else asset.filename)
    fun installed(asset: VoiceAsset) = file(asset).let {it.isFile && it.length()==asset.installedBytes}
    fun verifyAsset(asset: VoiceAsset, cancelled: () -> Unit = {}) {
        check(installed(asset)) { "Installed voice component is missing: ${asset.filename}" }
        val digest=MessageDigest.getInstance("SHA-256")
        file(asset).inputStream().use { input -> val buffer=ByteArray(65536);while(true){cancelled();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
        check(digest.digest().joinToString(""){"%02x".format(it)}==asset.installedSha256) { "Installed voice checksum failed: ${asset.filename}" }
    }
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
                permitFamily("magpie")
                permitMagpie()
                com.sal7one.hearth.tts.magpie.MagpieModels(File(root,"magpie")).installTokenizerSegments(temporary,cancelled)
                return VoiceAsset("voice-magpie-tokenizer","tokenizer.ranges","tokenizer.ranges",size,hash,com.sal7one.hearth.tts.magpie.MagpieCatalog.source)
            }
            val asset=VoiceCatalog.assets.firstOrNull {it.bytes==size && it.sha256==hash}
                ?: error("Voice file does not match a pinned Hearth speech voice component")
            require(expected==null || expected==asset){"Downloaded voice file does not match the selected asset"}
            permitFamily(asset.family)
            if(asset.id.startsWith("voice-magpie-"))permitMagpie()
            if(asset.family == "omnivoice")permitOmniVoice()
            if(asset.family == "chatterbox-v3" && asset.path == com.sal7one.hearth.tts.chatterbox.ChatterboxGraph.SOURCE_FILENAME) {
                com.sal7one.hearth.tts.chatterbox.ChatterboxGraph.install(temporary,File(root,"chatterbox-v3"),cancelled)
                return asset
            }
            if(asset.id == "voice-chatterbox-v3-perth") {
                com.sal7one.hearth.tts.chatterbox.ChatterboxPerth.install(temporary,File(root,"chatterbox-v3"),cancelled)
                return asset
            }
            cancelled();file(asset).parentFile?.mkdirs();Files.move(temporary.toPath(),file(asset).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            return asset
        }finally {temporary.delete()}
    }
    fun importZip(input: InputStream, onImported: (VoiceAsset) -> Unit = {}, cancelled: () -> Unit = {}): Int {
        var count=0;var entries=0;var supertonicTotal=0L;var mingTotal=0L;val seen=mutableSetOf<String>()
        ZipInputStream(input).use {zip ->
            while(true) {
                cancelled();val entry=zip.nextEntry ?: break
                require(++entries<=80){"Voice ZIP has too many entries"}
                require(!entry.name.startsWith('/') && entry.name.split('/','\\').none {it==".."}){"Unsafe voice ZIP path"}
                if(entry.isDirectory)continue
                val name=entry.name.substringAfterLast('/')
                if(name in setOf("README.md","LICENSE",".gitattributes")) {var bytes=0;val buffer=ByteArray(4096);while(true){cancelled();val n=zip.read(buffer);if(n<0)break;bytes+=n;require(bytes<=65536){"ZIP document exceeds limit"}};continue}
                // Supertonic ZIPs use flat names. The Ming bundle may be zipped either flat or as
                // the published parts/000.bin layout; MingCatalog maps both to the flat install name.
                val expected=VoiceCatalog.supertonicAssets.firstOrNull {it.filename==name}
                    ?: MingCatalog.flatFilename(entry.name)?.let {flat -> VoiceCatalog.mingAssets.firstOrNull {it.filename==flat}}
                    ?: error("Unsupported voice ZIP entry: ${entry.name}")
                require(seen.add(expected.filename)){"Duplicate voice file: ${expected.filename}"}
                when(expected.family) {
                    "supertonic" -> { supertonicTotal+=expected.bytes;require(supertonicTotal<=410_000_000){"Supertonic voice ZIP exceeds installation bound"} }
                    MingCatalog.FAMILY -> { mingTotal+=expected.bytes;require(mingTotal<=MingCatalog.totalBytes){"Ming bundle ZIP exceeds installation bound"} }
                    else -> error("Unsupported voice ZIP entry: ${entry.name}")
                }
                onImported(import(zip,expected,cancelled));count++
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
