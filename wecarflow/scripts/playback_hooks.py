"""Validated, in-memory playback patches for the WeCarFlow 2.7.1 baseline."""
from pathlib import Path
import re

PREFIX = 'smali_classes3/com/tencent/wecarflow/'
RUNTIME = 'Lcom/tencent/wecarflow/carplayguard/GuardRuntime;'
# Exact method signatures; blocked void operations return without side effects.
HOOKS = {
    'play/k.smali': ['public s()V', 'public c(I)V', 'public a(ILjava/lang/String;)V',
                     'private a(IZ)V'],
    'play/l.smali': ['protected w()V'],
    'x0/h.smali': ['public e()V', 'public g()V', 'public a(Ljava/lang/String;)V',
                    'public a(Ljava/lang/String;I)V', 'public a(Ljava/lang/String;Ljava/util/List;)V'],
    'x0/m.smali': ['public g()V', 'public h()V', 'public c(I)V',
                    'private e(I)V', 'private p()V', 'private q()V',
                    'public a(Ljava/lang/String;Ljava/lang/String;)V',
                    'private c(Lcom/tencent/wecarflow/x0/m$o;)V',
                    'public a(Lcom/tencent/wecarflow/x0/m$o;Ljava/lang/String;)I',
                    'public a(Lcom/tencent/wecarflow/x0/m$o;)V',
                    'public a(IZILcom/tencent/wecarflow/x0/j;)V',
                    'public a(Ljava/lang/String;IZZ)V',
                    'public a(Ljava/lang/String;Ljava/util/List;)V',
                    'public a(Ljava/util/List;)V',
                    'private e(Ljava/util/List;Lcom/tencent/wecarflow/x0/m$j;)V',
                    'public a(Ljava/util/List;Lcom/tencent/wecarflow/x0/m$j;)V',
                    'public b(Ljava/util/List;Lcom/tencent/wecarflow/x0/m$j;)V',
                    'public c(Ljava/util/List;Lcom/tencent/wecarflow/x0/m$j;)V'],
    's0/b/f.smali': ['public e(Z)V', 'private a(Landroid/os/Message;)V', 'C()V'],
    's0/b/g.smali': ['public i()V'],
    's0/c/d/b.smali': ['public start()V', 'private j()V'],
    's0/c/f/c.smali': ['public start()V'],
    's0/c/f/c$b.smali': ['public onPrepared(Lcom/tencent/thumbplayer/api/ITPPlayer;)V'],
    'player/MediaPlaybackService$k.smali': [
        'public a()V', 'public b()V', 'public onPlay()V',
        'public onPlayFromMediaId(Ljava/lang/String;Landroid/os/Bundle;)V',
        'public onPlayFromSearch(Ljava/lang/String;Landroid/os/Bundle;)V',
        'public onPlayFromUri(Landroid/net/Uri;Landroid/os/Bundle;)V',
        'public onSkipToNext()V', 'public onSkipToPrevious()V',
        'public onSkipToQueueItem(J)V'],
}
SELECTIVE = {
    'w0/b$a.smali': [('public a(Lcom/tencent/tai/pal/input/CabinKeyEvent;)V', 'pal', 'p1'),
                      ('public b(Lcom/tencent/tai/pal/input/CabinKeyEvent;)V', 'pal', 'p1')],
    'media/service/MediaButtonIntentReceiver.smali': [
        ('public onReceive(Landroid/content/Context;Landroid/content/Intent;)V', 'intent', 'p2')],
    'player/MediaPlaybackService$k.smali': [
        ('public onMediaButtonEvent(Landroid/content/Intent;)Z', 'intent', 'p1')],
}
REGISTRATIONS = {
    's0/c/d/b.smali': 'public constructor <init>()V',
    's0/c/f/c.smali': 'private constructor <init>(Z)V',
}

TICKETS = {
    's0/c/d/b.smali': [('public setDataSource(Ljava/lang/String;)V', 'mark'),
                         ('public start()V', 'start'), ('private j()V', 'prepared')],
    's0/c/f/c.smali': [('public setDataSource(Ljava/lang/String;)V', 'mark'),
                         ('public start()V', 'start')],
    's0/c/f/c$b.smali': [('public onPrepared(Lcom/tencent/thumbplayer/api/ITPPlayer;)V', 'owner')],
}


# Per-request instances, unlike permanent listeners, retain their origin revision.
CALLBACKS = {
    'x0/m$a.smali': ('constructor <init>(Lcom/tencent/wecarflow/x0/m;Ljava/lang/String;I)V',
                     ['public b(I)V', 'public onFinish()V']),
    'x0/m$i.smali': ('constructor <init>(Lcom/tencent/wecarflow/x0/m;Lcom/tencent/wecarflow/x0/m$o;)V',
                     ['public a()V', 'public a(Lcom/tencent/wecarflow/net/network/bean/ServerErrorMessage;)V']),
    'play/l$d.smali': ('constructor <init>(Lcom/tencent/wecarflow/play/l;I)V', ['public run()V']),
    'play/l$e.smali': ('constructor <init>(Lcom/tencent/wecarflow/play/l;I)V', ['public run()V']),
    'play/l$h.smali': ('constructor <init>(Lcom/tencent/wecarflow/play/l;)V', ['public a()V', 'public a(I)V']),
    'play/l$b$a.smali': ('constructor <init>(Lcom/tencent/wecarflow/play/l$b;Ljava/lang/String;I)V', ['public run()V']),
}
for suffix, response in {
    'c': 'AudioBookSecondDetailResponseBean', 'd': 'RadioProgramResponseBean',
    'e': 'MixedFlowDetailsResponseBean', 'f': 'SingerDetailResponseBean',
    'g': 'HistoryMusicResponseBean', 'h': 'like/LikeSongResponse', 'l': 'PlayListResponseBean',
}.items():
    CALLBACKS[f'x0/m${suffix}.smali'] = (
        'constructor <init>(Lcom/tencent/wecarflow/x0/m;Lcom/tencent/wecarflow/x0/m$j;)V',
        [f'public a(Lcom/tencent/wecarflow/net/network/bean/{response};I)V', 'public b()V'])


def _callback_body(body):
    match = re.search(r'^    \.locals (\d+)\n', body, re.M)
    if not match:
        raise ValueError('Callback requires .locals directive')
    if int(match.group(1)) == 0:
        # Only p registers are used, so adding v0 leaves every original operand intact.
        if re.search(r'\bv[0-9]+\b', body):
            raise ValueError('Cannot safely extend callback locals with explicit v registers')
        body = body[:match.start()] + '    .locals 1\n' + body[match.end():]
    code = (f'    # carplayguard callback origin\n'
            f'    invoke-static {{p0}}, {RUNTIME}->shouldBlockPrepared(Ljava/lang/Object;)Z\n'
            '    move-result v0\n    if-eqz v0, :carplayguard_callback_continue\n'
            '    return-void\n    :carplayguard_callback_continue\n')
    return _entry(body, code)


def _method(text, signature):
    pattern = re.compile(r'^\.method ' + re.escape(signature) + r'\n.*?^\.end method', re.M | re.S)
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one method: {signature}; found {len(matches)}')
    return matches[0]


def _entry(body, code):
    locals_match = re.search(r'^    \.locals (\d+)\n', body, re.M)
    if not locals_match or int(locals_match.group(1)) < 1:
        raise ValueError('Hook requires an existing local register')
    return body[:locals_match.end()] + '\n' + code + body[locals_match.end():]


def _guard(signature):
    return_code = '    return-void\n' if signature.endswith('V') else '    const/4 v0, -0x1\n    return v0\n'
    return (f'    # carplayguard playback gate\n    invoke-static {{}}, {RUNTIME}->shouldBlock()Z\n'
            '    move-result v0\n    if-eqz v0, :carplayguard_continue\n' + return_code +
            '    :carplayguard_continue\n')


def _selective(signature, kind, parameter):
    code = f'    # carplayguard selective media gate\n    invoke-static {{}}, {RUNTIME}->shouldBlock()Z\n'
    code += '    move-result v0\n    if-eqz v0, :carplayguard_continue\n'
    code += f'    if-eqz {parameter}, :carplayguard_continue\n'
    if kind == 'pal':
        code += f'    invoke-virtual {{{parameter}}}, Lcom/tencent/tai/pal/input/CabinKeyEvent;->getKeyCode()I\n'
        values = (5, 8, 9)
    else:
        code += ('    const-string v0, "android.intent.extra.KEY_EVENT"\n'
                 f'    invoke-virtual {{{parameter}, v0}}, Landroid/content/Intent;->getParcelableExtra(Ljava/lang/String;)Landroid/os/Parcelable;\n'
                 '    move-result-object v0\n    check-cast v0, Landroid/view/KeyEvent;\n'
                 '    if-eqz v0, :carplayguard_continue\n'
                 '    invoke-virtual {v0}, Landroid/view/KeyEvent;->getKeyCode()I\n')
        # Pause (127), stop (86) and volume keys pass through unchanged.
        values = (79, 85, 87, 88, 126)
    code += '    move-result v0\n'
    for value in values:
        code += f'    const/16 v1, 0x{value:x}\n    if-eq v0, v1, :carplayguard_block\n'
    code += '    goto :carplayguard_continue\n    :carplayguard_block\n'
    code += ('    const/4 v0, 0x1\n    return v0\n' if signature.endswith('Z') else '    return-void\n')
    return code + '    :carplayguard_continue\n'


def patch_playback(root):
    """Return relative-path -> patched text after validating the entire input set.

    Does not write files. An already patched exact method is preserved, making
    build retries idempotent. Missing files/signatures fail before any caller writes.
    """
    root = Path(root)
    names = set(HOOKS) | set(SELECTIVE) | set(REGISTRATIONS) | set(TICKETS) | set(CALLBACKS)
    data = {name: (root / (PREFIX + name)).read_text() for name in sorted(names)}
    for name, text in data.items():
        signatures = HOOKS.get(name, []) + [v[0] for v in SELECTIVE.get(name, [])]
        signatures += [v[0] for v in TICKETS.get(name, [])]
        if name in REGISTRATIONS:
            signatures += [REGISTRATIONS[name]]
        for signature in signatures:
            match = _method(text, signature)
            if '# carplayguard' not in match.group():
                m = re.search(r'^    \.locals (\d+)', match.group(), re.M)
                required = 2 if signature in [v[0] for v in SELECTIVE.get(name, [])] else 1
                if not m or int(m.group(1)) < required:
                    raise ValueError(f'{name}: insufficient locals: {signature}')
    for name, (constructor, callbacks) in CALLBACKS.items():
        _method(data[name], constructor)
        for signature in callbacks:
            body = _method(data[name], signature).group()
            if '# carplayguard callback origin' not in body:
                _callback_body(body)  # Validate allocation before producing any patch.
    for name, text in data.items():
        operations = [(s, _guard(s)) for s in HOOKS.get(name, [])]
        operations += [(s, _selective(s, k, p)) for s, k, p in SELECTIVE.get(name, [])]
        for signature, code in operations:
            match = _method(text, signature)
            body = match.group()
            if '# carplayguard' not in body:
                text = text[:match.start()] + _entry(body, code) + text[match.end():]
        for signature, kind in TICKETS.get(name, []):
            match = _method(text, signature)
            body = match.group()
            if '# carplayguard playback revision' in body:
                continue
            code = '    # carplayguard playback revision\n'
            if kind in ('mark', 'start'):
                code += f'    invoke-static {{p0}}, {RUNTIME}->markPlayback(Ljava/lang/Object;)V\n'
                if kind == 'start':
                    anchor = '    :carplayguard_continue\n'
                    if body.count(anchor) != 1:
                        raise ValueError(f'{name}: missing unique accepted-start gate')
                    body = body.replace(anchor, anchor + code, 1)
                else:
                    body = _entry(body, code)
            else:
                parameter = 'p0'
                if kind == 'owner':
                    code += '    iget-object v0, p0, Lcom/tencent/wecarflow/s0/c/f/c$b;->a:Lcom/tencent/wecarflow/s0/c/f/c;\n'
                    parameter = 'v0'
                code += (f'    invoke-static {{{parameter}}}, {RUNTIME}->shouldBlockPrepared(Ljava/lang/Object;)Z\n'
                         '    move-result v0\n    if-eqz v0, :carplayguard_revision_continue\n'
                         '    return-void\n    :carplayguard_revision_continue\n')
                body = _entry(body, code)
            text = text[:match.start()] + body + text[match.end():]
        if name in REGISTRATIONS:
            match = _method(text, REGISTRATIONS[name])
            body = match.group()
            if '# carplayguard register player' not in body:
                code = ('    # carplayguard register player\n'
                        f'    invoke-static {{p0}}, {RUNTIME}->register(Ljava/lang/Object;)V\n\n')
                body = body.replace('    return-void', code + '    return-void')
                text = text[:match.start()] + body + text[match.end():]
        if name in CALLBACKS:
            constructor, callbacks = CALLBACKS[name]
            match = _method(text, constructor)
            body = match.group()
            if '# carplayguard callback capture' not in body:
                code = ('    # carplayguard callback capture\n'
                        f'    invoke-static/range {{p0 .. p0}}, {RUNTIME}->markPlayback(Ljava/lang/Object;)V\n')
                body = body.replace('    return-void', code + '    return-void')
                text = text[:match.start()] + body + text[match.end():]
            for signature in callbacks:
                match = _method(text, signature)
                body = match.group()
                if '# carplayguard callback origin' not in body:
                    text = text[:match.start()] + _callback_body(body) + text[match.end():]
        data[name] = text
    return {PREFIX + name: text for name, text in data.items()}
