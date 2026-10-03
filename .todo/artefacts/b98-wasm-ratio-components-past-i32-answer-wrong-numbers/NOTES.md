# Exact WASM ratios: the differential sweeps

`run.sh JAR [SEED]` compares the interpreter with WASM-GC (Preview 1 at the default level
and at `--optimize=size`) over random ratio work, and the Scheme and Clojure decimal readers
on all four backends against Python's `float` (Java's `parseDouble`). The generators take a
seed; `run.sh` prints the number of differing lines per sweep.

| Sweep | What it prints | i32-component runtime (seed 7) | exact components |
| --- | --- | --- | --- |
| `gen_ops.py` | 400 random ratios (components to 300 bits) and 300 aimed at the double range: the ratio, its float, its floor/ceiling/round/truncate, its + - * / < = against the previous one; `rational` of 300 random doubles | 3,697 of 3,700 lines | 0 |
| `gen_edges.py` | float of 1,500 ratios near 2^e, e in the subnormal and overflow edges and around 1 | 1,499 of 1,500 | 0 |
| `gen_ties.py` | float of 1,000 ratios exactly halfway between two doubles, or one sticky bit off | 996 of 1,000 | 0 |
| `gen_bigint.py` | float of 1,500 limb integers (ties weighted) | 66 of 1,500 | 0 |
| `gen_decimals.py` | 531 decimal strings through Clojure `read-string` and Scheme `string->number` | Clojure 0, Scheme 529 on both wasm legs | 0 on every leg |

Measured 2026-10-03 with seeds 1-5, 7, 11-14 and 21-23 (`.kb/wasm-bignum.md`, "Ratios").
The i32 runtime's Clojure column is 0 because its reader built doubles from IEEE bits in Lisp
until the shared `%decimal-double` replaced it.
