import shutil
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from instrument_hooks import BASE, BRIDGE, patch_instrument

WORK = Path(__file__).resolve().parents[1]
ROOT = WORK / 'build/baseline' if (WORK / 'build/baseline').is_dir() else WORK / 'apktool'

class InstrumentHooksTest(unittest.TestCase):
    def test_dispatch_native_service_and_idempotence(self):
        changes = patch_instrument(ROOT)
        self.assertEqual(2, len(changes))
        service = changes[str(BASE / 'UpdateWidgetService.smali')]
        handler = changes[str(BASE / 'UpdateWidgetService$y.smali')]
        self.assertIn('.super Landroid/app/Service;', service)
        self.assertEqual(8, service.count(BRIDGE + '->sendNative'))
        self.assertNotIn('Landroid/os/Messenger;->send(Landroid/os/Message;)V', service)
        self.assertLess(handler.index(BRIDGE + '->handle'), handler.index('isAgreedUserProtocol'))
        self.assertIn('CONTROL_ADD_FAVOR', handler)
        self.assertIn('CONTROL_GET_LIST', handler)
        with tempfile.TemporaryDirectory() as d:
            for rel, content in changes.items():
                p = Path(d) / rel
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(content)
            self.assertEqual(changes, patch_instrument(d))

    def test_protocol_drift_and_partial_patch_fail_closed(self):
        changes = patch_instrument(ROOT)
        with tempfile.TemporaryDirectory() as d:
            for rel in changes:
                p = Path(d) / rel
                p.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(ROOT / rel, p)
            p = Path(d) / BASE / 'UpdateWidgetService.smali'
            text = p.read_text()
            p.write_text(text.replace('"media_playing"', '"unexpected_type"'))
            with self.assertRaisesRegex(ValueError, 'payload drift'):
                patch_instrument(d)
            p.write_text(changes[str(BASE / 'UpdateWidgetService.smali')])
            # Native handler left untouched must not silently produce a partial build.
            with self.assertRaisesRegex(ValueError, 'partial patch'):
                patch_instrument(d)

if __name__ == '__main__': unittest.main()
