package com.sal7one.common_jni.vision

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class VisionModelFileTest {
    @Test fun validatesPinsBeforeNativeLoading() {
        val file = Files.createTempFile("vision-pin", ".gguf").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            VisionModelFile(file, 3, sha).verify()
            file.writeBytes(byteArrayOf(3, 2, 1))
            assertThrows(Exception::class.java) { VisionModelFile(file, 3, sha).verify() }
            assertThrows(IllegalStateException::class.java) { VisionModelFile(file, 4, sha).verify() }
            assertThrows(IllegalArgumentException::class.java) { VisionModelFile(file, 3, "not-a-pin") }
            assertThrows(IllegalArgumentException::class.java) { VisionModelFile(file, 0, sha) }
        } finally { file.delete() }
    }
}
