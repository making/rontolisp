# A `--native` output runs `gc.lisp` 7-9% slower than `wasmtime run`

Difficulty: Medium

Measured 2026-09-24, Linux x86_64 (64 cores), wasmtime 47.0.3 (`.kb/native-output.md`,
"Numbers"): `gc.lisp`'s output ran 3.62-3.72 s against `wasmtime run gc.wasm` 3.34-3.39 s
and `wasmtime run --allow-precompiled` of `wasmtime compile`'s `.cwasm` 3.41-3.42 s, at
load average 2 and at 12 alike. The same day's stub-only table recorded 3.60 vs 3.54 s.
The Java-assembled output is byte-identical to `rlpack`'s, so the gap is in the shim's
precompile config or the stub's runtime, not the assembler. `fib.lisp` (no GC) was not
re-measured.

The output's `.cwasm` cannot be run by the CLI to split the two ("compiled without
concurrency support but it is enabled for the host"): the CLI's engine has default
features (`threads`, `component-model-async`, `pooling-allocator`, ...) the pinned
`default-features = false` build does not.

## Plan

- Split codegen from runtime: build a throwaway runner with the CLI's feature set, or a
  shim with the stub's, so one module runs under both.
- Candidates: the CLI's default-on features (`pooling-allocator`, `memory_init_cow`,
  `parallel-compilation` is already on), the allocator (glibc malloc in the stub), and
  `Config` defaults that differ with the feature set.
- Anything that changes the engine config changes `rlabi::FINGERPRINT`
  (`.kb/native-output.md`, "Invariant").
