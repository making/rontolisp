# extract.py YAML CASE OUTDIR: writes the case's program (the corpus imports + its source)
# as Main.java in OUTDIR and its expected stdout as Main.expected.txt
import sys, os, re
path, name, out = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(path).read().split('\n')
def block(start):
    # a block scalar starting after line `start` (index), indented more than the key
    key_indent = len(lines[start]) - len(lines[start].lstrip())
    body = []
    i = start + 1
    ind = None
    while i < len(lines):
        l = lines[i]
        if l.strip() == '':
            body.append(''); i += 1; continue
        cur = len(l) - len(l.lstrip())
        if cur <= key_indent:
            break
        if ind is None:
            ind = cur
        body.append(l[ind:])
        i += 1
    while body and body[-1] == '':
        body.pop()
    return '\n'.join(body) + '\n', i
imports, _ = block(lines.index('imports: |'))
idx = lines.index('  - name: ' + name)
main = None
src = expected = None
i = idx + 1
while i < len(lines) and not lines[i].startswith('  - name:'):
    l = lines[i]
    if l.startswith('    main: '):
        main = l.split(': ', 1)[1].strip()
    if l.startswith('    file: '):
        main = l.split(': ', 1)[1].strip()[:-5]
    if l == '    source: |':
        src, i = block(i); continue
    if l == '    stdout: |' or l == '    expected: |':
        expected, i = block(i); continue
    i += 1
os.makedirs(out, exist_ok=True)
text = (imports if not lines[idx+1].startswith('    file:') else '') + src
open(os.path.join(out, main + '.java'), 'w').write(text)
open(os.path.join(out, main + '.expected.txt'), 'w').write(expected or '')
print(main)
