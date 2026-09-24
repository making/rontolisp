# 957: how many of the locals a landing pad refreshes are live after it

Measured 2026-09-24, linux-x64, wasmtime 49.0.0, on the hello-ningle Worker's fast-http
`parse-request` (every `defun-speedy` parser helper inlined into it: 1,838 locals, 80
`try_table`s, 120 refresh runs).

- `runs.py FUNC.wat` counts `local.get` / `local.set` in runs of 8 or more -- the push and
  pop halves of `WasmLandingPad.keepLocalsAlive` / `refreshLocals`.
- `padlive.py FUNC.wat` runs a backward liveness over the function's `wasm-tools print` text
  (structured control flow from the `@N` label annotations; every call or throw inside a
  `try_table` may reach each enclosing catch label) and counts, per refresh run, the restored
  locals that are live after it.

| build | function bytes / module | restored | live after | that function, serial Cranelift |
| --- | ---: | ---: | ---: | ---: |
| `worker.lisp --no-wasi --optimize=size` (size-report row) | 624,468 / 2,749,384 (22.7%) | 101,756 | 156 | 28.6 s |
| `check.lisp --optimize` (examples `wasm` leg) | 749,554 / 3,355,186 (22.3%) | 119,480 | 156 | 46.6 s |

A POSITIONAL rule -- keep a local read textually after the pad or inside a loop enclosing it --
keeps 117,656 of the `--optimize` build's 119,360 pushed (a positional variant of the same walk,
not kept here): the parser's state loop encloses every pad, so only real liveness separates
the 156.

Extracting one function: `wasm-tools print m.wasm > m.wat`, then
`awk '/^  \(func \(;K;\)/ {p=1; print; next} p && /^  \(/ {exit} p' m.wat > f.wat`.
