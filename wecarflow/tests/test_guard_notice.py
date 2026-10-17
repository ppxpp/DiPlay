"""Execute the actual notice callbacks with deterministic Android window/lifecycle doubles."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
'android/content/Context.java': 'package android.content; public class Context {}',
'android/content/DialogInterface.java': '''package android.content; public interface DialogInterface {
 interface OnClickListener {void onClick(DialogInterface d,int w);}
 interface OnDismissListener {void onDismiss(DialogInterface d);}
}''',
'android/os/Bundle.java': 'package android.os; public class Bundle {}',
'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
 static List<Runnable> q=new ArrayList<>(); public Handler(Looper l){}
 public void post(Runnable r){q.add(r);} public void removeCallbacks(Runnable r){while(q.remove(r)){} }
 public static void drain(){while(!q.isEmpty())q.remove(0).run();}
}''',
'android/util/Log.java': 'package android.util; public class Log {public static int w(String t,String m,Throwable e){return 0;}}',
'android/util/DisplayMetrics.java': 'package android.util; public class DisplayMetrics {public float density=1;}',
'android/content/res/Resources.java': 'package android.content.res; public class Resources {public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}}',
'android/app/Activity.java': '''package android.app; public class Activity extends android.content.Context {
 public boolean finishing,destroyed,rotating;
 public boolean isFinishing(){return finishing;} public boolean isDestroyed(){return destroyed;}
 public boolean isChangingConfigurations(){return rotating;}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
}''',
'android/app/Application.java': '''package android.app; import android.os.Bundle; public class Application {
 public ActivityLifecycleCallbacks callbacks; public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){callbacks=c;}
 public interface ActivityLifecycleCallbacks {
 void onActivityStarted(Activity a);void onActivityResumed(Activity a);void onActivityPaused(Activity a);
 void onActivityStopped(Activity a);void onActivityDestroyed(Activity a);
 void onActivityCreated(Activity a,Bundle b);void onActivitySaveInstanceState(Activity a,Bundle b);
 }
}''',
'android/widget/CompoundButton.java': '''package android.widget; public class CompoundButton {
 public interface OnCheckedChangeListener {void onCheckedChanged(CompoundButton b,boolean checked);}
}''',
'android/widget/CheckBox.java': '''package android.widget; public class CheckBox extends CompoundButton {
 public static CheckBox last; OnCheckedChangeListener listener;
 public CheckBox(android.app.Activity a){last=this;} public void setText(String s){} public void setTextSize(int n){}
 public void setOnCheckedChangeListener(OnCheckedChangeListener l){listener=l;}
 public void setChecked(boolean checked){listener.onCheckedChanged(this,checked);}
}''',
'android/widget/LinearLayout.java': '''package android.widget; public class LinearLayout {
 public LinearLayout(android.app.Activity a){} public void setPadding(int a,int b,int c,int d){}
 public void addView(CheckBox b,LayoutParams p){} public static class LayoutParams {
 public static int MATCH_PARENT=-1,WRAP_CONTENT=-2; public LayoutParams(int w,int h){} }
}''',
'android/app/AlertDialog.java': '''package android.app; import android.content.DialogInterface;
 public class AlertDialog implements DialogInterface {
 public static int shown;public static AlertDialog last;public boolean showing; OnDismissListener listener;
 public void setCanceledOnTouchOutside(boolean b){} public void setOnDismissListener(OnDismissListener l){listener=l;}
 public void show(){++shown;last=this;showing=true;}
 public void dismiss(){showing=false;if(listener!=null)listener.onDismiss(this);}
 public static class Builder {
 public Builder(Activity a){} public Builder setTitle(String s){return this;} public Builder setMessage(String s){return this;}
 public Builder setView(android.widget.LinearLayout l){return this;}
 public Builder setPositiveButton(String s,OnClickListener l){return this;}public AlertDialog create(){return new AlertDialog();}
 }
}''',
'com/tencent/wecarflow/carplayguard/GuardRuntime.java': '''package com.tencent.wecarflow.carplayguard;
 public class GuardRuntime {static boolean guarded;public static boolean isGuarded(){return guarded;}}
''',
}
HARNESS = '''package com.tencent.wecarflow.carplayguard;
import android.app.*;import android.os.Handler;import android.widget.CheckBox;
public class GuardNoticeTest {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static void leave(Application.ActivityLifecycleCallbacks c,Activity a){c.onActivityPaused(a);c.onActivityStopped(a);Handler.drain();}
 static void open(Application.ActivityLifecycleCallbacks c,Activity a){c.onActivityStarted(a);c.onActivityResumed(a);Handler.drain();}
 public static void main(String[] args)throws Exception {
  Application app=new Application();GuardNotice.install(app);Object registered=app.callbacks;
  GuardNotice.install(app);check(app.callbacks==registered,"install only once");
  Application.ActivityLifecycleCallbacks c=app.callbacks;Activity page=new Activity();
  open(c,page);check(AlertDialog.shown==0,"no CarPlay/no active protection: no notice");
  GuardRuntime.guarded=true;GuardNotice.guardChanged();Handler.drain();
  check(AlertDialog.shown==1,"delayed acquire while visible shows notice");
  AlertDialog.last.dismiss();check(GuardRuntime.guarded,"close preserves guard");
  c.onActivityResumed(page);Handler.drain();check(AlertDialog.shown==1,"resume same page does not repeat");
  Activity another=new Activity();c.onActivityPaused(page);c.onActivityStarted(another);
  c.onActivityResumed(another);c.onActivityStopped(page);Handler.drain();
  check(AlertDialog.shown==1,"internal page navigation does not repeat");
  leave(c,another);open(c,another);check(AlertDialog.shown==2,"reopen existing page shows again");
  CheckBox.last.setChecked(true);AlertDialog.last.dismiss();
  leave(c,another);open(c,new Activity());check(AlertDialog.shown==2,"suppressed for this process");
  check(GuardRuntime.guarded,"suppression preserves guard");
  GuardRuntime.guarded=false;GuardNotice.guardChanged();Handler.drain();
  GuardRuntime.guarded=true;GuardNotice.guardChanged();Handler.drain();
  check(AlertDialog.shown==2,"reconnect does not clear process suppression");
  java.lang.reflect.Field singleton=GuardNotice.class.getDeclaredField("instance");
  singleton.setAccessible(true);singleton.set(null,null); // Model a fresh app process.
  Application fresh=new Application();GuardNotice.install(fresh);Activity newPage=new Activity();
  open(fresh.callbacks,newPage);check(AlertDialog.shown==3,"restart restores notice when protection is active");
  GuardRuntime.guarded=false;GuardNotice.guardChanged();Handler.drain();
  check(!AlertDialog.last.showing,"disconnect dismisses obsolete notice");
  leave(fresh.callbacks,newPage);open(fresh.callbacks,newPage);
  check(AlertDialog.shown==3,"unprotected reopen has no notice");
  System.out.println("GuardNotice lifecycle/foreground/suppression checks passed");
 }
}
'''

class GuardNoticeTest(unittest.TestCase):
    def test_actual_lifecycle_callbacks_and_process_suppression(self):
        with tempfile.TemporaryDirectory(prefix='wecarflow-notice-test-') as temp:
            base = Path(temp)
            for name, source in STUBS.items():
                target = base/name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(source)
            source_dir = ROOT/'patch-src/com/tencent/wecarflow/carplayguard'
            harness = base/'com/tencent/wecarflow/carplayguard/GuardNoticeTest.java'
            harness.write_text(HARNESS)
            subprocess.run(['javac', '-d', str(base),
                            *map(str, base.rglob('*.java')),
                            str(source_dir/'GuardNotice.java'), str(source_dir/'GuardNoticePolicy.java')], check=True)
            subprocess.run(['java', '-cp', str(base), 'com.tencent.wecarflow.carplayguard.GuardNoticeTest'], check=True)

if __name__ == '__main__':
    unittest.main()
