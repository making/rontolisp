#!/usr/bin/env bash
# Shows the hang without the test suite -- a scheduled pool or a timer reading the clock
# twice (.kb/java-frontend.md, "java.util.concurrent"), fixed 2026-11-19, so every JVM
# leaves 0 now: 24 JVMs at once, each running four copies of Timed.java (java-spec.yaml's
# a-scheduled-pool-and-a-timer-run-tasks-at-their-times, as of 2026-11-19 before the fix)
# on threads of its own. Prints how many JVMs left 0, 1, 2 .. copies waiting after 25 s.
#   ./mvnw compile && .todo/artefacts/k14-*/run.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
out=$(mktemp -d)
javac -cp "$root/target/classes" -d "$out" "$here/InProc.java"
for _ in $(seq 1 24); do
  java -cp "$root/target/classes:$out" InProc "$here/Timed.java" "$here/Timed.java" "$here/Timed.java" "$here/Timed.java" 2>&1 | grep -c HUNG &
done | sort | uniq -c
wait
