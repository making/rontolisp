# Runs a java-spec standalone case on the JDK and the four backends, each in a fresh directory.
# usage: python3 four.py <worktree> <case-name> <outdir> [legs...]
import os, subprocess, sys, yaml
wt, name, out = sys.argv[1], sys.argv[2], sys.argv[3]
legs = sys.argv[4:] or ['jdk', 'interp', 'jvm', 'wasm', 'comp']
lines = open(os.path.join(wt, 'src/test/resources/java-spec.yaml'), encoding='utf-8').read().split('\n')
start = lines.index('  - name: ' + name)
end = start + 1
while end < len(lines) and not lines[end].startswith('  - name: ') and not (lines[end][:1].isalpha()):
    end += 1
case = yaml.safe_load('\n'.join(lines[start:end]))[0]
jar = os.path.join(wt, 'target/rontolisp-0.1.0-SNAPSHOT-exec.jar')
modes = {'r': 4, 'w': 2, 'x': 1}
def bits(text):
    m = 0
    for i, ch in enumerate(text):
        if ch != '-':
            m |= modes[ch] << (6 - 3 * (i // 3))
    return m
def stage(d):
    os.makedirs(d, exist_ok=True)
    for path, text in (case.get('files') or {}).items():
        p = os.path.join(d, path)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, 'w', encoding='utf-8').write(text)
    for path, target in (case.get('links') or {}).items():
        p = os.path.join(d, path)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        os.symlink(target, p)
    for path, mode in (case.get('modes') or {}).items():
        p = os.path.join(d, path.rstrip('/'))
        if path.endswith('/'):
            os.makedirs(p, exist_ok=True)
        os.chmod(p, bits(mode))
    src = os.path.join(d, case['file'])
    open(src, 'w', encoding='utf-8').write(case['source'])
    return src
for leg in legs:
    d = os.path.join(out, leg)
    if os.path.exists(d):
        sys.exit('exists: ' + d)
    src = stage(d)
    f = case['file']
    cls = f[:-5]
    if leg == 'jdk':
        cmd = 'java -Duser.language=en -Duser.country=US %s' % f
    elif leg == 'interp':
        cmd = 'java -jar %s %s' % (jar, f)
    elif leg == 'jvm':
        cmd = 'mkdir -p ../jvmcls && java -jar %s %s -o ../jvmcls/%s.class && java -cp ../jvmcls %s' % (jar, f, cls, cls)
    elif leg == 'wasm':
        cmd = 'java -jar %s %s -o ../%s.wasm && wasmtime run --dir . ../%s.wasm' % (jar, f, cls, cls)
    else:
        cmd = 'java -jar %s %s -o ../%s-c.wasm --component && wasmtime run --dir . ../%s-c.wasm' % (jar, f, cls, cls)
    r = subprocess.run(cmd, shell=True, cwd=d, capture_output=True, text=True, timeout=600)
    open(os.path.join(out, leg + '.out'), 'w').write(r.stdout)
    open(os.path.join(out, leg + '.err'), 'w').write(r.stderr)
    print(leg, r.returncode, len(r.stdout.splitlines()))
open(os.path.join(out, 'expected.out'), 'w').write(case['stdout'])
