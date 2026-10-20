package com.sal7one.common_jni.voice;
import java.util.concurrent.CancellationException;
public class OuteRealSmoke {
 public static void main(String[] paths){
  if(paths.length!=2||!OuteNative.available())throw new AssertionError("Oute runtime unavailable");
  long id=OuteNative.create(OuteNative.bytes(paths[0]),OuteNative.bytes(paths[1]));
  try {
   String[] sentences={"Hello. Welcome to Hearth.","مرحباً. أهلاً بك في هيرث."};
   for(int language=0;language<sentences.length;language++) {
    long[] frames={0};double[] power={0};
    OuteNative.synthesize(id,OuteNative.bytes(sentences[language]),(samples,rate)->{
     if(rate!=24000||samples.length==0||samples.length>24000)throw new AssertionError("real PCM bounds");
     for(float sample:samples){if(!Float.isFinite(sample)||Math.abs(sample)>1)throw new AssertionError("real PCM finite range");power[0]+=sample*sample;}
     frames[0]+=samples.length;return true;
    });
    if(frames[0]<2400||power[0]<=0)throw new AssertionError("real model returned empty/silent audio");
    System.out.println("OUTE JNI "+(language==0?"English":"Arabic")+" frames="+frames[0]+" rms="+Math.sqrt(power[0]/frames[0]));
   }
   OuteNative.cancel(id);
   try{OuteNative.synthesize(id,OuteNative.bytes("Cancelled."),(a,b)->true);throw new AssertionError("cancelled context reused");}catch(CancellationException expected){}
  }finally{OuteNative.destroy(id);}
  System.out.println("OUTE REAL JNI SMOKE PASS: actual two-language PCM, same-handle reuse, cancellation");
 }
}
