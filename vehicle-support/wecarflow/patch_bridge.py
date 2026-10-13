"""Exact source changes for the existing two bridge classes; no bridge means no remote display."""
from pathlib import Path

MARK = '// CARPLAY_GUARD_BRIDGE_V1'

def replace(source, old, new):
    if source.count(old) != 1: raise RuntimeError('Bridge source mismatch: '+old[:90])
    return source.replace(old,new,1)

def patch_sources(directory):
    cluster = directory/'com/tencent/wecarflow/launcherwidget/ClusterMediaBridge.java'
    widget = directory/'com/tencent/wecarflow/launcherwidget/UpdateWidgetService.java'
    if not cluster.is_file() or not widget.is_file(): return {}
    c=cluster.read_text(); w=widget.read_text()
    if MARK in c and MARK in w: return {cluster:c,widget:w}
    if MARK in c or MARK in w: raise RuntimeError('Partially patched bridge sources')
    c=replace(c,'public final class ClusterMediaBridge {', MARK+'\npublic final class ClusterMediaBridge {')
    c=replace(c,'        if (durationMs <= 0L) {\n            durationMs = 240000L;\n        }\n','')
    c=replace(c,'durationMs, 0L, true);','Math.max(0L, durationMs), 0L, UpdateWidgetService.localPlaying());')
    w=replace(w,'public class UpdateWidgetService extends Service {',MARK+'\npublic class UpdateWidgetService extends Service {')
    w=replace(w,'    private Track currentTrack = new Track("qqmusic-empty", "", "", "", "", 240000L);','''    private Track currentTrack = new Track("qqmusic-empty", "", "", "", "", 0L);
    private Track localTrack = currentTrack;
    private long localProgress;
    private boolean localPlaying;
    private boolean remoteActive;
    private static volatile RemoteSnapshot pendingRemote;''')
    w=replace(w,'    private boolean playing = true;', '    private boolean playing = false;')
    w=replace(w,'        instance = this;', '''        instance = this;
        RemoteSnapshot pending = pendingRemote;
        if (pending != null && com.tencent.wecarflow.carplayguard.GuardRuntime.isGuarded()) applyRemote(pending);''')
    w=replace(w,'long durationMs = Math.max(1000L, intent.getLongExtra(EXTRA_DURATION_MS, 240000L));','long durationMs = Math.max(0L, intent.getLongExtra(EXTRA_DURATION_MS, 0L));')
    w=replace(w,'        currentTrack = new Track(id, title, artist, album, cover, durationMs);','''        localTrack = new Track(id, title, artist, album, cover, durationMs);
        localProgress = incomingProgress;
        localPlaying = incomingPlaying;
        if (com.tencent.wecarflow.carplayguard.GuardRuntime.isGuarded()) return;
        currentTrack = localTrack;''')
    w=replace(w,'        progressMs = Math.max(0L, intent.getLongExtra(EXTRA_PROGRESS_MS, progressMs));','''        localProgress = Math.max(0L, intent.getLongExtra(EXTRA_PROGRESS_MS, localProgress));
        if (com.tencent.wecarflow.carplayguard.GuardRuntime.isGuarded()) return;
        progressMs = localProgress;''')
    w=replace(w,'        playing = intent.getBooleanExtra(EXTRA_PLAYING, playing);','''        localPlaying = intent.getBooleanExtra(EXTRA_PLAYING, localPlaying);
        if (com.tencent.wecarflow.carplayguard.GuardRuntime.isGuarded()) return;
        playing = localPlaying;''')
    w=replace(w,'        bundle.putInt("media_index", 1);\n        bundle.putBoolean("is_first", false);\n        bundle.putBoolean("is_last", false);','''        if (!remoteActive) {
            bundle.putInt("media_index", 1);
            bundle.putBoolean("is_first", false);
            bundle.putBoolean("is_last", false);
        }''')
    w=replace(w,'        return Math.min(Math.max(0L, current), currentTrack.durationMs);','        return currentTrack.durationMs > 0 ? Math.min(Math.max(0L, current), currentTrack.durationMs) : Math.max(0L, current);')
    # Remote queue position/boundaries are unknown; omit those keys in query replies too.
    # Above nested buildMediaBundle line differs in indentation; replace exact reply blocks.
    w=replace(w,'        message.what = MSG_CURRENT_INDEX_RESULT;\n        Bundle bundle = new Bundle();\n        bundle.putInt("media_index", 1);','        message.what = MSG_CURRENT_INDEX_RESULT;\n        Bundle bundle = new Bundle();\n        if (!remoteActive) bundle.putInt("media_index", 1);')
    w=replace(w,'        message.what = MSG_IS_FIRST_RESULT;\n        Bundle bundle = new Bundle();\n        bundle.putBoolean("is_first", false);','        message.what = MSG_IS_FIRST_RESULT;\n        Bundle bundle = new Bundle();\n        if (!remoteActive) bundle.putBoolean("is_first", false);')
    w=replace(w,'        message.what = MSG_IS_LAST_RESULT;\n        Bundle bundle = new Bundle();\n        bundle.putBoolean("is_last", false);','        message.what = MSG_IS_LAST_RESULT;\n        Bundle bundle = new Bundle();\n        if (!remoteActive) bundle.putBoolean("is_last", false);')
    w=replace(w,'        int what = source.what;','''        int what = source.what;
        if (com.tencent.wecarflow.carplayguard.GuardRuntime.isGuarded()) {
            int command, resultWhat = 0;
            String resultKey = null;
            switch (what) {
                case MSG_CONTROL_NEXT: command = 4; resultWhat = MSG_NEXT_RESULT; resultKey = "next_result"; break;
                case MSG_CONTROL_PREVIOUS: command = 5; resultWhat = MSG_PREVIOUS_RESULT; resultKey = "pre_result"; break;
                case MSG_CONTROL_PLAY: command = 1; resultWhat = MSG_PLAY_RESULT; resultKey = "play_result"; break;
                case MSG_CONTROL_PAUSE: command = 2; resultWhat = MSG_PAUSE_RESULT; resultKey = "pause_result"; break;
                case MSG_CONTROL_STOP: command = 2; break;
                default: return;
            }
            boolean accepted = com.tencent.wecarflow.carplayguard.GuardRuntime.forwardMediaCommand(command);
            sendControlResult(source.replyTo, resultWhat, resultKey, accepted ? 0 : -1);
            return;
        }''')
    w=replace(w,'    private void dispatchMediaButton(int keyCode) {','''    private void dispatchMediaButton(int keyCode) {
        if (com.tencent.wecarflow.carplayguard.GuardRuntime.isGuarded()) {
            int command;
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) command = 1;
            else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_STOP) command = 2;
            else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) command = 4;
            else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) command = 5;
            else return;
            com.tencent.wecarflow.carplayguard.GuardRuntime.forwardMediaCommand(command);
            return;
        }''')
    w=replace(w,'    private final class IncomingHandler extends Handler {','''    public static boolean localPlaying() {
        UpdateWidgetService service = instance;
        return service != null && service.localPlaying;
    }

    public static boolean updateRemote(final Context context, String title, String artist, String album,
            long duration, long elapsed, boolean isPlaying) {
        if (context == null || !com.tencent.wecarflow.carplayguard.GuardRuntime.isGuarded()) return false;
        final RemoteSnapshot snapshot = new RemoteSnapshot(title, artist, album, duration, elapsed, isPlaying);
        pendingRemote = snapshot;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if (!com.tencent.wecarflow.carplayguard.GuardRuntime.isGuarded() || pendingRemote != snapshot) return;
                UpdateWidgetService service = instance;
                if (service != null) service.applyRemote(snapshot);
                else {
                    try { start(context); }
                    catch (RuntimeException e) { Log.e(TAG, "Remote bridge start failed", e); }
                }
            }
        });
        return true; // Accepted publication request; Messenger delivery is asynchronous.
    }

    private void applyRemote(RemoteSnapshot snapshot) {
        remoteActive = true;
        currentTrack = snapshot.track;
        progressMs = snapshot.elapsed;
        progressStartedAt = SystemClock.uptimeMillis();
        playing = snapshot.playing;
        publishCurrentMediaToTargets(false);
        publishPlayingToClients();
        publishProgressToClients();
        scheduleProgressLoop();
    }

    public static void restoreLocal() {
        pendingRemote = null;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if (com.tencent.wecarflow.carplayguard.GuardRuntime.isGuarded()) return;
                UpdateWidgetService service = instance;
                if (service == null) return;
                service.remoteActive = false;
                service.currentTrack = service.localTrack;
                service.progressMs = service.localProgress;
                service.progressStartedAt = SystemClock.uptimeMillis();
                service.playing = service.localPlaying;
                service.publishCurrentMediaToTargets(false);
                service.publishPlayingToClients();
                service.publishProgressToClients();
            }
        });
    }

    private static final class RemoteSnapshot {
        final Track track;
        final long elapsed;
        final boolean playing;
        RemoteSnapshot(String title, String artist, String album, long duration, long progress, boolean playing) {
            track = new Track("carplay-remote", title, artist, album, "", Math.max(0, duration));
            elapsed = Math.max(0, progress);
            this.playing = playing;
        }
    }

    private final class IncomingHandler extends Handler {''')
    return {cluster:c,widget:w}

BUILD_MARK = '# CARPLAY_GUARD_BUILD_V1'

def patch_build_script(path):
    if not path.is_file(): raise RuntimeError('Existing bridge build script missing: '+str(path))
    source = path.read_text()
    if BUILD_MARK in source: return source
    javac = 'javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$CLASSES_DIR" $(find "$SRC_DIR" -name \'*.java\' | sort)'
    replacement = '''# CARPLAY_GUARD_BUILD_V1
GUARD_API_JAR="$QQMUSIC_DIR/build/carplayguard/guard-api.jar"
typeset -a GUARD_JAVAC_ARGS GUARD_D8_ARGS
GUARD_JAVAC_ARGS=()
GUARD_D8_ARGS=()
if [[ -f "$GUARD_API_JAR" ]]; then
  GUARD_JAVAC_ARGS=(-classpath "$GUARD_API_JAR")
  GUARD_D8_ARGS=(--classpath "$GUARD_API_JAR")
elif grep -rq 'com.tencent.wecarflow.carplayguard.GuardRuntime' "$SRC_DIR"; then
  echo "错误: CarPlay guard bridge 已补丁，但缺少 $GUARD_API_JAR；请先重新运行 DiPlay vehicle-support/wecarflow/apply_guard.py" >&2
  exit 1
fi
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" "${GUARD_JAVAC_ARGS[@]}" -d "$CLASSES_DIR" $(find "$SRC_DIR" -name '*.java' | sort)'''
    source = replace(source,javac,replacement)
    source = replace(source,'"$D8" --lib "$ANDROID_JAR" --min-api 21 --output "$DEX_DIR"',
                     '"$D8" --lib "$ANDROID_JAR" "${GUARD_D8_ARGS[@]}" --min-api 21 --output "$DEX_DIR"')
    return source
