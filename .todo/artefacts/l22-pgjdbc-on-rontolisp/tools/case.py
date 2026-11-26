# case.py SPEC NAME OUT-DIR: writes a corpus case as files: a regular case as its main
# class's file (the imports ahead of its source), a standalone one as its file and files;
# prints the path of the file to run and writes expected.txt beside it.
import os, sys, yaml
spec, name, out = sys.argv[1], sys.argv[2], sys.argv[3]
MARKS = {'\x85': '\ue085', '\u2028': '\ue028', '\u2029': '\ue029'}
text = open(spec, encoding='utf-8').read()
for k, v in MARKS.items():
    text = text.replace(k, v)
data = yaml.safe_load(text)
def back(t):
    for k, v in MARKS.items():
        t = t.replace(v, k)
    return t
os.makedirs(out, exist_ok=True)
for c in data.get('cases', []):
    if c['name'] == name:
        path = os.path.join(out, c['main'] + '.java')
        open(path, 'w', encoding='utf-8').write(back(data['imports'] + c['source']))
        open(os.path.join(out, 'expected.txt'), 'w', encoding='utf-8').write(back(c['expected']))
        print(path); sys.exit(0)
for c in data.get('standalone', []):
    if c['name'] == name:
        path = os.path.join(out, c['file'])
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, 'w', encoding='utf-8').write(back(c['source']))
        for f, text in (c.get('files') or {}).items():
            p = os.path.join(out, f); os.makedirs(os.path.dirname(p), exist_ok=True); open(p, 'w', encoding='utf-8').write(back(text))
        open(os.path.join(out, 'expected.txt'), 'w', encoding='utf-8').write(back(c['stdout']))
        print(path); sys.exit(0)
sys.exit('no case ' + name)
