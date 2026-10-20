package com.sal7one.common_jni.voice;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
public class OmniVoiceNative {
 static {System.loadLibrary("hearth_omnivoice_jni");}
 interface Callback {boolean onPcm(float[] samples,int rate);}
 static native boolean available();
 static native long create(byte[] generator,byte[] codec);
 static native String[] languages();
 static native void synthesize(long id,byte[] text,byte[] language,int design,Callback callback);
 static native void cancel(long id);
 static native void destroy(long id);
 static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
 public static void main(String[] args){
  if(!available())throw new AssertionError("runtime unavailable");
  try{create(utf8("unicode-error"),utf8("codec"));throw new AssertionError("native error swallowed");}
  catch(IllegalStateException expected){if(!expected.getMessage().equals("خطأ محرك OmniVoice 🗣"))throw new AssertionError("native UTF-8 error corrupted",expected);}
  if(!Arrays.asList(languages()).containsAll(Arrays.asList("arb","en")))throw new AssertionError("language contract");
  long id=create(utf8("generator"),utf8("codec"));final long[] count={0};
  synthesize(id,utf8("مرحبا"),utf8("arb"),0,(samples,rate)->{if(rate!=24000||samples.length==0||samples.length>rate)throw new AssertionError("PCM bound");count[0]+=samples.length;return true;});
  if(count[0]!=24001)throw new AssertionError("PCM delivery changed");
  try{synthesize(id,utf8("stop"),utf8("en"),1,(samples,rate)->{cancel(id);return true;});throw new AssertionError("cancel ignored");}
  catch(CancellationException expected){}
  destroy(id);destroy(id);
  try{synthesize(id,utf8("closed"),utf8("en"),0,(samples,rate)->true);throw new AssertionError("retired handle accepted");}catch(IllegalStateException expected){}
  long callbackId=create(utf8("generator"),utf8("codec"));
  try{synthesize(callbackId,utf8("bad-pcm"),utf8("en"),0,(samples,rate)->true);throw new AssertionError("nonfinite PCM accepted");}
  catch(IllegalStateException expected){if(!expected.getMessage().contains("non-finite"))throw expected;}
  RuntimeException sentinel=new RuntimeException("sentinel callback failure");
  try{synthesize(callbackId,utf8("callback"),utf8("en"),2,(samples,rate)->{throw sentinel;});throw new AssertionError("callback error swallowed");}
  catch(RuntimeException received){if(received!=sentinel)throw new AssertionError("callback exception identity lost",received);}
  finally{destroy(callbackId);}

  for(String mode:new String[]{"close","threaded-close"}){
   final long closing=create(utf8("generator"),utf8("codec"));
   try{synthesize(closing,utf8(mode),utf8("en"),0,(samples,rate)->{destroy(closing);return true;});throw new AssertionError("close did not abort");}catch(CancellationException expected){}
   destroy(closing);
  }
  System.out.println("OMNIVOICE JNI CONTRACT PASS: UTF-8, exact callback exception, finite bounded PCM, cancellation, lifetime, inventory");
 }
}
