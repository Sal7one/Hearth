package com.sal7one.common_jni.voice;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
public class MagpieNative {
 static {System.loadLibrary("hearth_magpie_jni");}
 interface Callback {boolean onPcm(short[] samples,int rate);}
 static native boolean available();
 static native long create(byte[] generator,byte[] codec,byte[] tokenizer,int threads);
 static native String[] voices(long id);
 static native void synthesize(long id,byte[] text,byte[] lang,byte[] voice,Callback callback);
 static native void cancel(long id);
 static native void destroy(long id);
 static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
 public static void main(String[] args){
  if(!available())throw new AssertionError("runtime unavailable");
  if(args[0].contains("unused-fixture-path")) {
   try{create(utf8("unicode-error"),utf8("codec"),utf8("tokenizer"),4);throw new AssertionError("native error swallowed");}
   catch(IllegalStateException expected){if(!expected.getMessage().equals("خطأ محرك Magpie 🗣"))throw new AssertionError("native UTF-8 error corrupted",expected);}
   System.out.println("PASS JNI preserves Arabic and supplementary Unicode native errors");
  }
  String root=args[0];long id=create(utf8(root+"/magpie_tts_multilingual_357m.v2607.f16.gguf"),utf8(root+"/nemo_nano_codec_22khz_1.89kbps_21.5fps.decoder.f16.gguf"),utf8(root+"/tokenizer"),4);
  if(!Arrays.equals(voices(id),new String[]{"Aria","Jason","John","Leo","Sofia"}))throw new AssertionError("speaker order");
  final long[] count={0};
  synthesize(id,utf8("مرحبا، هذا اختبار عربي من جافا."),utf8("ar-MSA"),utf8("John"),(samples,rate)->{if(samples.length==0||samples.length>rate)throw new AssertionError("PCM bound");count[0]+=samples.length;return true;});
  if(count[0]==0)throw new AssertionError("empty audio");
  try {synthesize(id,utf8("Stop after the first audio frame."),utf8("en"),utf8("Aria"),(samples,rate)->{cancel(id);return true;});throw new AssertionError("cancel ignored");}
  catch(CancellationException expected){System.out.println("PASS JNI cancellation propagates CancellationException");}
  destroy(id);destroy(id);
  long exceptionId=create(utf8(root+"/magpie_tts_multilingual_357m.v2607.f16.gguf"),utf8(root+"/nemo_nano_codec_22khz_1.89kbps_21.5fps.decoder.f16.gguf"),utf8(root+"/tokenizer"),4);
  if(root.contains("unused-fixture-path")) {
   try{synthesize(exceptionId,utf8("bad-pcm"),utf8("en"),utf8("John"),(samples,rate)->true);throw new AssertionError("malformed PCM accepted");}
   catch(IllegalStateException expected){if(!expected.getMessage().contains("invalid or oversized PCM"))throw new AssertionError("malformed PCM error changed",expected);}
   System.out.println("PASS JNI malformed PCM is a real error rather than cancellation");
  }
  RuntimeException sentinel=new RuntimeException("sentinel Java PCM callback failure");
  try{synthesize(exceptionId,utf8("Callback exception test."),utf8("en"),utf8("John"),(samples,rate)->{throw sentinel;});throw new AssertionError("callback exception swallowed");}
  catch(RuntimeException received){if(received!=sentinel)throw new AssertionError("callback exception identity lost",received);}
  finally{destroy(exceptionId);}

  if(root.contains("unused-fixture-path"))for(String mode:new String[]{"close","threaded-close"}){
   final long closing=create(utf8("generator"),utf8("codec"),utf8("tokenizer"),2);
   try{synthesize(closing,utf8(mode),utf8("en"),utf8("John"),(samples,rate)->{destroy(closing);return true;});throw new AssertionError("close did not abort");}catch(CancellationException expected){}
   try{voices(closing);throw new AssertionError("callback-close did not retire handle");}catch(IllegalStateException expected){}
  }
  System.out.println("PASS callback close from synthesis and foreign native threads");
  System.out.println("PASS JNI preserves exact Java callback exception identity");
  try{voices(id);throw new AssertionError("retired handle accepted");}catch(IllegalStateException expected){}
  System.out.println("PASS JNI Arabic PCM samples="+count[0]+" bounded frames, speaker inventory, exact-once destroy and retired handle rejection");
 }
}
