#!/usr/bin/env python3
"""Run the Starlark conformance suite through the prototype on the four backends."""
import re, subprocess, sys, os, glob, warnings
warnings.simplefilter('ignore')
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
SUITE = os.environ['SUITE']  # bazelbuild/starlark's test_suite/testdata
from sl2lisp import translate, emit, StaticError
RT = open(os.path.join(HERE, 'starlark-rt.lisp')).read()

def chunks(path, impl):
    code, expected = [], []
    for line in open(path, encoding='utf-8'):
        line = line.rstrip()
        if line == '---':
            yield ''.join(code), expected
            code, expected = [], []
            continue
        m = re.fullmatch(r'(.*?) *### *((go|java|rust):)? *(.*)', line)
        if m:
            if not m.group(3) or m.group(3) == impl:
                expected.append(m.group(4))
            code.append(m.group(1))
        else:
            code.append(line)
        code.append('\n')
    yield ''.join(code), expected

def build(path, impl):
    out, meta = [RT], []
    for n, (src, expected) in enumerate(chunks(path, impl)):
        try:
            forms = translate(src, f'c{n}%')
        except (StaticError, SyntaxError, KeyError) as e:
            meta.append((expected, f'{type(e).__name__}: {e}'))
            continue
        meta.append((expected, None))
        out.append('(setq %sl-err nil)')
        for kind, form in forms:
            text = emit(form)
            if kind == 'stmt':
                text = f'(if (null %sl-err) (handler-case {text} (error (e) (setq %sl-err (format nil "~a" e)))))'
            out.append(text)
        out.append(f'(%sl-chunk-report {n} %sl-err)')
    return '\n'.join(out) + '\n', meta

def judge(meta, text):
    res, asserts = {}, {}
    pending = []
    for line in text.splitlines():
        m = re.match(r'CHUNK (\d+) (OK|ERROR)(.*)', line)
        if m:
            res[int(m.group(1))] = (m.group(2), m.group(3).strip(), pending)
            pending = []
        else:
            pending.append(line)
    ok, bad = 0, []
    for n, (expected, static) in enumerate(meta):
        if static is not None:
            if expected: ok += 1
            else: bad.append((n, 'static: ' + static))
            continue
        if n not in res:
            bad.append((n, 'no report (crash?)')); continue
        status, msg, lines = res[n]
        if expected:
            if status == 'ERROR': ok += 1
            else: bad.append((n, 'expected error: ' + expected[0]))
        elif status == 'ERROR':
            bad.append((n, 'error: ' + msg))
        elif lines:
            bad.append((n, lines[0]))
        else:
            ok += 1
    return ok, bad

total = passed = 0
files = sorted(glob.glob(SUITE + '/*/*.star')) if len(sys.argv) < 2 else sys.argv[1:]
for path in files:
    impl = os.path.basename(os.path.dirname(path))
    name = impl + '-' + os.path.basename(path)[:-5]
    prog, meta = build(path, impl)
    open(name + '.lisp', 'w').write(prog)
    r = subprocess.run([os.path.join(HERE, 'run4.sh'), name + '.lisp'], capture_output=True, text=True)
    same = r.stdout.count(': same')
    ok, bad = judge(meta, open(f'out-{name}/int.txt').read())
    total += len(meta); passed += ok
    print(f'{name}: {ok}/{len(meta)} chunks pass; backends identical to interpreter: {same}/3')
    for line in r.stdout.splitlines():
        if ': same' not in line: print('    ' + line)
    for n, why in bad[:int(os.environ.get('SHOW', '3'))]:
        print(f'    chunk {n}: {why[:150]}')
    sys.stdout.flush()
print(f'TOTAL {passed}/{total}')
