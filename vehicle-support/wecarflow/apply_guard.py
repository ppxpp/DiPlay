#!/usr/bin/env python3
"""Compile only the guard and apply exact, idempotent smali hooks; no full APK or deployment."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from patch_bridge import patch_sources, patch_build_script

HERE = Path(__file__).resolve().parent
ANDROID = '{http://schemas.android.com/apk/res/android}'
GUARD = 'Lcom/tencent/wecarflow/carplayguard/GuardRuntime;'
MANAGER = 'smali_classes2/com/tencent/qqmusicsdk/player/playermanager/AudioPlayerManager.smali'
COMPONENT = 'smali_classes2/com/tencent/qqmusic/player/component/'
SYSTEM = 'smali_classes2/com/tencent/qqmusic/mediaplayer/system/AndroidMediaPlayer.smali'
# Signatures verified against the supplied decoded QQ version; never guess by substring at apply time.
HOOKS = {
 MANAGER: [
 'public final N0(ZI)I',
 'private final O0(Lcom/tencent/qqmusicsdk/player/playermanager/APlayer;ZI)I',
 'public final y0(Lcom/tencent/qqmusicsdk/protocol/SongInfomation;I)I',
 'public final l1(Lcom/tencent/qqmusicsdk/protocol/SongInfomation;ZZIZ)V',
 'private final g1(Lcom/tencent/qqmusicsdk/player/PlayInfoStruct;)I'],
 COMPONENT+'AndroidMediaPlayer.smali': ['public play()V'],
 COMPONENT+'EdgeThumbPlayer.smali': ['public play()V'],
 COMPONENT+'AndroidMediaPlayer$play$1.smali': ['public final invoke()V'],
 COMPONENT+'EdgeThumbPlayer$play$1.smali': ['public final invoke()V'],
 SYSTEM: ['public I()V'],
 'smali_classes2/com/tencent/qqmusicsdk/player/playlist/PlayListManager.smali': ['public B(ZI)I'],
 'smali_classes2/com/tencent/qqmusic/qplayer/core/player/playlist/MusicPlayListManager.smali': ['public final z(ZI)I', 'public final b(ZI)V'],
 'smali_classes2/com/tencent/qqmusic/qplayer/core/PlayerManagerImpl.smali': [
 'public next()I', 'public next(I)I', 'public prev()I', 'public prev(I)I',
 'public play()I', 'public play(I)I', 'public playPos(I)I', 'public playPos(IIZ)I',
 'public playSongs(Lcom/tencent/qqmusic/openapisdk/model/PlayParam;)I',
 'public playSongs(Ljava/util/List;I)I', 'public playCachedSongs(Ljava/util/List;I)I',
 'public playRadio(Ljava/util/List;Ljava/lang/String;ZLkotlin/jvm/functions/Function1;)I'],
}
REGISTER = [MANAGER, COMPONENT+'AndroidMediaPlayer.smali', COMPONENT+'EdgeThumbPlayer.smali', SYSTEM]

def run(*args):
    subprocess.run([str(a) for a in args], check=True)

def latest(paths):
    paths = list(paths)
    if not paths: raise RuntimeError('Required SDK tool missing')
    return sorted(paths, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', str(p))))[-1]

def patch_methods(source, signatures):
    for signature in signatures:
        pat = re.compile(r'(?m)^\.method '+re.escape(signature)+r'\n(?P<body>.*?)^\.end method$', re.S)
        matches = list(pat.finditer(source))
        if len(matches) != 1: raise RuntimeError('Exact method missing/duplicate: '+signature)
        match = matches[0]; body = match.group('body')
        if '# CARPLAY_GUARD_ENTRY_V1' in body: continue
        locals_match = re.search(r'(?m)^    \.locals (\d+)\s*$', body)
        if not locals_match or int(locals_match[1]) < 1:
            raise RuntimeError('Hook requires existing v0 local: '+signature)
        result = signature.rsplit(')',1)[1]
        if result == 'V': early = '    return-void\n'
        elif result == 'I': early = '    const/4 v0, -0x1\n    return v0\n'
        else: raise RuntimeError('Unsupported return type: '+signature)
        injection = ('\n    # CARPLAY_GUARD_ENTRY_V1\n    invoke-static {}, '+GUARD+'->shouldBlock()Z\n'
                     '    move-result v0\n    if-eqz v0, :carplay_guard_continue_v1\n'+early+
                     '    :carplay_guard_continue_v1\n')
        body = body[:locals_match.end()]+injection+body[locals_match.end():]
        source = source[:match.start('body')]+body+source[match.end('body'):]
    return source

def register_constructors(source):
    pat = re.compile(r'(?m)^\.method [^\n]* constructor <init>\([^\n]*\n(?P<body>.*?)^\.end method$',re.S)
    count = 0
    def replace(m):
        nonlocal count
        count += 1
        body = m['body']
        if '# CARPLAY_GUARD_REGISTER_V1' not in body:
            body, n = re.subn(r'(?m)^    return-void$',
                '    # CARPLAY_GUARD_REGISTER_V1\n    invoke-static/range {p0 .. p0}, '+GUARD+'->register(Ljava/lang/Object;)V\n    return-void',body)
            if not n: raise RuntimeError('Constructor has no normal return')
        return m[0][:m.start('body')-m.start()]+body+'.end method'
    source = pat.sub(replace,source)
    if not count: raise RuntimeError('No constructors found for player registry')
    return source

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apktool-dir', type=Path, required=True)
    parser.add_argument('--diplay-apk', type=Path, required=True)
    parser.add_argument('--extra-certificate', action='append', default=[], help='Additional SHA256, never unsigned bypass')
    parser.add_argument('--compile-only',action='store_true')
    parser.add_argument('--bridge-src',type=Path,help='Existing scripts/wecarflow_bridge_src; auto-detected from apktool directory')
    args = parser.parse_args()
    target = args.apktool_dir.resolve()
    manifest = target/'AndroidManifest.xml'
    text = manifest.read_text()
    tree = ET.fromstring(text)
    if tree.attrib.get('package') != 'com.tencent.wecarflow': raise RuntimeError('Unexpected target package')
    app = tree.find('application')
    if app.get(ANDROID+'process') not in (None, 'com.tencent.wecarflow'): raise RuntimeError('Unexpected main process')
    processes = sorted({node.get(ANDROID+'process') for node in app if node.get(ANDROID+'process')
                        and node.get(ANDROID+'name') != 'com.tencent.wecarflow.carplayguard.PlaybackGuardService'
                        and not node.get(ANDROID+'name','').startswith('com.tencent.wecarflow.carplayguard.')})
    clean_manifest = re.sub(r'\n    <!-- CARPLAY_GUARD_V1_BEGIN -->.*?<!-- CARPLAY_GUARD_V1_END -->\n','\n',text,flags=re.S)
    if 'com.tencent.wecarflow.carplayguard.PlaybackGuardService' in clean_manifest:
        raise RuntimeError('Conflicting preexisting guard service outside generated block')
    if text.count('    </application>') != 1:
        raise RuntimeError('Expected one application closing tag')
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Library/Android/sdk'))))
    android_jar = latest((sdk/'platforms').glob('*/android.jar'))
    d8 = latest((sdk/'build-tools').glob('*/d8'))
    signer = d8.parent/'apksigner'
    cert_output = subprocess.check_output([str(signer),'verify','--print-certs',str(args.diplay_apk)],text=True)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})',cert_output)
    if not certs: raise RuntimeError('No verified APK signer')
    for extra in args.extra_certificate:
        extra = extra.replace(':','').lower()
        if not re.fullmatch(r'[0-9a-f]{64}',extra): raise RuntimeError('Invalid SHA256')
        certs.append(extra)
    certs = sorted(set(c.lower() for c in certs))
    authorities = ['com.tencent.wecarflow.carplayguard.peer'+str(i) for i in range(len(processes))]
    # Validate every target before compiling or changing target files.
    patched = {}
    for path, signatures in HOOKS.items(): patched[path] = patch_methods((target/path).read_text(),signatures)
    for path in REGISTER: patched[path] = register_constructors(patched.get(path,(target/path).read_text()))
    bridge_root = args.bridge_src or target.parent.parent/'scripts/wecarflow_bridge_src'
    bridge_sources = patch_sources(bridge_root)
    bridge_build_script = target.parent.parent/'scripts/build_wecarflow_bridge_smali.sh'
    bridge_build_source = patch_build_script(bridge_build_script) if bridge_sources else None
    if not bridge_sources: print('Existing bridge sources missing: remote instrumentation explicitly unavailable')
    with tempfile.TemporaryDirectory(prefix='wecarflow-carplayguard-') as temp:
        temp = Path(temp); sources=temp/'src'; shutil.copytree(HERE/'src',sources)
        for source_path, data in bridge_sources.items():
            output = sources/source_path.relative_to(bridge_root)
            output.parent.mkdir(parents=True,exist_ok=True); output.write_text(data)
        package=sources/'com/tencent/wecarflow/carplayguard'
        config = ('package com.tencent.wecarflow.carplayguard;\nfinal class GuardConfig {\n'
                  'static final String PACKAGE = "com.gtmc.app.fengyunyx.b819";\n'
                  'static final String[] CERTIFICATES = '+array(certs)+';\n'
                  'static final String[] PEER_AUTHORITIES = '+array(authorities)+';\n'
                  'static final String[] PEER_PROCESSES = '+array(processes)+';\n'
                  'static final boolean BRIDGE_AVAILABLE = '+str(bool(bridge_sources)).lower()+';\n}\n')
        (package/'GuardConfig.java').write_text(config)
        for i in range(len(processes)):
            (package/('GuardPeer'+str(i)+'.java')).write_text('package com.tencent.wecarflow.carplayguard;\npublic final class GuardPeer'+str(i)+' extends GuardStateProvider {}\n')
        classes=temp/'classes'; dex=temp/'dex'; classes.mkdir(); dex.mkdir()
        run('javac','-source','8','-target','8','-bootclasspath',android_jar,'-d',classes,*sorted(sources.rglob('*.java')))
        run(d8,'--lib',android_jar,'--min-api','25','--output',dex,*sorted(classes.rglob('*.class')))
        import zipfile
        archive=temp/'guard.apk'
        with zipfile.ZipFile(archive,'w') as z: z.write(dex/'classes.dex','classes.dex')
        run('apktool','d','-f','--no-res',archive,'-o',temp/'decoded')
        if args.compile_only:
            print('Guard Java/d8/smali generation passed; target unchanged'); return
        # Persist only guard bytecode for the original bridge-only javac/d8 pipeline.
        api_jar = target.parent.parent/'build/carplayguard/guard-api.jar'
        api_jar.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(api_jar,'w') as z:
            for bytecode in sorted((classes/'com/tencent/wecarflow/carplayguard').glob('*.class')):
                z.write(bytecode,bytecode.relative_to(classes).as_posix())
        if bridge_build_source is not None: bridge_build_script.write_text(bridge_build_source)
        destination=target/'smali_classes4/com/tencent/wecarflow/carplayguard'
        if destination.exists(): shutil.rmtree(destination)
        shutil.copytree(temp/'decoded/smali/com/tencent/wecarflow/carplayguard',destination)
        if bridge_sources:
            bridge_smali = target/'smali_classes4/com/tencent/wecarflow/launcherwidget'
            bridge_smali.mkdir(parents=True,exist_ok=True)
            for prefix in ('ClusterMediaBridge','UpdateWidgetService'):
                for old in bridge_smali.glob(prefix+'*.smali'): old.unlink()
            for generated in (temp/'decoded/smali/com/tencent/wecarflow/launcherwidget').glob('*.smali'):
                shutil.copy2(generated,bridge_smali/generated.name)
            for path, data in bridge_sources.items(): path.write_text(data)
        for path, data in patched.items(): (target/path).write_text(data)
        # Keep unrelated XML formatting intact; replace only our exact generated block.
        text = clean_manifest
        entries = ['<service android:name="com.tencent.wecarflow.carplayguard.PlaybackGuardService" android:enabled="true" android:exported="true"/>',
                   '<provider android:name="com.tencent.wecarflow.carplayguard.GuardStateProvider" android:authorities="com.tencent.wecarflow.carplayguard" android:exported="false" android:initOrder="1000"/>']
        from xml.sax.saxutils import quoteattr
        for i, process in enumerate(processes):
            entries.append('<provider android:name="com.tencent.wecarflow.carplayguard.GuardPeer'+str(i)+'" android:authorities="'+authorities[i]+'" android:process='+quoteattr(process)+' android:exported="false" android:initOrder="1000"/>')
        block='\n    <!-- CARPLAY_GUARD_V1_BEGIN -->\n'+''.join('        '+entry+'\n' for entry in entries)+'    <!-- CARPLAY_GUARD_V1_END -->\n'
        text=text.replace('    </application>',block+'    </application>')
        ET.fromstring(text); manifest.write_text(text)
        record={'package':'com.gtmc.app.fengyunyx.b819','certificates':certs,'peerProcesses':processes,
                'hooks':HOOKS,'registeredPlayers':REGISTER,'bridgeAvailable':bool(bridge_sources)}
        (HERE/'last-applied.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
        print('Applied guard smali and '+str(sum(map(len,HOOKS.values())))+' exact playback hooks; no full APK built')

def array(values): return 'new String[] {'+', '.join(json.dumps(value) for value in values)+'}'
if __name__=='__main__': main()
