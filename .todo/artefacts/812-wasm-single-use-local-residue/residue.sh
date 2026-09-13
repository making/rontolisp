#!/bin/bash
# The two populations the item measures, off `wasm-tools print`:
#   1. adjacent `local.set N; local.get N` pairs -- what the existing peephole rule
#      would collapse to `local.tee N` if anything ran after the inliner;
#   2. single-assignment single-use locals -- the population a copy-propagation or
#      expression-sinking rule would reach. An UPPER BOUND: every one of these is
#      non-adjacent, so each needs a legality analysis before it can be removed.
# usage: residue.sh a.wasm b.wasm ...
for f in "$@"; do
  txt=$(wasm-tools print "$f" | grep -v '(data')
  pairs=$(printf '%s\n' "$txt" | awk '/local\.set/{s=$2; getline; if ($1=="local.get" && $2==s) n++} END{print n+0}')
  singles=$(printf '%s\n' "$txt" | awk '
    /^  \(func/ {for (k in s) if (s[k]==1 && g[k]==1) n++; delete s; delete g}
    /local\.set/ {s[$2]++} /local\.tee/ {s[$2]++; g[$2]++} /local\.get/ {g[$2]++}
    END {for (k in s) if (s[k]==1 && g[k]==1) n++; print n+0}')
  echo "$f adjacent-set-get=$pairs single-assign-single-use=$singles"
done
