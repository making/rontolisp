# The generic `length` dispatch is a shared callee, not per-site code

**Invariant: no `length` site emits the sequence-type ladder inline on either compile path. A site is
one call -- `_seq_len` on WASM, `_length` on the JVM -- unless the argument's representation is
already pinned, in which case it is a direct `array.len` and no dispatch at all.** Same lesson as
`.kb/wasm-shared-coercion.md` (`_as_f64`), `.kb/subseq-runtime.md` (`_arr_get`/`_arr_set`) and
`.kb/sequence-op-runtimes.md`.

## The two backends
- **JVM**: `JvmLengthCompiler` emits one `invokestatic _length` (`JvmLengthRuntimeBuilder`; the
  packed-array chain heads `_ivLength`/`_fvLength` delegate down to it), originally for the 64 KB
  per-method limit.
- **WASM**: `_seq_len ((ref null eq)) -> (ref null eq)` -- `WasmLengthCompiler.buildSeqLenBody`, index
  `FUNC_SEQ_LEN`, appended after `FUNC_ARR_SET` as the new `FX_FUNC_LAST`, type
  `TYPE_CALLABLE_BASE + 0` -- holds the ladder once (310 bytes), returning an i31-boxed count:
  packed-float arm (rank check + `dims[0]`), packed-int arms, string-vs-symbol arm
  (`_str_char_count`), general-array box arm (fill pointer else `dims[0]`, rank-2+ trap), hash-table
  arm, cons walk.
- Both answer a STRING length through their character-count helper (`_str_char_count` on WASM,
  `_scount` on the JVM); neither recounts a string it already counted -- see [[string-index-cost]] for
  why `(length s)` in a loop head is not a per-call walk.
- **The `DeclaredArrayTypes` short-circuit stays at the site**: an argument pinned to a packed integer
  vector compiles to a trapping `ref.cast` + `array.len` and never calls
  (`.kb/declarations-type-checks.md`) -- the same "proven arm stays inline" split as `_arr_get`.

## Load-bearing numbers
- Marginal cost of one more generic `length` site: ~309 bytes -> 4.
- Copies are expensive: the zlib artifact carried 66 non-overlapping ladder copies, ~14.4 KB, 13.6% of
  the module -- spread over the toplevel chunk, chipz's defuns AND the spliced helpers
  (`%SUBSEQ-RUNTIME`, `%SEQ-INT-VECTOR`, `%SEQ-TO-LIST`, `%REPLACE-RUNTIME-ARRAY` each inlined it too);
  sharing cut zlib 6.1-8.1% depending on `--optimize` level, collapsed the `LENGTH` first-class wrapper
  defun 314 -> 6 bytes, and left `hello_world`/`pi_approx` byte-identical at both `--optimize` levels
  (the body is emitted unconditionally and the shaker drops it unused). CI-tracked rows:
  `size-report/results/wasm-flags.md`.

## At the DEFAULT optimize level, not just `size`
Both levels emit the call; not one of the two `prefersSizeOverSpeed()` trades. A call per `length`
CALL (not per element) is invisible next to the `ref.test` chain it replaces -- measured on zlib's
inflate window copy, gunzip timings unchanged.

## Re-evaluation triggers
- **If `_seq_len` gets hot**, add a fast path at the SITE (e.g. a cons/string `ref.test` before the
  call) or inside the shared body -- never a return to inlining. A new representation tier means
  editing `buildSeqLenBody` alone.
- A program with exactly one generic `length` site pays slightly more (310-byte body + call vs one
  ~300-byte inline copy). Break-even is between one and two sites and the un-shaken wrapper catalog
  always holds a second, so no site-count gate is worth having (same rejection as
  `.kb/sequence-op-runtimes.md`).
- Per-site ladders of the SAME shape still inline, measured bytes/site: `position` 451, `assoc` 433,
  `reverse` 419, `elt` 406, `(setf (aref ...))` 375, `member` 335, `eql` 228, `aref` 204. Separate
  decisions; count surviving copies in a real artifact before pricing any.

## Pinning tests
- `WasmLispCompilerTest.aLengthSiteDoesNotCarryItsOwnCopyOfTheSharedDispatch` -- the marginal byte
  budget. Nothing else notices: every arrangement compiles and runs correctly.
- Behavior: the length cases in `WasmLispCompilerIntegrationTest` and `JvmLispCompilerTest`, the
  string/array/fill-pointer sequence cases in `ci-spec.yaml` (all four backends), and the zlib row's
  gunzip byte-for-byte check in `size-report/measure.sh` / `ChipzE2eTest`.
