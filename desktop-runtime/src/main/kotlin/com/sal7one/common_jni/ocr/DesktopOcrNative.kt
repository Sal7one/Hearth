package com.sal7one.common_jni.ocr

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

/** Same owned JNI contracts as Android; the desktop library contains no Android service code. */
internal object ComicNative {
    init { DesktopNativeLibraries.load("hearth_desktop_ocr") }
    external fun create(model: ByteArray): Long
    external fun createKoharu(model: ByteArray): Long
    external fun createInpainter(model: ByteArray): Long
    external fun inpaint(handle: Long,pixels: IntArray,width: Int,height: Int,mask: IntArray): IntArray
    external fun detect(handle: Long, pixels: IntArray, width: Int, height: Int): Array<IntArray>
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}
internal object JapaneseNative {
    init { DesktopNativeLibraries.load("hearth_desktop_ocr") }
    external fun create(kind: Int, first: ByteArray, second: ByteArray, third: ByteArray): Long
    external fun recognize(handle: Long, pixels: IntArray, width: Int, height: Int): Array<IntArray>
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}
