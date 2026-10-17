import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class PatchBuildTest(unittest.TestCase):
    def test_manifest_compatible_service_and_internal_peer(self):
        spec = importlib.util.spec_from_file_location('apply_patches', ROOT/'scripts/apply_patches.py')
        self.assertTrue(spec.origin and Path(spec.origin).exists(), 'build patch pipeline is missing')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        manifest = (ROOT/'build/baseline/AndroidManifest.xml').read_text()
        text, peers = module.patch_manifest(manifest)
        self.assertEqual(peers, ['', ':coreService'])
        self.assertEqual(text.count('android:name="com.tencent.wecarflow.carplayguard.PlaybackGuardService"'), 1)
        self.assertIn('android:authorities="com.tencent.wecarflow.carplayguard.peer0"', text)
        self.assertEqual(module.patch_manifest(text), (text, peers))
        import xml.etree.ElementTree as ET
        android = '{http://schemas.android.com/apk/res/android}'
        app = ET.fromstring(text).find('application')
        service = next(n for n in app.findall('service') if n.get(android+'name').endswith('PlaybackGuardService'))
        self.assertEqual(service.get(android+'process'), ':carplayguard')
        central = next(n for n in app.findall('provider') if n.get(android+'authorities') == 'com.tencent.wecarflow.carplayguard')
        self.assertEqual(central.get(android+'process'), ':carplayguard')
        main = next(n for n in app.findall('provider') if n.get(android+'authorities') == 'com.tencent.wecarflow.carplayguard.peer0')
        self.assertIsNone(main.get(android+'process'))
        self.assertIn('android:name="com.tencent.wecarflow.launcherwidget.UpdateWidgetService"', text)
        self.assertNotIn('android:permission=', text[text.index('<!-- WECARFLOW_GUARD_BEGIN'):])

if __name__ == '__main__':
    unittest.main()
