#!/usr/bin/env python3
"""Decode the approved private original for source-level hook tests; no APK is installed."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    config = json.loads((ROOT / 'baseline.json').read_text())
    original = Path(os.environ.get('WECARFLOW_BASE_APK', str(ROOT / '.private/WeCarFlow.apk')))
    with original.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    if digest != config['apkSha256']:
        raise RuntimeError('Original APK does not match baseline.json')
    version = subprocess.check_output(['apktool', '--version'], text=True).strip()
    if version != config['apktoolVersion']:
        raise RuntimeError('Use apktool ' + config['apktoolVersion'])
    destination = ROOT / 'build/baseline'
    destination.parent.mkdir(exist_ok=True)
    subprocess.run(['apktool', 'd', '-f', str(original), '-o', str(destination)], check=True)
    print('Prepared test baseline:', destination)


if __name__ == '__main__':
    main()
