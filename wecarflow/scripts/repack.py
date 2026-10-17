#!/usr/bin/env python3
"""Build and sign WeCarFlow with the workspace-wide signing identity shared with DiPlay. Install is explicit."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from apply_patches import ROOT, BASE_SHA, sdk_tools

PRIVATE = ROOT/'.private'
SHARED = ROOT/'.signing'
BASE_APK = Path(os.environ.get('WECARFLOW_BASE_APK', str(PRIVATE/'WeCarFlow.apk')))


def read_properties(path):
    if not path.is_file():
        return {}
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip()
    return values


def signing_inputs():
    """Keystore, alias and passwords come from the shared .signing config that DiPlay also reads."""
    props = read_properties(SHARED/'signing.properties')

    def pick(variable, key, default=None):
        return os.environ.get(variable) or props.get(key) or default

    raw_keystore = pick('QQMUSIC_KEYSTORE', 'storeFile', str(SHARED/'repack.keystore'))
    keystore = Path(raw_keystore) if Path(raw_keystore).is_absolute() else ROOT/raw_keystore
    alias = pick('QQMUSIC_KEY_ALIAS', 'keyAlias')
    store_pass = pick('QQMUSIC_KEYSTORE_PASS', 'storePassword')
    key_pass = pick('QQMUSIC_KEY_PASS', 'keyPassword') or store_pass
    missing = [name for name, value in [('keyAlias', alias), ('storePassword', store_pass)] if not value]
    if missing:
        raise RuntimeError('Signing configuration is incomplete: ' + ', '.join(missing) +
                           ' unset. Provision ' + str(SHARED/'signing.properties') +
                           ' with DiPlay/scripts/provision-signing.sh.')
    return keystore, alias, store_pass, key_pass


def keystore_certificate_sha256(keystore, alias, env):
    """Certificate digest straight from the keystore, so the check does not depend on keytool's locale."""
    keytool = os.environ.get('KEYTOOL', 'keytool')
    result = subprocess.run([keytool, '-list', '-rfc', '-keystore', str(keystore), '-alias', alias,
                             '-storepass:env', 'WECARFLOW_STORE_PASS'], check=True, capture_output=True,
                            text=True, env=env)
    lines = result.stdout.splitlines()
    try:
        der = base64.b64decode(''.join(lines[lines.index('-----BEGIN CERTIFICATE-----')+1:
                                            lines.index('-----END CERTIFICATE-----')]))
    except ValueError:
        raise RuntimeError('Could not read the signing certificate from ' + str(keystore))
    return hashlib.sha256(der).hexdigest()



def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as file:
        for block in iter(lambda: file.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def run(*args, capture=False):
    return subprocess.run([str(arg) for arg in args], check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def cert(apksigner, apk):
    output = run(apksigner, 'verify', '--print-certs', apk, capture=True)
    matches = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$', output, re.M)
    if not matches:
        raise RuntimeError('APK signing certificate not found')
    return sorted(matches)


def output_path():
    release = json.loads((ROOT/'release.json').read_text())
    # Android's network-facing versionName must retain the original numeric format.
    revision = ('_'+release['patchRevision']) if release.get('patchRevision') else ''
    abi = ('_'+release['targetAbi']) if release.get('targetAbi') else ''
    return ROOT/'release'/f"{release['artifactName']}_v{release['versionName']}{revision}_{release['versionCode']}{abi}_signed.apk"


def build():
    if sha256(BASE_APK) != BASE_SHA:
        raise RuntimeError('Backup APK differs from the approved baseline')
    _, bt = sdk_tools()
    keystore, alias, store_pass, key_pass = signing_inputs()
    reference = Path(os.environ.get('QQMUSIC_REFERENCE_APK', str(PRIVATE/'qqmusic-reference.apk')))
    for path in [keystore, bt/'apksigner', bt/'zipalign']:
        if not path.is_file():
            raise RuntimeError('Missing signing input/tool: '+str(path))
    # apksigner and keytool read passwords from the environment; credentials never appear in arguments or logs.
    env = os.environ.copy()
    env['WECARFLOW_STORE_PASS'] = store_pass
    env['WECARFLOW_KEY_PASS'] = key_pass
    baseline = json.loads((ROOT/'baseline.json').read_text())
    tool_version = run('apktool', '--version', capture=True).strip()
    if tool_version != baseline['apktoolVersion']:
        raise RuntimeError('apktool version mismatch: expected '+baseline['apktoolVersion'])
    compiler = subprocess.run(['javac', '-version'], capture_output=True, text=True, check=True)
    compiler_text = (compiler.stdout + compiler.stderr).strip()
    compiler_match = re.search(r'javac (\d+)', compiler_text)
    if not compiler_match or int(compiler_match[1]) != baseline['jdkMajor']:
        raise RuntimeError('Use JDK '+str(baseline['jdkMajor'])+' from baseline.json')
    tmp = ROOT/'build/repack'
    tmp.mkdir(parents=True, exist_ok=True)
    work = tmp/'decoded'
    # Always regenerate from the approved original. Existing patched trees are inspection-only.
    run('apktool', 'd', '-f', BASE_APK, '-o', work)
    run(sys.executable, ROOT/'scripts/apply_patches.py', '--apktool-dir', work)
    unsigned, aligned = tmp/'unsigned.apk', tmp/'aligned.apk'
    result = output_path()
    result.parent.mkdir(exist_ok=True)
    if (work/'build').exists():
        shutil.rmtree(work/'build')
    run('apktool', 'b', work, '-o', unsigned)
    target_abi = json.loads((ROOT/'release.json').read_text()).get('targetAbi')
    if target_abi:
        if target_abi != 'armeabi-v7a':
            raise RuntimeError('Unsupported vehicle ABI target: '+target_abi)
        import zipfile
        filtered = tmp/'abi-filtered.apk'
        with zipfile.ZipFile(unsigned) as source, zipfile.ZipFile(filtered, 'w') as destination:
            for entry in source.infolist():
                if entry.filename.startswith('lib/') and entry.filename.split('/')[1] != target_abi:
                    continue
                destination.writestr(entry, source.read(entry.filename))
        filtered.replace(unsigned)
    run(bt/'zipalign', '-f', '-p', '4', unsigned, aligned)
    candidate = tmp/'signed.apk'
    subprocess.run([str(bt/'apksigner'), 'sign', '--ks', str(keystore), '--ks-key-alias', alias,
                    '--ks-pass', 'env:WECARFLOW_STORE_PASS', '--key-pass', 'env:WECARFLOW_KEY_PASS',
                    '--out', str(candidate), str(aligned)], env=env, check=True)
    run(bt/'apksigner', 'verify', '--verbose', candidate)
    run(bt/'zipalign', '-c', '-p', '4', candidate)
    actual, expected = cert(bt/'apksigner', candidate), keystore_certificate_sha256(keystore, alias, env)
    if actual != [expected]:
        raise RuntimeError('Signed WeCarFlow certificate '+', '.join(actual)+
                           ' differs from the shared keystore certificate '+expected)
    if reference.is_file() and actual != cert(bt/'apksigner', reference):
        print('NOTE: signing differs from the legacy reference. Compare the installed certificate; '
              'the already migrated vehicle supports in-place updates. No automatic uninstall.')
    shutil.copy2(candidate, result)
    if candidate.with_suffix('.apk.idsig').exists():
        shutil.copy2(candidate.with_suffix('.apk.idsig'), Path(str(result)+'.idsig'))
    metadata = {'apk': result.name, 'sha256': sha256(result), 'certificateSha256': actual,
                'signingKeystore': str(keystore), 'signingAlias': alias, 'baselineSha256': BASE_SHA,
                'version': json.loads((ROOT/'release.json').read_text()),
                'deviceVerified': False}
    result.with_suffix('.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n')
    print('OUTPUT:', result)
    print('SHA-256:', metadata['sha256'])
    print('Shared keystore certificate:', actual[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['build', 'install', 'all', 'print-output'], nargs='?', default='build')
    args = parser.parse_args()
    if args.mode == 'print-output':
        print(output_path()); return
    if args.mode in ['build', 'all']:
        build()
    if args.mode in ['install', 'all']:
        # No implicit uninstall, downgrade, data deletion or network rule changes.
        if not output_path().exists():
            raise RuntimeError('Build the signed APK first')
        _, bt = sdk_tools()
        run(bt/'apksigner', 'verify', output_path())
        run('adb', 'install', '-r', output_path())

if __name__ == '__main__':
    main()
