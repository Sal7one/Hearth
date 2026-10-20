package com.sal7one.hearth.tts.chatterbox

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ChatterboxGraphTest {
    private fun varint(value: Int): ByteArray { val out=ByteArrayOutputStream();var n=value;while(n>=128){out.write((n and 127) or 128);n=n ushr 7};out.write(n);return out.toByteArray() }
    private fun field(tag: Int,bytes: ByteArray)=varint((tag shl 3) or 2)+varint(bytes.size)+bytes
    private fun text(tag: Int,value: String)=field(tag,value.toByteArray())
    private fun node(last: String="",count: Int=12): ByteArray {
        var bytes=text(3,"attention")+text(4,"GroupQueryAttention")+text(7,"com.microsoft")+text(5,"opaque attribute bytes")
        repeat(count){bytes+=text(1,if(it<9)"in$it" else last)}
        return field(1,bytes)
    }
    private fun model(nodes: Int=30,last: String="",count: Int=12): ByteArray {var graph=text(2,"graph name");repeat(nodes){graph+=node(last,count)};return byteArrayOf(8,10)+field(7,graph)+text(3,"publisher metadata")}
    @Test fun stripsOnlyAbsentSlotsAndPreservesOpaqueFields() {
        val raw=model();val converted=trimOmittedGqaInputs(raw)
        assertTrue(converted.contentEquals(model(count=9)))
        assertEquals(180,raw.size-converted.size)
    }
    @Test fun rejectsActiveOptionalInputsWrongInventoryAndBrokenWire() {
        assertThrows(IllegalArgumentException::class.java){trimOmittedGqaInputs(model(last="active"))}
        assertThrows(IllegalArgumentException::class.java){trimOmittedGqaInputs(model(nodes=29))}
        assertThrows(IllegalArgumentException::class.java){trimOmittedGqaInputs(model(count=11))}
        assertThrows(IllegalArgumentException::class.java){trimOmittedGqaInputs(byteArrayOf(58,127,1))}
        assertThrows(IllegalStateException::class.java){trimOmittedGqaInputs(byteArrayOf(11))}
    }
    @Test fun explicitDeveloperFixtureMatchesPinnedDerivedGraph() {
        val path="build/voice-runtimes/chatterbox-models/onnx/language_model_q4.onnx"
        val source=listOf(java.io.File(path),java.io.File("../$path")).firstOrNull{it.isFile}
        org.junit.Assume.assumeNotNull(source)
        val root=Files.createTempDirectory("chatterbox-actual-graph").toFile()
        try {ChatterboxGraph.install(source!!,root);val result=java.io.File(root,ChatterboxGraph.FILENAME);assertEquals(ChatterboxGraph.OUTPUT_BYTES,result.length());assertEquals(ChatterboxGraph.OUTPUT_SHA256,java.security.MessageDigest.getInstance("SHA-256").digest(result.readBytes()).joinToString(""){"%02x".format(it)})}
        finally {root.deleteRecursively()}
    }
    @Test fun cancellationAndHashFailureNeverPublish() {
        val stop=java.util.concurrent.CancellationException("stop")
        assertSame(stop,assertThrows(java.util.concurrent.CancellationException::class.java){trimOmittedGqaInputs(model()){throw stop}})
        val root=Files.createTempDirectory("chatterbox-graph").toFile()
        try {val source=java.io.File(root,"raw").apply{writeBytes(ByteArray(ChatterboxGraph.SOURCE_BYTES.toInt()))};assertThrows(IllegalStateException::class.java){ChatterboxGraph.install(source,root)};assertFalse(java.io.File(root,ChatterboxGraph.FILENAME).exists())}
        finally {root.deleteRecursively()}
    }
}
