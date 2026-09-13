# 805 / 804: what `--no-gc` emits for a host-facing reactor, and what two spikes removed

Numbers and the reading: [`../../805-no-gc-literal-import-call-sites.md`](../../805-no-gc-literal-import-call-sites.md)
and [`../../804-no-gc-module-surface.md`](../../804-no-gc-module-surface.md). This
directory is how to reproduce them.

## The benchmark

`bench.lisp` -- four host DOM imports (three taking two `:string`s), four thin forwarders,
a recursive `fib`, four exports, seven literals crossing out. Build:

```bash
rontolisp bench.lisp -o base.wasm --no-gc --no-wasi --optimize=size
```

**It measures 1,382 bytes here against the 1,383 the items quote.** The one byte is in the
data section: the literal TEXT in this file is placeholder prose written to the same
lengths as the original, and one of them lands in a different 4-byte alignment bucket. The
CODE section -- which is what 804, 805 and 806 are about -- is byte-identical at 491 in 20
functions, and so are the type (129 / 24) and export (128 / 8) sections.

## Scripts

- `sizes.sh` -- build and print the section table for one module.
- `sections.mjs` -- section sizes and counts straight out of the binary, no `wasm-tools`.
- `host.mjs` -- the JS host. Every spiked module was required to produce output identical
  to the baseline's through this before its size was believed: the four DOM strings
  `InitApp` passes, `AddNumbers(1234,5678)=6912`, `RunComputation(20)=6765`, and the three
  branches of `AppendLogMessage`.

## The two `.wat` files

`baseline-functions.wat` and `spiked-functions.wat` are the FUNCTION sections of the
baseline and of the `800`+`805` spike (20 functions against 12). The data sections are not
included: they held the pre-placeholder literals, and they are not what these items are
about.

Read them side by side for the shape the item argues about -- the four import wrappers
(`local.get 0; i32.const 4; i32.add; local.get 0; i32.load` per `:string` parameter) and the
four forwarders (`local.get 0; local.get 1; call N`) are present in one and absent from the
other, and the call sites in `init-app` / `run-computation` / `append-log-message` carry two
constants each where they carried one.

## The compiler patch is NOT here

The spike was applied to `NoGcWasmCompiler.java` in a scratch worktree and reverted; only
its output survived. `spiked-functions.wat` is therefore the specification of what the
sound version has to emit, not a diff to re-apply. The i32 spike in
[`../806-no-gc-internal-void/`](../806-no-gc-internal-void) DOES carry its diff.

## After `804` and `800` landed (2026-09-13)

Both are in, so the baseline this directory describes is history twice over. The same build
of `bench.lisp`:

| | total | types | exports | code (functions) | data |
| --- | ---: | ---: | ---: | ---: | ---: |
| as measured here | 1,382 | 129 (24) | 128 | 491 (20) | 497 |
| after `804` | 1,090 | 57 | 69 | 360 (17) | 481 |
| after `800` | **1,011** | 47 | 69 | **300 (8)** | 481 |

`800`'s share was **-79 bytes**, not the 62 the ladder below predicted, and it took the
function count to 8 -- one byte of code section away from the 299 a hand-written non-GC
toolchain emits for this program. `host.mjs` was run against every step and produced output
identical to the baseline's. `805` must be re-measured against the 1,011-byte module; the
rows below predict nothing about it any more.

`spiked-functions.wat` is still the specification of what `805` has to EMIT, but it is no
longer a description of what is in the way: `800` already removed the four forwarders and
the `set-badge-color` wrapper, and folded the two literals of that call into `InitApp` as
`i32.const 59; i32.const 4; i32.add; i32.const 59; i32.load`. Three wrappers survive, each
with two or three call sites.

## The measured ladder

| | total | code (functions) | types |
| --- | ---: | ---: | ---: |
| baseline | 1,383 | 491 (20) | 129 (24) |
| `805` alone | **1,383** | 491 (20) | 129 |
| `800` alone | 1,321 | 457 (16) | 106 |
| `800` + `805` | 1,244 | 407 (12) | 83 (16) |
| + no length prefixes (NOT recommended, see the item) | 1,184 | 405 (12) | 83 |

`805` alone is zero because the literals are not at the import call site.
