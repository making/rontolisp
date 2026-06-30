# Break the JVM "baked constants" ceiling (blocks larger baked models)

**Status:** open. Discovered while extending `examples/hiragana/` to 46 classes
(see `.todo/16`). It is the reason the demo's hidden layer is pinned at 20: a
larger net runs on the interpreter and WASM but won't load on the JVM backend,
so to keep all four backends we capped capacity. We want to lift this cap with a
different codegen approach rather than shrinking the model.

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

4. **Load weights at runtime from an external file (NOT for the browser).**
   `(read)` weights from a sidecar file instead of baking. The runtime reader
   already supports it. But it breaks self-containment and the browser WASM has no
   filesystem (the original reason weights are baked), so this only helps
   non-browser/native runs. Keep as a fallback, not the primary fix.

## Recommended next step
Prototype approach 1 (packed string/blob, optionally int8-quantized) on the
hiragana `infer.lisp` path: change `train.lisp` serialization + add the JVM
data-decode, confirm `infer.lisp` loads on the JVM at hidden=48/64 (and ideally a
higher resolution), then re-verify all four backends. If we later want the
limit gone program-wide (not just baked data), do approach 2.

## References
- `examples/hiragana/train.lisp` (`*hidden*` cap comment + serializer),
  `infer.lisp` (rebuilds the net from `*weights*`).
- `JvmLispCompiler` / `codegen.jvm` (constant emission, class version 50),
  `am.ik.jvm` (bytecode writer — where StackMapTable would go).
- CLAUDE.md: "JVM Class Version 50", "JVM method name mangling", weight chunking.
- `.todo/16-extend-hiragana-to-full-set.md` (where the cap currently bites).
