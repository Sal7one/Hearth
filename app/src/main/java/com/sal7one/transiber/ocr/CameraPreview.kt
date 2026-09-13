package com.sal7one.transiber.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal fun upright(image: ImageProxy, maxSide: Int): Bitmap {
    val original=image.toBitmap()
    val scale=minOf(1f,maxSide.toFloat()/maxOf(original.width,original.height))
    val matrix=Matrix().apply { postScale(scale,scale);postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    val output=Bitmap.createBitmap(original,0,0,original.width,original.height,matrix,true)
    if(output !== original)original.recycle()
    return output
}
/** Adapted from Hearth's CameraDetectionScreen: latest frame only, unconditional proxy closure. */
@Composable
internal fun CameraPreview(modifier: Modifier, live: Boolean, onFrame: (Bitmap) -> Unit, onCaptureReady: (((() -> Unit)?) -> Unit), onPhoto: (Bitmap) -> Unit, onError: (Throwable) -> Unit) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    val currentLive=rememberUpdatedState(live);val frame=rememberUpdatedState(onFrame);val photo=rememberUpdatedState(onPhoto);val failure=rememberUpdatedState(onError)
    val view=remember { PreviewView(context).apply { scaleType=PreviewView.ScaleType.FIT_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE } }
    AndroidView(factory={view},modifier=modifier)
    DisposableEffect(lifecycle) {
        val closed=AtomicBoolean(false);val executor=Executors.newSingleThreadExecutor();val main=ContextCompat.getMainExecutor(context)
        val future=ProcessCameraProvider.getInstance(context);var provider: ProcessCameraProvider?=null
        val resolution=ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(960,720),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val preview=Preview.Builder().setResolutionSelector(resolution).build()
        val analysis=ImageAnalysis.Builder().setResolutionSelector(resolution).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val capture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        var last=0L
        analysis.setAnalyzer(executor) { image ->
            try {
                val now=android.os.SystemClock.elapsedRealtime()
                if(!closed.get() && currentLive.value && now-last>=650) {last=now;frame.value(upright(image,960))}
            } catch(e: Exception) { if(!closed.get())main.execute { if(!closed.get())failure.value(e) } }
            finally {image.close()}
        }
        future.addListener({if(!closed.get())try {
            provider=future.get();preview.surfaceProvider=view.surfaceProvider
            provider!!.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis,capture)
            onCaptureReady({ capture.takePicture(executor,object:ImageCapture.OnImageCapturedCallback(){
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {if(!closed.get()){val bitmap=upright(image,1600);main.execute {if(closed.get())bitmap.recycle() else photo.value(bitmap)}}}
                    catch(e: Exception){if(!closed.get())main.execute { if(!closed.get())failure.value(e) }} finally{image.close()}
                }
                override fun onError(exception: ImageCaptureException){if(!closed.get())main.execute {if(!closed.get())failure.value(exception)}}
            }) })
        } catch(e: Exception){failure.value(e)}},main)
        onDispose {closed.set(true);onCaptureReady(null);analysis.clearAnalyzer();provider?.unbind(preview,analysis,capture);executor.shutdown()}
    }
}
