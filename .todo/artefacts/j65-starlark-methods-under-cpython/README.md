# Starlark under CPython on wasm: a literal a global, count, strip, pop and a string's hash (2026-11-16)

The numbers of `.kb/starlark-frontend.md`, "A literal is a global", of
`.kb/wasm-gc-strings.md`, "A literal is one object", and of `.kb/bulk-primitives.md`.
The programs, `time.sh`, the sampler and `parts/` are
`.todo/artefacts/j54-starlark-faster-than-cpython/` and `j63-starlark-under-cpython/`.

- `litglobal.py in.wat out.wat` -- the measurement the item asked for before the literal
  was made: every `i32.const off; i32.const len; call 49` (`_str_lit`) of a module's text
  (`wasm-tools print`) becomes `global.get` of a global of its own, filled at the head of
  function 2 (`_start`). `methods.star`: 1.67 -> 1.54 s of user time, 202 literals at 331
  sites, the module 1,405 bytes larger.
- `litstat.py module.wasm` -- how many sites read each literal's global (the literals
  `_lit_init` builds, by the start function's body). `methods.star` 141 of 202 at one
  site, `examples/proxy-wasm/gateway.lisp` 112 of 125: a literal named once is what the
  change costs in bytes.
- `sizes.sh jar outdir` (`REPO=` the repository root) -- the `.wasm` sizes of sixteen
  programs for one build; two runs side by side are the size tables.
- `abw.sh N prog.star a.cwasm b.cwasm ..` -- alternating runs with `python3` among them:
  the least wall time and the least user and system time of each.
- `stringcheck.star` -- `count`, `find`, `startswith`, `endswith`, `strip`, `upper`,
  `lower` and a dict's `get` at their edges (each bound, an empty needle, overlapping
  occurrences, characters of several bytes, a cutset of them): the same output from the
  four backends and from the build before; Java Starlark's but for its ASCII-only case
  fold, Go's but for its byte indices.
- How the backend's changes were checked against the whole ci-spec corpus between the
  `CiSpecE2eTest` runs (forty minutes through the exec jar): as
  `j64-starlark-bulk-primitives/README.md` says, the driver's `ci-program.lisp` and its
  fixtures copied out of the run's `@TempDir`, compiled by each build and run under
  `wasmtime run -W gc=y -W exceptions=y --dir . --dir /tmp test.wasm alpha beta`, the
  9,984 lines compared.
- A module's start function over 100,000 literals (2.6 MB of module, generated text):
  `wasmtime compile` 2.1 s and 449 MB, 0.22 s and 68 MB over 12,000 -- linear, where a
  top-level body's allocations are not (`.kb/wasm-function-body-size.md`). The corpus
  module names 11,500 (115 KB of `_lit_init`); it is split at 16,384 a function all the
  same, for the bound the driver holds every body to.
