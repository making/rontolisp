#!/usr/bin/env bash
# probe.sh <module.wat> <n>... : whether n objects fit in a hard-capped 64 MiB GC heap.
mod=$1
shift
for n in "$@"; do
	if wasmtime run -O gc-heap-may-move=n -O gc-heap-reservation=67108864 -O gc-heap-reservation-for-growth=0 --invoke alloc "$mod" "$n" >/dev/null 2>&1; then
		echo "$mod n=$n fits"
	else
		echo "$mod n=$n TRAP"
	fi
done
