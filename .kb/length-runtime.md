# The generic `length` dispatch is a shared callee, not per-site code

**Invariant: no `length` site emits the sequence-type ladder inline on either
compile path. A site is one call -- `_seq_len` on WASM, `_length` on the JVM --
unless the argument's representation is already pinned, in which case it is a
direct `array.len` and no dispatch at all.**

Same lesson as `.kb/wasm-shared-coercion.md` (`_as_f64`), `.kb/subseq-runtime.md`
(`_arr_get`/`_arr_set`) and `.kb/sequence-op-runtimes.md`: a per-site expansion
that grew past a few hundred bytes becomes a callee.

## The two backends, and which one was lagging

- **JVM**: always had the shared form. `JvmLengthCompiler` emits one
  `invokestatic _length` (`JvmLengthRuntimeBuilder`; the packed-array chain heads
  `_ivLength`/`_fvLength` delegate down to it), originally for the 64 KB
  per-method limit.
- **WASM**: `WasmLengthCompiler` spelled the whole ladder at every site --
  packed-float arm (rank check + `dims[0]`), packed-int arms, string-vs-symbol
  arm (`_str_char_count`), general-array box arm (fill pointer else `dims[0]`,
  rank-2+ trap), hash-table arm, cons walk -- ~300 bytes and up to three
  never-released temps each. `_seq_len ((ref null eq)) -> (ref null eq)`
  (`WasmLengthCompiler.buildSeqLenBody`, index `FUNC_SEQ_LEN`, appended after
  `FUNC_ARR_SET` as the new `FX_FUNC_LAST`; type `TYPE_CALLABLE_BASE + 0`) is
  that ladder once, 310 bytes, answering the i31-boxed count.

Both bodies answer a STRING length through the character-count helper of their
backend (`_str_char_count` on WASM, `_scount` on the JVM), and neither of those
recounts a string it has already counted -- see [[string-index-cost]] for why
`(length s)` in a loop head is not a per-call walk of the whole string.

**The `DeclaredArrayTypes` short-circuit stays at the site**: an argument whose
representation is pinned to a packed integer vector compiles to a trapping
`ref.cast` + `array.len` directly (`.kb/declarations-type-checks.md`) and never
calls -- the same "the proven arm stays inline" split as `_arr_get`.

## What it bought

Measured 2026-08-11 against `83083cd3` (local numbers matched the CI-tracked
`size-report/results/wasm-flags.md` rows exactly). The zlib artifact carried 66
non-overlapping ladder copies, ~14.4 KB, 13.6% of the module -- the single
biggest machinery item -- spread over the toplevel chunk, chipz's own defuns AND
the spliced runtime helpers (`%SUBSEQ-RUNTIME`, `%SEQ-INT-VECTOR`,
`%SEQ-TO-LIST`, `%REPLACE-RUNTIME-ARRAY` all inlined it too, so sharing shrank
them as well). The `LENGTH` first-class wrapper defun collapsed 314 -> 6 bytes
(its body is now the one call), and the ladder's rank-trap signature went 29
occurrences -> exactly 1 (inside `_seq_len`).

| `size-report zlib` | before | after | |
| --- | ---: | ---: | ---: |
| `--optimize` | 133,833 | 125,213 | -6.4% |
| `--optimize=size` | 105,393 | 96,834 | **-8.1%** |
| `--component --optimize=size` | 109,899 | 101,340 | -7.8% |
| (none) | 318,219 | 298,934 | -6.1% |

Marginal cost of one more generic `length` site: ~309 bytes -> 4 (the todo-331
per-site table's biggest still-inlined row). `hello_world` and `pi_approx` are
byte-identical at both `--optimize` levels (no site survives their shake; the
body is emitted unconditionally and the shaker drops it with nothing calling
it); their un-shaken `(none)` builds fell 4,570 bytes each, which is the ladder
leaving the wrapper catalog.

## Why it is at the DEFAULT optimize level

Same answer as `_as_f64`, measured the same way: zlib's inflate loop calls
`length` inside the window copy, and gunzipping a 1.3 MB stream (the 8,192-byte
input buffer's ceiling on the size-report fixture shape) is 2.70 s -> 2.70 s at
`--optimize=size` and 2.18 s -> 2.15 s at `--optimize` (wasmtime 47, best of
five) -- a call per `length` CALL, not per element, is invisible next to the
`ref.test` chain it replaces. So both levels emit the call; this is not one of
the two `prefersSizeOverSpeed()` trades.

## The re-evaluation trigger

- **If `_seq_len` ever gets hot**, the answer is a fast path at the SITE (e.g. a
  cons/string `ref.test` before the call) or inside the shared body, never a
  return to inlining -- "66 copies, 13.6% of the module" is what one ladder
  costs the moment there is more than one of it. Adding a representation tier
  means editing `buildSeqLenBody` alone.
- **A program with exactly one generic `length` site pays a little more** (the
  310-byte body plus the call against one ~300-byte inline copy). Break-even is
  between one and two sites, and the un-shaken wrapper catalog always holds a
  second one, so no site-count gate is worth having -- the same rejection as
  `.kb/sequence-op-runtimes.md`.
- The remaining per-site ladders of the SAME shape (`position` 451 B/site,
  `assoc` 433, `reverse` 419, `elt` 406, `(setf (aref ...))` 375, `member` 335,
  `eql` 228, `aref` 204 -- todo-331's measured table) are separate decisions;
  count the surviving copies in a real artifact before pricing any of them.

## Pinning tests

- `WasmLispCompilerTest.aLengthSiteDoesNotCarryItsOwnCopyOfTheSharedDispatch` --
  the marginal byte budget. Nothing else notices: every arrangement compiles and
  runs correctly.
- The behavior is pinned where it already was: the length cases in
  `WasmLispCompilerIntegrationTest`, `JvmLispCompilerTest`, the string/array/
  fill-pointer sequence cases in `ci-spec.yaml` (all four backends), and the
  zlib row's gunzip-byte-for-byte check in `size-report/measure.sh` /
  `ChipzE2eTest`.
