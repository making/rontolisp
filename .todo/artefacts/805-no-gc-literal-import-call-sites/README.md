# 805 / 804: what `--no-gc` emits for a host-facing reactor, and what two spikes removed

Numbers and the reading: `.kb/no-gc-scalar-wasm.md` ("A literal `:string` argument is two
constants" and the module-surface sections above it) -- both items are closed, and their
text is recoverable from the deletion history. This directory is how to reproduce them.

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

## After `805` landed (2026-09-13)

| | total | types | exports | code (functions) | data |
| --- | ---: | ---: | ---: | ---: | ---: |
| as measured here | 1,382 | 129 (24) | 128 | 491 (20) | 497 |
| after `804` | 1,090 | 57 | 69 | 360 (17) | 481 |
| after `800` | 1,011 | 47 | 69 | 300 (8) | 481 |
| after `805` | **953** | 36 | 69 | **256 (5)** | 481 |

`805`'s share was **-58 bytes** (-5.7%), and `host.mjs` produced output identical to the
baseline's. At `--optimize=off` the same change is 1,483 -> 1,378.

**The ladder below was right that the fold is worth ZERO on import call sites alone, and
wrong about why that would stop mattering.** `800` removes the forwarder at the BYTE level,
after emission, so the literals it substitutes into a wrapper's address arithmetic are
invisible to any source-level lowering -- reading the WAT of the 1,011-byte module shows
them sitting in `InitApp` where no compiler pass could reach them. What landed therefore
reads through the forwarder in the AST instead: a `defun` whose whole body hands its own
parameters to an import IS that import, so the fold looks at the forwarder's call sites,
and when it takes all of them neither the forwarder nor the wrapper is emitted. That is
what took the function count from 8 to 5 -- the three surviving wrappers and the two
forwarders merged into them. `.kb/no-gc-scalar-wasm.md`, "A literal `:string` argument is
two constants".

`spiked-functions.wat` remains the specification of the shape, and the emitted module now
matches it: `InitApp` opens `i32.const 12; i32.const 47; call 0`.

## The measured ladder

| | total | code (functions) | types |
| --- | ---: | ---: | ---: |
| baseline | 1,383 | 491 (20) | 129 (24) |
| `805` alone | **1,383** | 491 (20) | 129 |
| `800` alone | 1,321 | 457 (16) | 106 |
| `800` + `805` | 1,244 | 407 (12) | 83 (16) |
| + no length prefixes (NOT recommended, see the item) | 1,184 | 405 (12) | 83 |

`805` alone is zero because the literals are not at the import call site.
