package com.sal7one.common_jni.voice;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
public class OuteNative {
 static {System.loadLibrary("hearth_oute_jni");}
 interface Callback {boolean onPcm(float[] samples,int rate);}
 static native boolean available(); static native long create(byte[] model,byte[] profile);
 static native void synthesize(long id,byte[] text,Callback callback);
 static native void cancel(long id); static native void destroy(long id);
 static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static long create(){return create(bytes("model"),bytes("profile"));}
 static void rejected(long id,String mode,String expected){
  try{synthesize(id,bytes(mode),(samples,rate)->true);throw new AssertionError(mode+" accepted");}
  catch(IllegalStateException error){if(!error.getMessage().contains(expected))throw error;}
 }
 public static void main(String[] ignored)throws Exception {
  if(!available())throw new AssertionError("runtime unavailable");
  try{create(bytes("unicode-error"),bytes("profile"));throw new AssertionError("native error swallowed");}
  catch(IllegalStateException expected){if(!expected.getMessage().equals("خطأ محرك OuteTTS 🗣"))throw new AssertionError("native UTF-8 corrupted",expected);}
  long id=create();final int[] frames={0};
  synthesize(id,bytes("مرحبا"),(samples,rate)->{if(rate!=24000||samples.length<1||samples.length>24000)throw new AssertionError("PCM bounds");frames[0]+=samples.length;return true;});
  if(frames[0]!=24000)throw new AssertionError("PCM delivery changed");
  rejected(id,"empty","no audio");rejected(id,"bad-pcm","non-finite");rejected(id,"oversized","oversized");rejected(id,"rate","invalid");rejected(id,"total","oversized");
  try{synthesize(id,bytes("nested"),(samples,rate)->{synthesize(id,bytes("inner"),(a,b)->true);return true;});throw new AssertionError("recursive synthesis accepted");}catch(IllegalStateException expected){if(!expected.getMessage().contains("reentrant"))throw expected;}
  RuntimeException sentinel=new RuntimeException("callback sentinel");
  try{synthesize(id,bytes("callback"),(samples,rate)->{throw sentinel;});throw new AssertionError("callback error lost");}
  catch(RuntimeException received){if(received!=sentinel)throw new AssertionError("callback exception identity lost",received);}
  try{synthesize(id,bytes("cancel"),(samples,rate)->{cancel(id);return true;});throw new AssertionError("cancel ignored");}catch(CancellationException expected){}
  destroy(id);destroy(id);rejected(id,"closed","closed");
  long closeId=create();
  try{synthesize(closeId,bytes("close callback"),(samples,rate)->{destroy(closeId);return true;});throw new AssertionError("reentrant close ignored");}catch(CancellationException expected){}
  rejected(closeId,"closed","closed");
  long threadClose=create();
  try{synthesize(threadClose,bytes("threaded"),(samples,rate)->{destroy(threadClose);return true;});throw new AssertionError("worker callback close ignored");}catch(CancellationException expected){}
  rejected(threadClose,"closed","closed");
  long threadError=create();
  try{synthesize(threadError,bytes("threaded"),(samples,rate)->{throw sentinel;});throw new AssertionError("worker callback exception lost");}catch(RuntimeException received){if(received!=sentinel)throw new AssertionError("worker exception identity lost",received);}finally{destroy(threadError);}

  long concurrent=create();AtomicReference<Throwable> failed=new AtomicReference<>();CountDownLatch started=new CountDownLatch(1);
  Thread worker=new Thread(()->{started.countDown();try{synthesize(concurrent,bytes("wait"),(samples,rate)->true);failed.set(new AssertionError("concurrent cancel ignored"));}catch(CancellationException expected){}catch(Throwable e){failed.set(e);}});
  worker.start();started.await();Thread.sleep(30);cancel(concurrent);destroy(concurrent);worker.join(2000);
  if(worker.isAlive()||failed.get()!=null)throw new AssertionError("concurrent lifecycle failed",failed.get());
  System.out.println("OUTE JNI CONTRACT PASS: UTF-8, callback exception identity, finite bounded PCM, empty errors, cancellation, reentrant close, concurrent lifetime");
 }
}
