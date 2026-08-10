# Size report

How big a rontolisp artifact is, measured rather than remembered.

The numbers used to live in the example READMEs, where they went stale as soon
as the compiler changed. They live here instead: one script measures everything,
[`results/`](results) holds what it produced, and
[`.github/workflows/size-report.yaml`](../.github/workflows/size-report.yaml)
re-runs it weekly and commits the diff. Example READMEs link here; they quote no
byte counts of their own.

## The reports

| File | What it measures |
| --- | --- |
| [`results/wasm-flags.md`](results/wasm-flags.md) | Two micro programs across every size-relevant compiler flag -- what `--optimize`, `--component` and `--no-gc` are each worth |
| [`results/cloudflare-workers.md`](results/cloudflare-workers.md) | The [Cloudflare Worker](../examples/cloudflare-workers) modules, raw and gzipped -- what a web framework costs in a bundle |
| [`results/sizes.json`](results/sizes.json) | The same numbers, machine-readable |

## Running it

```bash
./measure.sh            # both families, rewrite results/
./measure.sh wasm       # the flag matrix only (seconds)
./measure.sh workers    # the Worker modules only
./measure.sh clean      # remove out/
```

It uses `target/rontolisp` when the GraalVM native binary is built and the
executable jar otherwise (override with `RONTOLISP=/path/to/rontolisp`).
`wasmtime` is optional -- without it the micro programs are built and measured
but not run. `npx` is optional -- without it the jco-transpiled glue row is
skipped. The Worker builds `ql:quickload` clack / lack / tiny-routes / ningle,
so the first run needs network.

## What is measured

[`programs/`](programs) holds the two micro programs, which exist only to be
measured:

| Directory | Program |
| --- | --- |
| [`programs/hello_world/`](programs/hello_world) | Write `Hello, World!` to stdout, and nothing else |
| [`programs/pi_approx/`](programs/pi_approx) | Approximate pi with the Leibniz series, 1,000,000 terms, to 15 decimal places |

Each has a `-nogc` companion, because `--no-gc` accepts only `(defun ...)` and
`rontolisp:wasm-export` at top level and has no `format`. They follow
[wado-lang/wado `wasm-size/`](https://github.com/wado-lang/wado/tree/main/wasm-size),
a cross-language Wasm size comparison, so the rows can be read next to C, Rust,
Zig, Moonbit and Wado.

The Worker family compiles the real
[`examples/cloudflare-workers/`](../examples/cloudflare-workers) sources in
place, plus two variants that exist only for the comparison: the two
tiny-routes Workers rebuilt against the full `tiny-routes` system instead of
`tiny-routes/lite`, which is what makes the price of the regex engine visible.

Every module is checked before it is measured: the micro programs must still
print the right answer under `wasmtime`, or the run fails instead of reporting a
smaller number for a module that stopped working.

## Reading the numbers

**`--optimize` is not optional.** Without it a module carries the whole prelude
-- ~124 KB for `hello_world`, 99.6% of which nothing in the program reaches.
`--optimize` is the dead-code tree-shaker; it is what turns 124 KB into a few
hundred bytes. Only tree-shaken numbers are worth comparing.

**wasm-GC modules are not like-for-like with C or Zig.** rontolisp's default
WASM backend is wasm-GC: strings and objects are host-managed GC types, so the
module ships no allocator, no `malloc` and no linear-memory bookkeeping, while
every Preview 1 row from a linear-memory language carries its own heap. The
comparable rows are the `--no-gc` ones, which emit a plain MVP core module.

**`--component` costs about 1.1 KB.** It re-frames the module as a WASI 0.3
component; the canonical-ABI adapters and the type section are the whole
difference. Its imports are then stated as a WIT world rather than as
`fd_write`.

**gzip is the number Cloudflare counts.** A Worker bundle is limited to 3 MB
compressed on the free plan, so the Worker table reports raw and gzipped sizes
and the share of that limit. What a framework costs there is module size and
isolate startup, not per-request time.

## Cross-language context

Quoted from the upstream README (measured there on 2026-08-03 with wasi-sdk
25.0, rustc 1.97.1, Zig 0.15.2, Moonbit 0.1.20260803) -- **not** re-measured
here, so read it as context rather than as a controlled benchmark. Each language
is built with its own size-optimization flags. The rontolisp rows are the
current ones from [`results/wasm-flags.md`](results/wasm-flags.md).

### hello_world

| Language | WASI | Size (bytes) |
| --- | --- | ---: |
| wado | Preview 3 (component) | 1,974 |
| c | Preview 1 | 3,829 |
| zig | Preview 1 | 4,449 |
| moonbit | Preview 1 | 9,227 |
| rust | Preview 1 | 40,365 |

### pi_approx

| Language | WASI | Size (bytes) |
| --- | --- | ---: |
| wado | Preview 3 (component) | 6,034 |
| zig | Preview 1 | 10,608 |
| c | Preview 1 | 18,105 |
| moonbit | Preview 1 | 22,986 |
| rust | Preview 1 | 59,753 |

## Flags

| Flag | What it does |
| --- | --- |
| `--optimize` | Dead-code-eliminate: keep only what `_start` and the exports reach |
| `--optimize=size` | The above, plus trade speed for size -- drops fused integer trees and unboxed locals. Costs up to 6x the runtime on integer-heavy code |
| `--component` | Emit a WASI 0.3 component instead of a Preview 1 module |
| `--no-gc` | Emit a plain MVP core module: no wasm-GC, numeric subset only, exports rather than `_start` |
| `--no-wasi` | No WASI imports at all; a reactor with `_initialize` instead of `_start` |
