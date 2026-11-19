#!/usr/bin/env python3
# refused.py File.java: the lines of a probe the front end refuses, each commented out
# in a copy until the program lowers -- how the inference was asked what it could not
# find. JAR is the exec jar; the copy goes under TMPDIR.
import os, re, subprocess, sys, tempfile
jar = os.path.realpath(os.environ.get('JAR', 'target/rontolisp-0.1.0-SNAPSHOT-exec.jar'))
source = sys.argv[1]
name = os.path.basename(source)
copy = os.path.join(tempfile.mkdtemp(), name)
lines = open(source).read().split('\n')
found = 0
for _ in range(200):
    open(copy, 'w').write('\n'.join(lines))
    run = subprocess.run(['java', '-jar', jar, copy, '--dump-ir'], capture_output=True, text=True)
    at = re.search(r'error: .*?' + re.escape(name) + r':(\d+):(\d+): (.*)', run.stdout + run.stderr)
    if not at:
        break
    line = int(at.group(1))
    print(f'{line}: {at.group(3)}\n    {lines[line - 1].strip()}', flush=True)
    lines[line - 1] = '// ' + lines[line - 1]
    found += 1
print(found, 'refused; what is left:', copy)
