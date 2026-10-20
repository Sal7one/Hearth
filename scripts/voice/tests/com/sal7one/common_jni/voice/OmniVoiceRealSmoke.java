package com.sal7one.common_jni.voice;
import java.util.concurrent.CancellationException;
public class OmniVoiceRealSmoke {
 public static void main(String[] args){
  if(!OmniVoiceNative.available())throw new AssertionError("real runtime unavailable");
  long id=OmniVoiceNative.create(OmniVoiceNative.utf8(args[0]+"/omnivoice-base-Q8_0.gguf"),OmniVoiceNative.utf8(args[0]+"/omnivoice-tokenizer-Q8_0.gguf"));
  try{
   for(int i=0;i<2;++i){final long[] samples={0};OmniVoiceNative.synthesize(id,OmniVoiceNative.utf8(i==0?"مرحبا بكم.":"Hello from Hearth."),OmniVoiceNative.utf8(i==0?"arb":"en"),i,(pcm,rate)->{if(rate!=24000||pcm.length<1||pcm.length>rate)throw new AssertionError("PCM bound");for(float x:pcm)if(!Float.isFinite(x))throw new AssertionError("PCM finite");samples[0]+=pcm.length;return true;});if(samples[0]==0)throw new AssertionError("empty speech");System.out.println("REAL JNI PCM "+(i==0?"Arabic":"English")+" samples="+samples[0]);}
   RuntimeException sentinel=new RuntimeException("callback identity");
   try{OmniVoiceNative.synthesize(id,OmniVoiceNative.utf8("Callback error."),OmniVoiceNative.utf8("en"),0,(pcm,rate)->{throw sentinel;});throw new AssertionError("callback exception swallowed");}catch(RuntimeException received){if(received!=sentinel)throw new AssertionError("callback identity lost",received);}
   try{OmniVoiceNative.synthesize(id,OmniVoiceNative.utf8("Cancel this."),OmniVoiceNative.utf8("en"),2,(pcm,rate)->{OmniVoiceNative.cancel(id);return true;});throw new AssertionError("cancel ignored");}catch(CancellationException expected){}
  }finally{OmniVoiceNative.destroy(id);OmniVoiceNative.destroy(id);}
  System.out.println("OMNIVOICE REAL JNI SMOKE PASS");
 }
}
