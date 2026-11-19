# The code bytes of a wasm module by what they belong to, one column a file.
# usage: by-owner.py <a.sizes> [<b.sizes> ...]   (the .sizes files measure.sh writes)
import collections
import re
import sys

PACKAGES = ('java.time', 'java.math', 'java.text', 'java.sql', 'java.util.regex', 'java.util.stream',
            'java.util.function', 'java.util.concurrent', 'java.util', 'java.net', 'java.io', 'java.nio')


def rows(path):
    out = []
    for line in open(path, encoding='utf-8', errors='replace'):
        m = re.match(r'\[func-size\] (\d+)\t(\d+)\t(.*)', line.rstrip('\n'))
        if m:
            out.append((int(m.group(1)), m.group(3)))
    return out


def owner(name):
    if name.startswith('am.ik.yavi.'):
        return 'YAVI'
    for package in PACKAGES:
        if name.startswith(package + '.'):
            return 'library ' + package
    if name.startswith('rontolisp.internal.'):
        return 'library java.lang'
    if re.match(r'(yavi|shelf|tag)\.', name):
        return 'the program'
    if name.startswith('_dispatch'):
        return 'dispatch (_dispatch_*)'
    if name.startswith('_toplevel_chunk'):
        return 'top-level chunks'
    if name.startswith('_lambda_'):
        return 'lambdas'
    if 'REGEX' in name:
        return 'runtime: regex'
    if 'URI' in name:
        return 'runtime: URI'
    # a class of java.lang is printed without its package
    if (re.match(r'[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)*([.%]|$)', name) and not name.isupper()
            and not name.startswith('FUNC_')):
        return 'library java.lang'
    return 'runtime: the rest'


totals = []
for path in sys.argv[1:]:
    size = collections.Counter()
    count = collections.Counter()
    for bytes_, name in rows(path):
        size[owner(name)] += bytes_
        count[owner(name)] += 1
    totals.append((size, count))
owners = sorted(set().union(*[size for size, _ in totals]), key=lambda o: -totals[0][0][o])
for o in owners:
    print(' '.join(f'{size[o]:9,d} ({count[o]:4d})' for size, count in totals), ' ', o)
print(' '.join(f'{sum(size.values()):9,d} ({sum(count.values()):4d})' for size, count in totals), '  total')
