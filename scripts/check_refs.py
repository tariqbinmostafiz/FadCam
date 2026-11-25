#!/usr/bin/env python3
"""Fail-fast check: every R.<type>.<name> used in Java/Kotlin and every @type/name used in
res XML must be defined in the app's own resources. Catches 'cannot find symbol R.id.x' and
AAPT 'resource not found' errors before the (slow) Gradle build.

Usage: python3 scripts/check_refs.py [--root DIR] [--baseline FILE] [--write-baseline FILE]
  --baseline        ignore kind/name entries listed in FILE (resources provided by libraries)
  --write-baseline  write the current kind/name set to FILE and exit 0
Only app-owned resource types are checked by default (id, layout, string, drawable, mipmap,
anim, color, dimen, menu, xml, raw, plurals, array, bool, integer, attr) because style/theme
names may legitimately come from libraries (Material, AppCompat).
"""
import os, re, sys

ARGS = sys.argv[1:]
def opt(name, default=None):
    return ARGS[ARGS.index(name) + 1] if name in ARGS else default

ROOT = os.path.abspath(opt('--root', os.path.join(os.path.dirname(__file__), '..')))
BASELINE = opt('--baseline')   # file with "kind/name" lines that are known library-provided
WRITE_BASELINE = opt('--write-baseline')
RES = os.path.join(ROOT, 'app/src/main/res')
SRC = os.path.join(ROOT, 'app/src/main/java')
CHECK = {'id', 'layout', 'string', 'drawable', 'mipmap', 'anim', 'color', 'dimen', 'menu', 'xml',
         'raw', 'plurals', 'array', 'bool', 'integer', 'attr', 'font', 'animator'}
FILE_DIR_TYPES = {'layout', 'drawable', 'mipmap', 'anim', 'menu', 'xml', 'raw', 'font', 'animator', 'color'}

defined = {t: set() for t in CHECK | {'style'}}

def norm(n):
    return n.replace('.', '_')

# ---- definitions ----
for d in sorted(os.listdir(RES)):
    p = os.path.join(RES, d)
    if not os.path.isdir(p):
        continue
    t = d.split('-')[0]
    for f in os.listdir(p):
        stem = f.rsplit('.', 1)[0]
        if t in FILE_DIR_TYPES:
            defined[t].add(norm(stem))
        if f.endswith('.xml'):
            txt = open(os.path.join(p, f), encoding='utf-8', errors='ignore').read()
            for m in re.finditer(r'@\+id/([\w.]+)', txt):
                defined['id'].add(norm(m.group(1)))
            if t == 'values':
                for m in re.finditer(r'<(string|plurals|string-array|integer-array|array|attr|style|color|dimen|bool|integer|item|fraction|public)\b[^>]*?\bname="([\w.]+)"([^>]*)>', txt):
                    kind, name, rest = m.group(1), norm(m.group(2)), m.group(3)
                    if kind in ('string-array', 'integer-array', 'array'):
                        defined['array'].add(name)
                    elif kind == 'item':
                        mt = re.search(r'type="(\w+)"', m.group(0))
                        if mt and mt.group(1) in defined:
                            defined[mt.group(1)].add(name)
                    elif kind in defined:
                        defined[kind].add(name)
                # attrs declared inside declare-styleable
                for m in re.finditer(r'<attr\s+name="([\w.]+)"', txt):
                    defined['attr'].add(norm(m.group(1)))

# ---- usages ----
problems = []
def add(kind, name, where):
    if kind in CHECK and norm(name) not in defined[kind]:
        problems.append((kind, name, where))

for dp, _, fs in os.walk(SRC):
    for f in fs:
        if not f.endswith(('.java', '.kt')):
            continue
        path = os.path.join(dp, f)
        for i, line in enumerate(open(path, encoding='utf-8', errors='ignore'), 1):
            s = line.split('//')[0]
            for m in re.finditer(r'(?<![\w.])(?:com\.fadcam\.)?R\.(\w+)\.(\w+)', s):
                kind = m.group(1)
                if kind in ('styleable', 'style', 'attr') and kind != 'attr':
                    continue
                add(kind, m.group(2), '%s:%d' % (os.path.relpath(path, ROOT), i))

for dp, _, fs in os.walk(RES):
    for f in fs:
        if not f.endswith('.xml'):
            continue
        path = os.path.join(dp, f)
        txt = open(path, encoding='utf-8', errors='ignore').read()
        for m in re.finditer(r'(?<![\w+:])@(?!android:)(\w+)/([\w.]+)', txt):
            if m.group(0).startswith('@+'):
                continue
            line = txt.count('\n', 0, m.start()) + 1
            add(m.group(1), m.group(2), '%s:%d' % (os.path.relpath(path, ROOT), line))
        for m in re.finditer(r'\?(?!android:)attr/([\w.]+)', txt):
            line = txt.count('\n', 0, m.start()) + 1
            add('attr', m.group(1), '%s:%d' % (os.path.relpath(path, ROOT), line))

seen = set()
out = []
keys = set()
for k, n, w in problems:
    keys.add('%s/%s' % (k, n))
    if (k, n, w) in seen:
        continue
    seen.add((k, n, w))
    out.append((k, n, w))

if WRITE_BASELINE:
    open(WRITE_BASELINE, 'w').write('\n'.join(sorted(keys)) + '\n')
    print('baseline written: %d entries' % len(keys))
    sys.exit(0)

known = set()
if BASELINE and os.path.exists(BASELINE):
    known = set(l.strip() for l in open(BASELINE) if l.strip())
bad = ['MISSING %s/%s  ->  %s' % (k, n, w) for k, n, w in out if '%s/%s' % (k, n) not in known]
print('\n'.join(sorted(bad)))
print('\n%d missing reference(s) (%d known library entries ignored)' % (len(bad), len(out) - len(bad)))
sys.exit(1 if bad else 0)
