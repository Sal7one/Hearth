package com.sal7one.hearth.tts.magpie

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Exact pinned assets only. Tokenizer range payload is authenticated before TAR parsing. */
class MagpieModels(private val root: File) {
    fun file(asset: MagpieAsset): File = if (asset in MagpieCatalog.tokenizer) File(root,"tokenizer/${asset.filename}") else File(root,asset.filename)
    fun ready(): Boolean = (MagpieCatalog.weights + MagpieCatalog.tokenizer).all { file(it).let { f -> f.isFile && f.length()==it.bytes } }
    fun verify(cancelled: () -> Unit = {}) {
        (MagpieCatalog.weights + MagpieCatalog.tokenizer).forEach { asset ->
            cancelled();check(file(asset).isFile && file(asset).length()==asset.bytes){"Install Magpie ${asset.filename} first"}
            check(hash(file(asset),cancelled)==asset.sha256){"Magpie checksum failed: ${asset.filename}"}
        }
    }
    fun importWeight(input: InputStream, expected: MagpieAsset, cancelled: () -> Unit = {}) {
        require(expected in MagpieCatalog.weights);root.mkdirs()
        val temporary=File.createTempFile("magpie-",".part",root)
        try {
            input.use { source -> temporary.outputStream().use { sink ->
                val buffer=ByteArray(65536);var size=0L
                while(true){cancelled();val n=source.read(buffer);if(n<0)break;size+=n;require(size<=expected.bytes){"Magpie file exceeds pinned size"};sink.write(buffer,0,n)}
                require(size==expected.bytes){"Magpie download is incomplete"}
            }}
            check(hash(temporary,cancelled)==expected.sha256){"Magpie download checksum failed"}
            Files.move(temporary.toPath(),file(expected).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{temporary.delete()}
    }
    fun installTokenizerSegments(payload: File, cancelled: () -> Unit = {}) {
        require(payload.length()==MagpieCatalog.tokenizerSegmentsBytes){"Incomplete Magpie tokenizer ranges"}
        check(hash(payload,cancelled)==MagpieCatalog.tokenizerSegmentsSha256){"Magpie tokenizer checksum failed"}
        root.mkdirs();val temporary=Files.createTempDirectory(root.toPath(),"magpie-tokenizer-").toFile()
        val seen=mutableSetOf<String>();var entries=0;var memberIndex=0
        try {
            RandomAccessFile(payload,"r").use { input ->
                var boundary=0L
                for((segment,range) in MagpieCatalog.ranges.withIndex()) {
                    boundary+=range.last-range.first+1;var stopped=false
                    while(input.filePointer+512<=boundary) {
                        cancelled();val header=ByteArray(512);input.readFully(header)
                        if(header.all {it==0.toByte()})break
                        fun field(offset: Int,length: Int)=header.copyOfRange(offset,offset+length).toString(Charsets.US_ASCII).trimEnd('\u0000',' ')
                        fun octal(offset: Int,length: Int)=field(offset,length).trim().let {if(it.isEmpty())0L else it.toLong(8)}
                        val checksum=header.indices.sumOf { i ->if(i in 148..155)32 else header[i].toInt() and 255 }.toLong()
                        require(checksum==octal(148,8)){"Invalid Magpie TAR header checksum"}
                        require(++entries<=80){"Magpie tokenizer contains too many TAR entries"}
                        val name=field(0,100).removePrefix("./");val prefix=field(345,155)
                        require(prefix.isEmpty() && !name.startsWith('/') && '\\' !in name && name.split('/').none {it==".."}){"Unsafe Magpie tokenizer path"}
                        val size=octal(124,12);require(size>=0){"Invalid Magpie TAR size"}
                        val type=header[156].toInt().toChar()
                        if(segment==0 && name=="model_weights.ckpt") {stopped=true;break}
                        val padded=((size+511)/512)*512
                        require(input.filePointer+padded<=boundary){"Truncated Magpie TAR member"}
                        when(type) {
                            '0','\u0000' -> {
                                val asset=MagpieCatalog.archiveMembers.getOrNull(memberIndex++) ?: error("Unexpected Magpie archive member")
                                require(asset.filename==name && size==asset.bytes){"Magpie tokenizer sequence or size mismatch"}
                                val bytes=ByteArray(size.toInt());input.readFully(bytes)
                                val actual=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                                check(actual==asset.sha256){"Magpie tokenizer member checksum failed: $name"}
                                if(MagpieCatalog.tokenizer.any {it.filename==name && it.bytes==size && it.sha256==actual}) {
                                    require(seen.add(name)){"Duplicate final Magpie tokenizer member"}
                                    File(temporary,name).writeBytes(bytes)
                                }
                            }
                            '5' -> require(size==0L && name in setOf("",".")){"Unsupported Magpie tokenizer directory"}
                            'x','g' -> {
                                require(size<=65536){"Magpie PAX metadata exceeds bound"}
                                val bytes=ByteArray(size.toInt());input.readFully(bytes)
                                var offset=0
                                while(offset<bytes.size) {
                                    val space=bytes.indexOfFrom(' '.code.toByte(),offset)
                                    require(space>offset){"Invalid Magpie PAX record"}
                                    val length=bytes.copyOfRange(offset,space).toString(Charsets.US_ASCII).toInt()
                                    require(length>space-offset+2 && offset+length<=bytes.size){"Invalid Magpie PAX length"}
                                    val record=bytes.copyOfRange(space+1,offset+length).toString(Charsets.US_ASCII)
                                    require(record.substringBefore('=') in setOf("mtime","atime","ctime")){"Unsupported Magpie PAX override"}
                                    offset+=length
                                }
                            }
                            else -> error("Unsupported Magpie TAR entry type")
                        }
                        input.seek(input.filePointer+(padded-size))
                    }
                    if(segment==0)require(stopped){"Magpie tokenizer prefix did not reach the expected stop member"}
                    input.seek(boundary)
                }
            }
            check(memberIndex==MagpieCatalog.archiveMembers.size){"Incomplete Magpie archive sequence"}
            check(seen==MagpieCatalog.tokenizer.map {it.filename}.toSet()){"Magpie tokenizer files are missing"}
            val destination=File(root,"tokenizer");val backup=File(root,"tokenizer.old")
            check(!backup.exists() || backup.deleteRecursively()){"Could not clean prior tokenizer staging"}
            if(destination.exists())Files.move(destination.toPath(),backup.toPath(),StandardCopyOption.ATOMIC_MOVE)
            try {Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)}
            catch(error: Exception){if(backup.exists())Files.move(backup.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);throw error}
            backup.deleteRecursively()
        }finally{temporary.deleteRecursively()}
    }
    private fun ByteArray.indexOfFrom(value: Byte,start: Int): Int {for(index in start until size)if(this[index]==value)return index;return -1}
    private fun hash(file: File,cancelled: () -> Unit): String {
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input ->val buffer=ByteArray(65536);while(true){cancelled();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
