package com.sal7one.transiber.voice

import com.sal7one.common_jni.voice.SupertonicVoice
import com.sal7one.common_jni.voice.VoiceStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Both public downloads and document imports use this verified installation sink. */
internal class VoiceModels(private val root: File) {
    fun file(asset: VoiceAsset) = File(root,asset.filename)
    fun installed(asset: VoiceAsset) = file(asset).let {it.isFile && it.length()==asset.bytes}
    fun ready(voice: String) = VoiceCatalog.core.all(::installed) && voice in VoiceCatalog.voices && installed(VoiceCatalog.file("$voice.json"))
    fun import(input: InputStream, expected: VoiceAsset?=null, cancelled: () -> Unit = {}): VoiceAsset {
        root.mkdirs(); val temporary=File.createTempFile("voice-", ".part",root)
        try {
            val digest=MessageDigest.getInstance("SHA-256");var size=0L
            temporary.outputStream().use {out ->
                val buffer=ByteArray(65536)
                while(true) {
                    cancelled();val count=input.read(buffer);if(count<0)break
                    size+=count;require(size<=(expected?.bytes ?: 260_000_000)){"Voice model file exceeds the supported size"}
                    digest.update(buffer,0,count);out.write(buffer,0,count)
                }
            }
            val hash=digest.digest().joinToString(""){"%02x".format(it)}
            val asset=VoiceCatalog.assets.firstOrNull {it.bytes==size && it.sha256==hash}
                ?: error("Voice file does not match the pinned Supertonic 3 model or voice styles")
            require(expected==null || expected==asset){"Downloaded voice file does not match the selected asset"}
            cancelled();Files.move(temporary.toPath(),file(asset).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
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
                val expected=VoiceCatalog.assets.firstOrNull {it.filename==name} ?: error("Unsupported voice ZIP entry: $name")
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
    fun style(voice: String): VoiceStyle {
        require(voice in VoiceCatalog.voices)
        val json=JSONObject(file(VoiceCatalog.file("$voice.json")).readText())
        fun values(key: String, a: Int, b: Int): FloatArray {
            val value=json.getJSONObject(key);val dims=value.getJSONArray("dims")
            require(dims.length()==3 && dims.getInt(0)==1 && dims.getInt(1)==a && dims.getInt(2)==b){"Invalid voice style dimensions"}
            val data=value.getJSONArray("data").getJSONArray(0)
            return FloatArray(a*b){i ->data.getJSONArray(i/b).getDouble(i%b).toFloat().also {require(it.isFinite())}}
        }
        return VoiceStyle(values("style_ttl",50,256),values("style_dp",8,16))
    }
    fun indexer(): LongArray = JSONArray(file(VoiceCatalog.file("unicode_indexer.json")).readText()).let {a ->LongArray(a.length(),a::getLong)}
    fun open() = SupertonicVoice(root)
}
