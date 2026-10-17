"""Exercise actual Android-independent protection lifecycle and pause adapters."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.tencent.wecarflow.carplayguard'

class RuntimeTest(unittest.TestCase):
    def test_lifecycle_pending_play_and_preparation_epochs(self):
        source = ROOT/'patch-src/com/tencent/wecarflow/carplayguard'
        names = ['GuardSession', 'GuardPause', 'PlaybackEpochs']
        with tempfile.TemporaryDirectory(prefix='wecarflow-runtime-test-') as temp:
            subprocess.run(['javac', '-d', temp,
                            *[str(source/(name+'.java')) for name in names],
                            *[str(ROOT/'tests/runtime'/(name+'Test.java')) for name in names]], check=True)
            for name in names:
                subprocess.run(['java', '-cp', temp, PACKAGE+'.'+name+'Test'], check=True)

if __name__ == '__main__':
    unittest.main()
