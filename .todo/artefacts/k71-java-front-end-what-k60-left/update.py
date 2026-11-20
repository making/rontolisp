# update.py YAML CASE NEWOUT: replaces the case's stdout/expected block with NEWOUT's text
import sys
path, name, newout = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(path).read().split('\n')
idx = lines.index('  - name: ' + name)
i = idx + 1
while not (lines[i] in ('    stdout: |', '    expected: |')):
    i += 1
start = i + 1
j = start
while j < len(lines) and (lines[j].startswith('      ') or lines[j].strip() == ''):
    j += 1
# keep trailing blank lines out of the block
while j > start and lines[j - 1].strip() == '':
    j -= 1
new = ['      ' + l if l else '' for l in open(newout).read().rstrip('\n').split('\n')]
lines[start:j] = new
open(path, 'w').write('\n'.join(lines))
print(name, 'replaced', j - start, 'lines with', len(new))
