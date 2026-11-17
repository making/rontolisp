#!/bin/bash
# usage: runsj.sh N prog.star jar..: N wall times of each build's class, in turn
n=$1; f=$2; shift 2; p=$(basename $f .star)
for j in "$@"; do b=$(basename $j .jar); mkdir -p rj-$p-$b; [ -f rj-$p-$b/Prog.class ] || java -jar $j $f -o rj-$p-$b/Prog.class --class-name Prog 2>/dev/null; done
declare -A t
for k in $(seq $n); do for j in "$@"; do b=$(basename $j .jar); t[$b]="${t[$b]} $( { cd rj-$p-$b; /usr/bin/time -f '%e' java -Xss64m Prog > /dev/null; } 2>&1 | tail -1)"; done; done
for j in "$@"; do b=$(basename $j .jar); echo "$p $b:${t[$b]}"; done
