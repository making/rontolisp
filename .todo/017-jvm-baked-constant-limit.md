# Break the JVM "baked constants" ceiling (blocks larger baked models)

**Status:** open as a CODEGEN issue -- but nothing in the repo is blocked on it
any more. It was discovered while extending `examples/browser/hiragana/` to 46
classes, and it used to be what pinned that demo's hidden layer at 20 (a larger
net ran on the interpreter and WASM but would not load on the JVM backend).

**The hiragana demo no longer bakes its weights** (2026-07-13): it writes them to
`weights.bin` (RLW1 binary, `save-rlw1`) and READS them at startup on every
backend -- approach 4 below, made viable in the browser by teaching
`wasi-shim.js` a read-only virtual filesystem. Its model grew from 12.5k to 150k
parameters with no codegen change. So the remaining question is only the general
one: should a program that bakes tens of thousands of float constants compile on
the JVM backend at all? If yes, approach 1 or 2 below is still the fix; if we are
content telling such programs to load their data instead, this can be closed.

## The constraint, precisely (measured on JDK 25, `claude-opus` session 2026-06)

Two distinct JVM limits bite when a program bakes in a lot of constants. Both are
about how `JvmLispCompiler` emits a class (version 50, **no StackMapTable** — see
CLAUDE.md "JVM Class Version 50").

1. **Class-wide verifier ceiling (~12.8k float constants) — the hard one.**
   A v50 class with no stackmaps loads on JDK 25 only up to ~12,800 baked
   `double` constants. Past that, loading fails with
   `java.lang.VerifyError: Expecting a stackmap frame at branch target N`
   (observed in `_lispToDisplayString` and others — the failure is class-wide,
   not method-specific). Binary-searched with a synthetic program: 64 chunks ×
   200 floats = 12,800 → OK; 66 × 200 = 13,200 → VerifyError.
   - This is BELOW the 65535 constant-pool limit (12.8k doubles = ~25.6k pool
     slots, since a `CONSTANT_Double` takes 2 slots), so the pool is not the
     binding limit — the verifier is.
   - Consequence at 24x24 input (576): weights = `623*hidden + 46`, so
     `hidden <= ~20`. hidden=20 → 12,506 weights (loads); hidden=21 → 13,129 (fails).
   - `-XX:+FailOverToOldVerifier` is gone in JDK 25 (option unrecognized), and
     `-Xverify:none` crashes the VM on these big classes — so there is no runtime
     flag escape hatch; it must be fixed in codegen.

2. **Per-method 64 KB bytecode cap on top-level forms — the soft one (already
   worked around).** Top-level `defparameter`/expression literals all compile
   into one `main` method. 184 glyph literals (46 kana × 4 fonts) overflowed it
   (`Invalid method Code length 82477`). Worked around in the trainer by emitting
   each glyph as its own `(defun glyph-... () (list ...))` (separate method). The
   weights in `infer.lisp` are likewise chunked into `gN` defuns. So this cap is
   manageable today by chunking into functions; it's limit (1) that actually
   blocks scaling.

## Root cause
The backend targets class version 50 to use the lenient type-inference verifier
and avoid emitting StackMapTable attributes. Modern HotSpot has dropped the
old-verifier failover, so large/complex v50 classes get pushed onto the split
(stackmap) verifier, which then demands frames the backend never wrote.

## Approaches to break through (ranked; pick one)

1. **Store weights as packed data, not thousands of bytecode constants
   (recommended first — localized, keeps the self-contained/browser story).**
   Emit the weights as a handful of large string constants (or one base64 blob),
   not ~25k individual `ldc` float constants. A `CONSTANT_Utf8` holds up to 65535
   bytes, so ~25k floats (~200 KB of text) is ~4 string chunks = ~4 pool entries
   instead of ~25k. Concatenate + parse at runtime (reuse the embedded reader, or
   a tiny number parser). Sidesteps BOTH limits at once and stays one
   self-contained class. Mirror it for WASM if convenient (WASM has no such cap,
   but a shared encoding is cleaner). Touch points: a "large constant data"
   helper in `codegen.jvm`, and `train.lisp`'s serializer (emit a data blob
   instead of `gN` float-list defuns).
   - Combine with **quantization** for an even bigger win: store weights as int8
     (256 levels) + a scale, dequantize at load. Quantized values dedupe to <=256
     distinct constants, and it's a legitimate ML compression. Could make even the
     naive `ldc` path fit, but the string/blob path is more robust.

2. **Emit StackMapTable and move to class version 51+ (the general fix).**
   Add a stackmap-frame generator to `am.ik.jvm` (compute frames at branch
   targets via abstract interpretation / basic-block typing) and bump the class
   version. Unblocks arbitrary class size for ALL programs, not just baked data —
   removes the reason the version-50 constraint exists. Biggest effort; reverses a
   core design choice (CLAUDE.md "JVM Class Version 50"); needs careful handling of
   the patterns the lenient verifier currently tolerates (dead code after `goto`,
   `checkcast` placement, definite assignment). High value, high cost.

3. **Multi-class output (split constants across helper classes).**
   Emit weight data into generated helper classes the main class references; each
   stays under the ceiling. Needs the backend to emit more than one class and wire
   classloading. Medium effort; doesn't help the general case, only big data.

4. **Load the data at runtime from an external file (TAKEN by the hiragana
   demo, 2026-07-13).** Read the weights from a sidecar file instead of baking
   them. This was written off here as "not for the browser" because a browser WASM
   module has no filesystem -- which turned out to be a property of the SHIM, not
   of WASM: `examples/browser/hiragana/wasi-shim.js` now answers `path_open`/
   `fd_read` out of an in-memory file map fed by `fetch`, so the module just opens
   `weights.bin` like it does under `wasmtime --dir .`. Cost: one more artifact to
   serve (and, for a big model, a per-instantiation load -- the demo pays it once
   by keeping the instance alive and calling a `wasm-export`ed entry point per
   recognition). It does not help a program that genuinely wants to be one
   self-contained class/module, which is what 1-3 are for.

## A third ceiling, measured 2026-08-19: the 65,534-entry constant pool

Not the verifier limit above -- the plain class-format one, and the compiler
already refuses cleanly rather than emitting a bad class:

```
error: constant pool overflow: this class needs more than 65534 constant pool
entries, the JVM class-format limit; split the program
```

Binary-searched with a synthetic rove suite of `n` tests x 3 assertions (an
assertion-heavy rather than data-heavy program: `ok` quotes its own form and
step-expansion so the reporter can print `(+ 1 1) = 2`, so every assertion bakes
its source as DATA on top of the code that evaluates it):

| n tests | assertions | result |
| --- | --- | --- |
| 400 | 1,200 | 8.9 MB class, runs |
| 550 | 1,650 | 11.4 MB class, runs |
| 700 | 2,100 | 13.9 MB class, runs |
| 900 | 2,700 | **constant pool overflow** |

Ceiling ~750-850 tests / ~2,300-2,600 assertions. Nothing in the repo is blocked
on it (the rove-migration idea that found it was cancelled), but it belongs
here: same class file, third distinct resource exhausted, and if the answer to
this file ever becomes "emit auxiliary classes", it is the one fix that clears
all three. Before optimizing, dump the pool by tag for the n=700 class -- if it
is dominated by duplicated symbol-name strings, interning at emit time is cheap
and helps every large program.

## Recommended next step
Decide whether we care. There is no in-repo program left that wants to bake this
much data, so the honest options are (a) close this as "load your data instead",
or (b) do approach 2 (StackMapTable + class version 51+), which is the only one
that also removes the class-wide ceiling for programs with no baked data at all
(deeply nested/large generated code would hit the same verifier). Approach 1 is
the cheap middle if a self-contained big-constant class is ever really needed.

## References
- `examples/browser/hiragana/` (the demo that found this; it now READS its
  weights -- `net.lisp` `save-rlw1` /
  `examples/deep-learning-from-scratch/dataset/rlw1.lisp` `load-rlw1`).
- `JvmLispCompiler` / `codegen.jvm` (constant emission, class version 50),
  `am.ik.jvm` (bytecode writer — where StackMapTable would go).
- CLAUDE.md: "JVM Class Version 50", "JVM method name mangling".
- `.todo/137-jvm-local-slot-overflow.md` -- the sibling JVM codegen ceiling.
