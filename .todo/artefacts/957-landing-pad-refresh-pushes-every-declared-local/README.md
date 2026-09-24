# 957: how many of the locals a landing pad refreshes are live after it

Measured 2026-09-24, linux-x64 (64 cores), wasmtime 49.0.0, on the hello-ningle Worker's
fast-http `parse-request` (every `defun-speedy` parser helper inlined into it: 80 `try_table`s,
120 pads). The conclusions live in `.kb/wasm-landing-pad-refresh.md` ("Why this shape",
"Cost"); this directory keeps what produced them.

| build | pushed and refreshed | function bytes / module | that function, serial Cranelift | `wasmtime compile` wall |
| --- | ---: | ---: | ---: | ---: |
| `worker.lisp --no-wasi --optimize=size` (size-report row), every declared local | 101,636 | 624,468 / 2,749,384 | 30.6 s | 30.3 s |
| the same, narrowed (`WasmCarriedLocals`) | 80 | 42,975 / 2,073,313 | 0.41 s | 1.8 s |
| `check.lisp --optimize` (examples `wasm` leg), every declared local | 119,360 | 749,554 / 3,354,877 | 45.0-46.6 s | 52.1 s |
| the same, narrowed | 80 | 61,011 / 2,578,987 | 0.5-0.6 s | 1.9 s |

The ci-spec corpus (`corpus.py`, then a default CLI build): 7,594,940 -> 6,011,558 bytes.

## The tools

All read `wasm-tools print` text; `wat_cfg.py` is their shared control flow, with every label
resolved through the control stack. wasm-tools annotates a block `;; label = @N` with its nesting
DEPTH, so the same `@N` names different blocks in different places: the first `padlive.py`
keyed labels by it and reported 156 live-after locals where there are 80.

- `runs.py FUNC.wat [minrun]` counts `local.get` / `local.set` in runs of 8 or more -- the push
  and refresh halves.
- `padlive.py FUNC.wat`: per pad, the refreshed locals live after it, with the push reads not
  counted as uses.
- `padfix.py FUNC.wat`: the least fixed point `WasmCarriedLocals` computes (a kept push reads
  its local), independently: 80 of 101,636 and 80 of 119,360, what the pass keeps.
- `padkept.py FUNC.wat`: what the pads of an already-narrowed function still push and refresh.
- `padcheck.py MODULE.wat`: the invariant over a whole module -- no reference-typed local may be
  live on entry to any pad. Before the narrowing: 586 of the ci-spec corpus module's 1,777 pads,
  and 7 + 2 pads in each ningle module, all from pushes reading locals an earlier pad did not
  refresh. After: none in any of the three.
- `funcsizes.py MODULE.wasm [top]`: the largest code entries, with local counts.
- `corpus.py ci-spec.yaml out.lisp`: the ci-spec cases as one program.

Serial per-function Cranelift times come from
`../955-wasmtime-compile-quadratic-in-allocations-per-function/perfunc.sh` (ordinal = function
index + 1 - imported functions: `parse-request` is func 1304 of the size-report module, which
imports none, and func 1327 of the check module, which imports 15).

Extracting one function: `wasm-tools print m.wasm > m.wat`, then
`awk '/^  \(func \(;K;\)/ {p=1; print; next} p && /^  \(/ {exit} p' m.wat > f.wat`.
