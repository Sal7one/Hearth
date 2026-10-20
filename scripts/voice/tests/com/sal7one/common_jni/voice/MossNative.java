package com.sal7one.common_jni.voice;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
public class MossNative {
 static {System.loadLibrary("hearth_moss_jni");}
 interface Callback {boolean onPcm(float[] samples,int rate);}
 static native boolean available();
 static native long create(byte[] directory,int threads);
 static native String[] voices(long id);
 static native void synthesize(long id,byte[] text,byte[] voice,Callback callback);
 static native void cancel(long id);
 static native void destroy(long id);
 static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
 public static void main(String[] args){
  if(!available())throw new AssertionError("runtime unavailable");
  boolean fixture=args[0].equals("fixture");
  if(fixture){try{create(utf8("unicode-error"),2);throw new AssertionError("native error swallowed");}catch(IllegalStateException expected){if(!expected.getMessage().equals("خطأ محرك MOSS 🗣"))throw new AssertionError("native UTF-8 error corrupted",expected);}}
  long id=create(utf8(args[0]),2);
  try{
   if(!Arrays.asList(voices(id)).containsAll(Arrays.asList("Junhao","Ava")))throw new AssertionError("voice inventory");
   for(int i=0;i<2;++i){final long[] samples={0};synthesize(id,utf8(i==0?"مرحبا بكم.":"Hello from Hearth."),utf8(i==0?"Junhao":"Ava"),(pcm,rate)->{if(rate!=48000||pcm.length<1||pcm.length>rate)throw new AssertionError("PCM bound");for(float x:pcm)if(!Float.isFinite(x))throw new AssertionError("PCM finite");samples[0]+=pcm.length;return true;});if(samples[0]==0)throw new AssertionError("empty speech");System.out.println("MOSS JNI PCM "+(i==0?"Arabic":"English")+" samples="+samples[0]);}
   if(fixture){try{synthesize(id,utf8("bad-pcm"),utf8("Ava"),(pcm,rate)->true);throw new AssertionError("nonfinite PCM accepted");}catch(IllegalStateException expected){if(!expected.getMessage().contains("non-finite"))throw expected;}}
   RuntimeException sentinel=new RuntimeException("callback identity");
   try{synthesize(id,utf8("Callback error."),utf8("Ava"),(pcm,rate)->{throw sentinel;});throw new AssertionError("callback exception swallowed");}catch(RuntimeException received){if(received!=sentinel)throw new AssertionError("callback identity lost",received);}
  }finally{destroy(id);destroy(id);}
  long cancelId=create(utf8(args[0]),2);
  try{try{synthesize(cancelId,utf8("Cancel this."),utf8("Ava"),(pcm,rate)->{cancel(cancelId);return true;});throw new AssertionError("cancel ignored");}catch(CancellationException expected){}}
  finally{destroy(cancelId);}
  try{voices(id);throw new AssertionError("retired handle accepted");}catch(IllegalStateException expected){}

  if(fixture)for(String mode:new String[]{"close","threaded-close"}){
   final long closing=create(utf8("fixture"),2);
   try{synthesize(closing,utf8(mode),utf8("Ava"),(samples,rate)->{destroy(closing);return true;});throw new AssertionError("close did not abort");}catch(CancellationException expected){}
   try{voices(closing);throw new AssertionError("callback-close did not retire handle");}catch(IllegalStateException expected){}
  }
  System.out.println("MOSS JNI SMOKE PASS: finite bounded PCM, callback identity, cancellation, lifetime, inventory");
 }
}
