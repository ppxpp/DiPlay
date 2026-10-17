import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'playback_hooks.py'

class PlaybackHooksTests(unittest.TestCase):
    def test_selective_guards_and_atomic_idempotent_patch(self):
        self.assertTrue(MODULE.exists(), 'playback adapter must exist')
        spec = importlib.util.spec_from_file_location('playback_hooks', MODULE)
        hooks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(hooks)
        root = Path(__file__).resolve().parents[2] / 'apktool'
        patches = hooks.patch_playback(root)
        self.assertTrue(patches)
        baseline = root.parent / 'build' / 'baseline'
        if baseline.exists():
            self.assertEqual(patches, hooks.patch_playback(baseline))
        # The guard must leave volume, pause, stop and focus method bodies intact.
        preserved = {
            'play/k.smali': ['public q()V', 'public r()V', 'public b()V', 'public w()V'],
            's0/b/f.smali': ['public d(Z)V', 'public g(Z)V', 'public a()V', 'public y()V', 'public a(Z)V'],
            'player/MediaPlaybackService$k.smali': ['public onPause()V', 'public onStop()V'],
            's0/c/d/b.smali': ['public pause()V', 'public isPlaying()Z'],
            's0/c/f/c.smali': ['public pause()V', 'public isPlaying()Z'],
        }
        for name, signatures in preserved.items():
            original = (root / (hooks.PREFIX + name)).read_text()
            for signature in signatures:
                self.assertEqual(hooks._method(original, signature).group(),
                                 hooks._method(patches[hooks.PREFIX + name], signature).group())
        for name in ['s0/c/d/b.smali', 's0/c/f/c.smali']:
            start = hooks._method(patches[hooks.PREFIX + name], 'public start()V').group()
            self.assertIn('->markPlayback(Ljava/lang/Object;)V', start)
            self.assertLess(start.index('shouldBlock()Z'), start.index('markPlayback'))
        retry = hooks._method(patches[hooks.PREFIX + 's0/c/d/b.smali'], 'private j()V').group()
        self.assertIn('->shouldBlockPrepared(Ljava/lang/Object;)Z', retry)
        prepared = hooks._method(patches[hooks.PREFIX + 's0/c/f/c$b.smali'], 'public onPrepared(Lcom/tencent/thumbplayer/api/ITPPlayer;)V').group()
        self.assertIn('->shouldBlockPrepared(Ljava/lang/Object;)Z', prepared)
        for name, (constructor, callbacks) in hooks.CALLBACKS.items():
            callback_text = patches[hooks.PREFIX + name]
            self.assertIn('->markPlayback(Ljava/lang/Object;)V', hooks._method(callback_text, constructor).group())
            for signature in callbacks:
                callback_body = hooks._method(callback_text, signature).group()
                self.assertIn('->shouldBlockPrepared(Ljava/lang/Object;)Z', callback_body)
        pal = patches[hooks.PREFIX + 'w0/b$a.smali']
        self.assertIn('const/16 v1, 0x5', pal)
        self.assertIn('const/16 v1, 0x8', pal)
        self.assertIn('const/16 v1, 0x9', pal)
        self.assertIn('getKeyCode()I', pal)
        for name in ['s0/c/d/b.smali', 's0/c/f/c.smali']:
            self.assertIn('->register(Ljava/lang/Object;)V', patches[hooks.PREFIX + name])
        with tempfile.TemporaryDirectory() as directory:
            copy = Path(directory)
            for name, data in patches.items():
                target = copy / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(data)
            self.assertEqual(patches, hooks.patch_playback(copy))
            (copy / (hooks.PREFIX + 'play/k.smali')).write_text('.class invalid\n')
            with self.assertRaises(ValueError):
                hooks.patch_playback(copy)

if __name__ == '__main__':
    unittest.main()
