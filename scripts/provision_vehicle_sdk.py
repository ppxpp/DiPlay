#!/usr/bin/env python3
"""Provision the PAL client locally without tracking proprietary SDK binaries."""
import argparse
import hashlib
from pathlib import Path
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("pal_dex", type=Path, help="wecarflow-pal-classes2.dex from the analyzed QQMusic assets")
args = parser.parse_args()
if not args.pal_dex.is_file() or not args.pal_dex.read_bytes().startswith(b"dex\n"):
    parser.error("PAL SDK must be an existing DEX file")
root = Path(__file__).resolve().parents[1]
target = root / ".private/vehicle-runtime-assets/vehicle/pal-client.dex"
target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(args.pal_dex, target)
print(f"PAL SDK provisioned: {target}")
print(f"SHA256: {hashlib.sha256(target.read_bytes()).hexdigest()}")
