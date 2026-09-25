#!/usr/bin/env bash
# usage: bench.sh ROUNDS CPU VARIANT... ; prints prog variant round cycles instr user_s
cd "$(dirname "$0")/b"
rounds=$1 cpu=$2; shift 2
for r in $(seq 1 $rounds); do
  for f in *.wasm; do n=${f%.wasm}
    for v in "$@"; do
      out=$(taskset -c $cpu perf stat -x, -e armv8_pmuv3_1/cycles/u,armv8_pmuv3_1/instructions/u ./$n.$v 2>&1 >/dev/null)
      cyc=$(grep /cycles/ <<<"$out" | cut -d, -f1); ins=$(grep /instructions/ <<<"$out" | cut -d, -f1)
      /usr/bin/time -f %U -o ut taskset -c $cpu ./$n.$v >/dev/null
      echo "$n $v $r $cyc $ins $(cat ut)"
    done
  done
done
