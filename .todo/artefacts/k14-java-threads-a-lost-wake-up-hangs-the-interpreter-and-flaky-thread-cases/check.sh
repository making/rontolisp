#!/usr/bin/env bash
# Runs the thread cases of java-spec.yaml under load on the interpreter and counts the
# copies that printed what the case expects (OK), something else (DIFF) or never ended
# (HUNG): N JVMs at once (default 12), each running two copies of every case on threads
# of its own (Check.java). Needs python3 with yaml.
#   ./mvnw compile && .todo/artefacts/k14-*/check.sh [N]
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
out=$(mktemp -d)
javac -cp "$root/target/classes" -d "$out" "$here/Check.java"
python3 - "$root/src/test/resources/java-spec.yaml" "$out" <<'PY'
import sys, yaml
spec, out = sys.argv[1], sys.argv[2]
names = []
for case in yaml.safe_load(open(spec))['standalone']:
    source, file = case['source'], case['file']
    threads = 'Thread' in source or 'java.util.concurrent' in source
    plain = not (case.get('stdin') or case.get('files') or case.get('error')) and file.endswith('.java') and '/' not in file
    if threads and plain and 'stdout' in case:
        open(out + '/' + file, 'w').write(source)
        open(out + '/' + file + '.expected', 'w').write(case['stdout'])
        names.append(out + '/' + file)
open(out + '/names', 'w').write(' '.join(names))
PY
files=$(cat "$out/names")
uptime
for i in $(seq 1 "${1:-12}"); do
  java -cp "$root/target/classes:$out" Check $files $files > "$out/log.$i" 2>&1 &
done
wait
uptime
cat "$out"/log.* | grep -o '^OK\|^DIFF\|^HUNG' | sort | uniq -c
cat "$out"/log.* | grep '^DIFF\|^HUNG' | sort | uniq -c | sort -rn | head -20
