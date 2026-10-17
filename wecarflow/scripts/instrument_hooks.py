"""Verified native widget hooks for backup/WeCarFlow.apk (2.7.1.36971303).

Returns content only; the caller controls atomic validation and writes.
"""
import re
from pathlib import Path

BASE = Path('smali_classes3/com/tencent/wecarflow/launcherwidget')
BRIDGE = 'Lcom/tencent/wecarflow/carplayguard/InstrumentBridge;'
SERVICE = 'Lcom/tencent/wecarflow/launcherwidget/UpdateWidgetService;'
MARKER = '# wecarflow-carplay-instrument-v1'


def _method(text, signature):
    matches = list(re.finditer(r'^\.method [^\n]* ' + re.escape(signature) + r'\n.*?^\.end method', text, re.M | re.S))
    if len(matches) != 1:
        raise ValueError('instrument expected exactly one method: ' + signature)
    return matches[0].group()


def _after_locals(body, code):
    match = re.search(r'^    \.locals (\d+)\n', body, re.M)
    if not match or int(match[1]) < 1:
        raise ValueError('instrument hook requires native local v0')
    return body[:match.end()] + '\n    ' + MARKER + '\n' + code + body[match.end():]


def patch_instrument(root):
    root = Path(root)
    service_path = BASE / 'UpdateWidgetService.smali'
    handler_path = BASE / 'UpdateWidgetService$y.smali'
    original = (root / service_path).read_text()
    handler = (root / handler_path).read_text()
    # Exact original signatures plus verified protocol fields. Reject drift before mutating.
    for signature in ('onCreate()V', 'onDestroy()V', 'f(Landroid/os/Messenger;)V',
                      'o(Landroid/os/Messenger;)V', 'k(Landroid/os/Messenger;)V',
                      'l(Landroid/os/Messenger;)V', 'a(JJ)V'):
        _method(original, signature)
    checks = {'f(Landroid/os/Messenger;)V': '0x7d8',
              'o(Landroid/os/Messenger;)V': '"media_playing"',
              'k(Landroid/os/Messenger;)V': '"is_first"',
              'l(Landroid/os/Messenger;)V': '"is_last"',
              'a(JJ)V': '"media_progress_current"'}
    for signature, needle in checks.items():
        if needle not in _method(original, signature):
            raise ValueError('instrument native payload drift: ' + signature)
    body = _method(handler, 'handleMessage(Landroid/os/Message;)V')
    patched_service = MARKER in original
    patched_handler = MARKER in handler
    if patched_service != patched_handler:
        raise ValueError('instrument partial patch detected; restore pristine baseline')
    if patched_service:
        if original.count(BRIDGE + '->sendNative') != 8 or handler.count(BRIDGE + '->handle') != 1:
            raise ValueError('instrument incomplete existing patch')
        return {str(service_path): original, str(handler_path): handler}
    if '.locals 5' not in body or 'const/16 v1, 0xbb9' not in body or '.packed-switch 0x3e9' not in body:
        raise ValueError('instrument native dispatcher drift')
    code = ('    invoke-static {p1}, ' + BRIDGE + '->handle(Landroid/os/Message;)Z\n'
            '    move-result v0\n'
            '    if-eqz v0, :carplay_instrument_native\n'
            '    return-void\n'
            '    :carplay_instrument_native\n')
    handler = handler.replace(body, _after_locals(body, code), 1)
    for signature, hook in [('onCreate()V', 'onCreate'), ('onDestroy()V', 'onDestroy')]:
        body = _method(original, signature)
        anchor = '    invoke-super {p0}, Landroid/app/Service;->' + signature
        if body.count(anchor) != 1:
            raise ValueError('instrument lifecycle super drift: ' + signature)
        code = ('    ' + MARKER + '\n    invoke-static {p0}, ' + BRIDGE + '->' + hook + '(Landroid/content/Context;)V')
        # Capture context before teardown; attach only after base service initialization.
        new = body.replace(anchor, anchor + '\n\n' + code if hook == 'onCreate' else code + '\n\n' + anchor, 1)
        original = original.replace(body, new, 1)
    send = r'invoke-virtual (\{[^}\n]+\}), Landroid/os/Messenger;->send\(Landroid/os/Message;\)V'
    original, count = re.subn(send, lambda m: 'invoke-static ' + m[1] + ', ' + BRIDGE + '->sendNative(Landroid/os/Messenger;Landroid/os/Message;)V', original)
    if count != 8:
        raise ValueError('instrument expected 8 native send sites, found ' + str(count))
    return {str(service_path): original, str(handler_path): handler}
