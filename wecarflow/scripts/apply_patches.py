#!/usr/bin/env python3
"""Validate WeCarFlow inputs, compile the companion, and apply exact patches."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
ANDROID = '{http://schemas.android.com/apk/res/android}'
BASE_SHA = json.loads((ROOT/'baseline.json').read_text())['apkSha256']
MARK = '\n    <!-- WECARFLOW_GUARD_BEGIN -->'


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def latest(paths):
    paths = list(paths)
    if not paths:
        raise RuntimeError('Android SDK tool not found')
    return max(paths, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', str(p))))


def sdk_tools():
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home()/'Library/Android/sdk'))))
    baseline = json.loads((ROOT/'baseline.json').read_text())
    bt = Path(os.environ['ANDROID_BUILD_TOOLS_DIR']) if os.environ.get('ANDROID_BUILD_TOOLS_DIR') else sdk/'build-tools'/baseline['buildToolsVersion']
    android_jar = sdk/'platforms'/baseline['androidPlatform']/'android.jar'
    if not android_jar.is_file() or not (bt/'d8').is_file():
        raise RuntimeError('Install pinned SDK platform/build-tools from baseline.json')
    return android_jar, bt


def patch_manifest(source):
    clean = re.sub(r'\n    <!-- WECARFLOW_GUARD_BEGIN -->.*?<!-- WECARFLOW_GUARD_END -->\n', '', source, flags=re.S)
    tree = ET.fromstring(clean)
    if tree.attrib.get('package') != 'com.tencent.wecarflow':
        raise RuntimeError('Unexpected application package')
    app = tree.find('application')
    if app is None or app.get(ANDROID+'process') not in (None, 'com.tencent.wecarflow'):
        raise RuntimeError('Unexpected application main process')
    if 'com.tencent.wecarflow.carplayguard.' in clean:
        raise RuntimeError('Existing companion declarations outside generated block')
    peers = sorted({''} | {node.get(ANDROID+'process') for node in app if node.get(ANDROID+'process')})
    declarations = [
        '<service android:name="com.tencent.wecarflow.carplayguard.PlaybackGuardService" android:process=":carplayguard" android:enabled="true" android:exported="true"/>',
        '<provider android:name="com.tencent.wecarflow.carplayguard.GuardStateProvider" android:authorities="com.tencent.wecarflow.carplayguard" android:process=":carplayguard" android:exported="false" android:initOrder="1000"/>']
    for i, peer in enumerate(peers):
        declarations.append('<provider android:name="com.tencent.wecarflow.carplayguard.GuardPeer'+str(i)+'" android:authorities="com.tencent.wecarflow.carplayguard.peer'+str(i)+'" '+('android:process='+quoteattr(peer)+' ' if peer else '')+'android:exported="false" android:initOrder="1000"/>')
    block = MARK+'\n'+''.join('        '+s+'\n' for s in declarations)+'    <!-- WECARFLOW_GUARD_END -->\n'
    if clean.count('    </application>') != 1:
        raise RuntimeError('Expected one application closing tag')
    result = clean.replace('    </application>', block+'    </application>')
    ET.fromstring(result)
    return result, peers


def compile_companion(peers, destination):
    android_jar, bt = sdk_tools()
    with tempfile.TemporaryDirectory(prefix='wecarflow-guard-') as tmp:
        temp = Path(tmp)
        sources = temp/'src'
        shutil.copytree(ROOT/'patch-src', sources)
        package = sources/'com/tencent/wecarflow/carplayguard'
        config = 'package com.tencent.wecarflow.carplayguard;\nfinal class GuardConfig {\n'
        for name, values in [('PEER_AUTHORITIES', ['com.tencent.wecarflow.carplayguard.peer'+str(i) for i in range(len(peers))]), ('PEER_PROCESSES', peers)]:
            config += 'static final String[] '+name+' = new String[] {'+', '.join(json.dumps(v) for v in values)+'};\n'
        config += '}\n'
        (package/'GuardConfig.java').write_text(config)
        for i in range(len(peers)):
            (package/('GuardPeer'+str(i)+'.java')).write_text('package com.tencent.wecarflow.carplayguard;\npublic final class GuardPeer'+str(i)+' extends GuardStateProvider {}\n')
        classes, dex = temp/'classes', temp/'dex'
        classes.mkdir(); dex.mkdir()
        run('javac', '-source', '8', '-target', '8', '-bootclasspath', android_jar, '-d', classes, *sorted(sources.rglob('*.java')))
        run(bt/'d8', '--lib', android_jar, '--min-api', str(json.loads((ROOT/'baseline.json').read_text())['minimumApi']), '--output', dex, *sorted(classes.rglob('*.class')))
        import zipfile
        apk = temp/'patch.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.write(dex/'classes.dex', 'classes.dex')
        run('apktool', 'd', '-f', '--no-res', apk, '-o', temp/'decoded')
        shutil.copytree(temp/'decoded/smali/com/tencent/wecarflow/carplayguard', destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apktool-dir', type=Path, default=ROOT/'apktool')
    parser.add_argument('--compile-only', action='store_true')
    args = parser.parse_args()
    from playback_hooks import patch_playback
    from instrument_hooks import patch_instrument
    target = args.apktool_dir.resolve()
    patches = patch_playback(target)
    instrument = patch_instrument(target)
    if patches.keys() & instrument.keys():
        raise RuntimeError('Overlapping patch ownership')
    patches.update(instrument)
    manifest, peers = patch_manifest((target/'AndroidManifest.xml').read_text())
    patches['AndroidManifest.xml'] = manifest
    application_path = 'smali/com/tencent/wecarflow/FlowApplication.smali'
    application = (target/application_path).read_text()
    marker = '# diplay-bridge-only-application-v1'
    if marker not in application:
        anchor = '.method public onCreate()V\n    .locals 3\n'
        if application.count(anchor) != 1:
            raise RuntimeError('FlowApplication onCreate drift')
        hook = ('\n    '+marker+'\n'
                '    invoke-static {p0}, Lcom/tencent/wecarflow/carplayguard/BridgeBootstrap;->isBridgeProcess(Landroid/content/Context;)Z\n'
                '    move-result v0\n    if-eqz v0, :diplay_normal_application\n'
                '    invoke-super {p0}, Lcom/tencent/wecar/base/BaseApplication;->onCreate()V\n'
                '    return-void\n    :diplay_normal_application\n')
        application = application.replace(anchor, anchor+hook, 1)
    for lifecycle in ('onLowMemory', 'onTerminate'):
        lifecycle_marker = '# diplay-bridge-only-'+lifecycle
        if lifecycle_marker not in application:
            anchor_lifecycle = '.method public '+lifecycle+'()V\n    .locals 2\n'
            if application.count(anchor_lifecycle) != 1:
                raise RuntimeError('FlowApplication lifecycle drift: '+lifecycle)
            hook_lifecycle = ('\n    '+lifecycle_marker+'\n'
                '    invoke-static {p0}, Lcom/tencent/wecarflow/carplayguard/BridgeBootstrap;->isBridgeProcess(Landroid/content/Context;)Z\n'
                '    move-result v0\n    if-eqz v0, :diplay_normal_'+lifecycle+'\n'
                '    invoke-super {p0}, Landroid/app/Application;->'+lifecycle+'()V\n'
                '    return-void\n    :diplay_normal_'+lifecycle+'\n')
            application = application.replace(anchor_lifecycle, anchor_lifecycle+hook_lifecycle, 1)
    patches[application_path] = application
    # A version update is explicit release metadata, never copied from QQMusic.
    release = json.loads((ROOT/'release.json').read_text())
    if not re.fullmatch(r'\d+(?:\.\d+)+', release['versionName']):
        raise RuntimeError('WeCarFlow network requires numeric versionName; use patchRevision for adaptation labels')
    yml = (target/'apktool.yml').read_text()
    yml = re.sub(r'(^\s*versionCode:\s*)\d+', lambda m: m[1]+str(release['versionCode']), yml, flags=re.M)
    yml = re.sub(r'(^\s*versionName:\s*).*', lambda m: m[1]+release['versionName'], yml, flags=re.M)
    patches['apktool.yml'] = yml
    # Validate/compile everything before touching the target.
    original_hashes = {p: hashlib.sha256((target/p).read_bytes()).hexdigest() for p in patches}
    with tempfile.TemporaryDirectory(prefix='wecarflow-smali-') as temp:
        generated = Path(temp)/'carplayguard'
        compile_companion(peers, generated)
        if args.compile_only:
            print('Validated hooks and Java/d8/smali; target unchanged')
            return
        out = target/'smali_classes4/com/tencent/wecarflow/carplayguard'
        if out.exists():
            shutil.rmtree(out)
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(generated, out)
        for path, source in patches.items():
            (target/path).write_text(source)
    report_path = ROOT/'build/patch-report.json'
    report_path.parent.mkdir(exist_ok=True)
    previous = json.loads(report_path.read_text()) if report_path.exists() else {}
    report = {'baselineSha256': BASE_SHA, 'peerProcesses': peers,
              'protocol': 'com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1',
              'originalHashes': previous.get('originalHashes', original_hashes),
              'patchedHashes': {p: hashlib.sha256((target/p).read_bytes()).hexdigest() for p in patches}}
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print('Applied', len(patches), 'validated files; companion generated under', out)

if __name__ == '__main__':
    main()
