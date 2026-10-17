"""Execute real InstrumentBridge Java with deterministic Android IPC/clock stubs."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
'android/content/Context.java': 'package android.content; public class Context {}',
'android/os/RemoteException.java': 'package android.os; public class RemoteException extends Exception {}',
'android/os/IBinder.java': '''package android.os; public interface IBinder {
 interface DeathRecipient { void binderDied(); }
 void linkToDeath(DeathRecipient d,int f) throws RemoteException;
 boolean unlinkToDeath(DeathRecipient d,int f);
}''',
'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long now; public static long elapsedRealtime(){return now;} }',
'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
 public static ArrayList<Runnable> immediate=new ArrayList<Runnable>(), delayed=new ArrayList<Runnable>();
 public Handler(Looper l) {} public boolean post(Runnable r){ immediate.add(r);return true; }
 public boolean postDelayed(Runnable r,long delay){delayed.add(r);return true;}
 public static void drain(){while(!immediate.isEmpty())immediate.remove(0).run();}
 public static void tick(){ArrayList<Runnable> old=delayed;delayed=new ArrayList<Runnable>();for(Runnable r:old)r.run();}
}''',
'android/os/Bundle.java': '''package android.os; import java.util.*; public class Bundle {
 public HashMap<String,Object> data=new HashMap<String,Object>();
 public void putString(String k,String v){data.put(k,v);} public void putLong(String k,long v){data.put(k,Long.valueOf(v));}
 public void putInt(String k,int v){data.put(k,Integer.valueOf(v));} public void putBoolean(String k,boolean v){data.put(k,Boolean.valueOf(v));}
 public void putAll(Bundle b){data.putAll(b.data);} public Object get(String k){return data.get(k);}
}''',
'android/os/Message.java': '''package android.os; public class Message {
 public int what;public Messenger replyTo;private Bundle data=new Bundle();
 public static Message obtain(){return new Message();} public void setData(Bundle d){data=d;} public Bundle getData(){return data;}
}''',
'android/os/Messenger.java': '''package android.os; import java.util.*; public class Messenger {
 public ArrayList<Message> received=new ArrayList<Message>();public Binder binder=new Binder(); public boolean fail;
 public IBinder getBinder(){return binder;}public void send(Message m)throws RemoteException{if(fail)throw new RemoteException();received.add(m);}
 public static class Binder implements IBinder {public ArrayList<DeathRecipient> deaths=new ArrayList<DeathRecipient>();
 public void linkToDeath(DeathRecipient d,int f){deaths.add(d);} public boolean unlinkToDeath(DeathRecipient d,int f){return deaths.remove(d);}
 public void die(){for(DeathRecipient d:new ArrayList<DeathRecipient>(deaths))d.binderDied();}}
}''',
'android/util/Log.java': 'package android.util; public class Log {public static int w(String t,String m,Throwable e){return 0;}}',
'com/tencent/wecarflow/carplayguard/GuardRuntime.java': '''package com.tencent.wecarflow.carplayguard;
 public class GuardRuntime {public static boolean centralProcess=false;public static android.content.Context context; public static void pullRemote(){} public static boolean guarded;public static long revision;public static int lastCommand;public static boolean accepted=true;
 public static boolean isGuarded(){return guarded;}public static long getRevision(){return revision;}
 public static boolean forwardMediaCommand(int c){lastCommand=c;return accepted;}}
''',
'com/tencent/wecarflow/carplayguard/BridgeBootstrap.java': 'package com.tencent.wecarflow.carplayguard; public class BridgeBootstrap {public static boolean isBridgeProcess(android.content.Context c){return false;}}',
'BridgeHarness.java': '''import android.content.Context;import android.os.*;import com.tencent.wecarflow.carplayguard.*;
public class BridgeHarness {
 public static class Service extends Context {int metadata,playing;
 private void f(Messenger m){metadata++;}private void o(Messenger m){playing++;}}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static boolean request(int code,Messenger m){Message in=Message.obtain();in.what=code;in.replyTo=m;return InstrumentBridge.handle(in);}
 static Message last(Messenger m,int code){for(int i=m.received.size()-1;i>=0;i--)if(m.received.get(i).what==code)return m.received.get(i);throw new AssertionError("missing "+code);}
 public static void main(String[] args)throws Exception {
  Service service=new Service();InstrumentBridge.onCreate(service);
  Messenger all=new Messenger(), meta=new Messenger(), playing=new Messenger();
  check(!request(3001,all),"native registration preserved");request(1007,meta);request(1006,playing);
  GuardRuntime.guarded=true;GuardRuntime.revision++;
  InstrumentBridge.beginRemote(service);Handler.drain();
  check(Boolean.TRUE.equals(last(all,2008).getData().get("media_is_null")),"empty first snapshot");
  InstrumentBridge.updateRemote(service,"Song","Artist","Album",10000,3000,true);Handler.drain();
  check(last(all,2008).getData().get("media_duration") instanceof Long,"duration long");
  check(Long.valueOf(10).equals(last(all,2008).getData().get("media_duration")),"metadata duration seconds");
  check(Long.valueOf(10000).equals(last(all,2006).getData().get("media_progress_total")),"progress duration milliseconds");
  check(last(all,2008).getData().get("media_playmode") instanceof Integer,"playmode int");
  check(last(all,2007).getData().get("media_playing") instanceof Boolean,"playing boolean");
  check(meta.received.size()==2 && meta.received.get(1).what==2008,"metadata-only subscription");
  check(playing.received.size()==2 && playing.received.get(1).what==2007,"playing-only subscription");
  int metaCount=meta.received.size(), playingCount=playing.received.size();
  InstrumentBridge.updateRemote(service,"Song","Artist","Album",10000,3000,true);Handler.drain();
  check(meta.received.size()==metaCount && playing.received.size()==playingCount,"unchanged snapshot avoids duplicate metadata/status");
  InstrumentBridge.updateRemote(service,"New Song","Artist","Album",10000,3000,true);
  InstrumentBridge.updateRemote(service,"New Song","Artist","Album",10000,3000,true);Handler.drain();
  check("New Song".equals(last(meta,2008).getData().get("media_name")),"coalesced elapsed update keeps pending metadata change");
  SystemClock.now=2000;Handler.tick();
  check(Long.valueOf(5000).equals(last(all,2006).getData().get("media_progress_current")),"progress extrapolation");
  SystemClock.now=99999;Handler.tick();
  check(Long.valueOf(10000).equals(last(all,2006).getData().get("media_progress_current")),"progress clamp");
  InstrumentBridge.updateRemote(service,"Song","Artist","Album",10000,4200,false);Handler.drain();
  SystemClock.now+=5000;request(1007,meta);
  check(Long.valueOf(4200).equals(last(meta,2008).getData().get("media_duration_current")),"paused position frozen");
  request(1019,all);request(1020,all);
  check(Boolean.FALSE.equals(last(all,2016).getData().get("is_first")),"first boolean");
  check(Boolean.FALSE.equals(last(all,2017).getData().get("is_last")),"last boolean");
  request(1001,all);check(GuardRuntime.lastCommand==4,"next");request(1002,all);check(GuardRuntime.lastCommand==5,"previous");
  check(Integer.valueOf(0).equals(last(all,4016).getData().get("next_result")),"next queue accepted int");
  check(Integer.valueOf(0).equals(last(all,4015).getData().get("pre_result")),"previous queue accepted int");
  request(1003,all);check(GuardRuntime.lastCommand==1,"play");
  check(Integer.valueOf(0).equals(last(all,4018).getData().get("play_result")),"play queue accepted int");
  request(1004,all);check(Integer.valueOf(0).equals(last(all,4019).getData().get("pause_result")),"pause queue accepted int");
  request(1005,all);check(GuardRuntime.lastCommand==2,"stop pause");
  GuardRuntime.accepted=false;int rejectBefore=all.received.size();request(1003,all);
  check(all.received.size()==rejectBefore,"rejection does not fabricate success");GuardRuntime.accepted=true;
  check(!request(1009,all),"favorites preserved");check(!request(1017,all),"list preserved");
  Message nativeState=Message.obtain();nativeState.what=2008;int before=all.received.size();
  InstrumentBridge.sendNative(all,nativeState);check(all.received.size()==before,"delayed native metadata suppressed");
  Message unrelated=Message.obtain();unrelated.what=2022;InstrumentBridge.sendNative(all,unrelated);
  check(all.received.size()==before+1,"unrelated reply preserved");
  int pendingBefore=all.received.size();InstrumentBridge.updateRemote(service,"Old","","",1000,0,true);
  GuardRuntime.guarded=false;GuardRuntime.revision++;InstrumentBridge.restoreLocal();Handler.drain();
  check(all.received.size()==pendingBefore,"stale remote runnable rejected");
  check(service.metadata==2 && service.playing==2,"native query by subscription without play");
  InstrumentBridge.sendNative(all,nativeState);check(all.received.size()==pendingBefore+1,"native restored");
  // Dead clients must not remain in subsequent session publications.
  meta.binder.die();Handler.drain();int deadBefore=meta.received.size();
  GuardRuntime.guarded=true;GuardRuntime.revision++;InstrumentBridge.beginRemote(service);Handler.drain();
  check(meta.received.size()==deadBefore,"binder death pruning");
  InstrumentBridge.updateRemote(service,"Units","","",9000,1200,false);Handler.drain();
  check(Long.valueOf(9).equals(last(all,2008).getData().get("media_duration")),"9000ms metadata duration is9seconds");
  check(Long.valueOf(9000).equals(last(all,2006).getData().get("media_progress_total")),"9000ms progress total remains9000");
  InstrumentBridge.beginRemote(service);Handler.drain();
  long currentRevision=GuardRuntime.revision;
  check(!InstrumentBridge.updateRemote(service,"Stale","","",1000,0,true,currentRevision-1),"old origin ticket rejected");
  InstrumentBridge.beginRemote(service,currentRevision-1);InstrumentBridge.restoreLocal(currentRevision-1);Handler.drain();
  request(1007,all);check("".equals(last(all,2008).getData().get("media_name")),"old origin cannot reset new session");
  Handler.delayed.clear();
  InstrumentBridge.updateRemote(service,"","","",0,0,true);Handler.drain();
  check(Handler.delayed.isEmpty(),"no progress timer without song metadata");
  InstrumentBridge.onDestroy(service);GuardRuntime.guarded=false;GuardRuntime.revision++;
  InstrumentBridge.restoreLocal();Handler.drain();check(service.metadata==2,"no restore to destroyed service");
  System.out.println("InstrumentBridge behavior verified");
 }
}''',
}

class InstrumentBehaviorTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java toolchain unavailable')
    def test_real_bridge_protocol_lifecycle_and_progress(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for rel, content in STUBS.items():
                path=root/rel;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
            source=ROOT/'patch-src/com/tencent/wecarflow/carplayguard/InstrumentBridge.java'
            subprocess.run(['javac','-d',str(root),str(source)]+[str(root/p) for p in STUBS],check=True,capture_output=True,text=True)
            result=subprocess.run(['java','-cp',str(root),'BridgeHarness'],capture_output=True,text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('behavior verified',result.stdout)

if __name__=='__main__':unittest.main()
