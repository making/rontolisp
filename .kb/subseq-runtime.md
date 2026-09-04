# `subseq` and general-array element access are shared callees, not per-site code

**Invariant: no `subseq` site emits the array copy loop inline, and no `aref` /
`%aset` / `row-major-aref` / `%row-major-aset` site emits the displacement-chain walk
inline. The program carries each once.** Same lesson as
`.kb/string-write-runtime.md`, `.kb/wasm-shared-coercion.md`, `.kb/format.md`'s
`%fixed-decimal`.

The STRING lane answers a MUTABLE CHARACTER VECTOR (`_subseqCv` on JVM, `_subseq_str` /
`FUNC_SUBSEQ_STR` on WASM), both charvec-aware: slicing a character vector copies
elements `[start, end)` and never renders the source. Mechanics and pinning tests:
`.kb/string-write-runtime.md`, "A copy-seq/subseq result is mutable with identity".

## 1. `%subseq-runtime` -- a spliced defun, both compile paths
`LispMacroExpander.expandSubseqCompat` lowers `(subseq seq start [end])` to a dispatch
on the RUNTIME type of `seq`: string or cons chain -> `%subseq-core` (what the
per-backend `subseq` compilers emit); a general array is copied element by element into
a fresh `%array-alike`. That array arm is a `dotimes` over `aref`/`%aset`, each itself a
representation dispatch -- 2,316 bytes of wasm per site, paid even by string-only code.
`LispMacroExpander.subseqRuntimeWrapper()` is the callee; its `end` is a PARAMETER, nil
when omitted (both `%subseq-core` lanes accept a runtime nil and default to the length),
so one call shape `(%subseq-runtime seq start end)` serves 2- and 3-argument calls.

**Injection is the BACKEND's**, in the same loop that adds the
`BuiltinFunctionWrappers` (`JvmLispCompiler` / `WasmLispCompiler`), not
`expandTopLevelDefinitions`: most `subseq` sites live in wrapper bodies, which do not
exist until the backend generates them (injecting from the top-level pass left
`minesweeper` unimproved).

Gates:
- **wasm**: whenever the program (or a generated wrapper) calls `subseq`.
- **JVM**: that AND `programUsesAnyArrayOp`. The helper's copy arm names `aref`/`%aset`,
  exactly what the JVM array-runtime gate scans for, so injecting into a string-only
  program would pull ~120 KB of array runtime in (174,682 -> 297,121 on
  `(print (subseq "hello" 1 3))`). Gate off, `JvmSubseqCompiler` declines the rewrite
  anyway. `subseq`/`copy-seq`/`replace` now RAISE that gate themselves (the string
  lane's mutable result needs the array runtime), so a minimal subseq program grows
  ~9.5 KB (3,908 -> 13,419); a program with none of them and no array op is untouched.

Both compilers route a site to the helper **only when `%subseq-runtime` really is among
the program's functions**, inlining otherwise -- an under-predicting gate costs sharing,
never correctness.

`programUsesGeneralArrayOp` (in `LispMacroExpander`, so `macro` owns it and
`codegen.jvm` reads it) is the ONE list of "this program can hold an array";
`JvmLispCompiler.programUsesAnyArrayOp` is that list plus its `concatenate` term.

## 2. `_arr_get` / `_arr_set` -- wasm runtime functions
A general array is a cell whose field 0 is the header cons `(dims . (meta . data))`;
when `data` is itself a cell the array is a DISPLACED VIEW, so a read adds the view
offset to the index and continues at the target's header, repeatedly.
`WasmArrayRuntimeBuilder` holds that walk plus `array.get`/`array.set` once per module,
replacing ~45 instructions and two never-released temps at each of the five accessor
sites (`aref` rank-1 and rank-n, `row-major-aref`, `%aset`, `%row-major-aset`). The
packed float and packed integer arms deliberately STAY inline -- the integer one is the
fused raw-`i64` store (`.kb/packed-integer-vectors.md`) a call would give up -- so a
site is still a representation dispatch, with only its general arm a call.

Indices: `FUNC_ARR_GET`/`FUNC_ARR_SET` appended after `FUNC_AS_F64` (new `FX_FUNC_LAST`),
`TYPE_ARR_SET` after `TYPE_UB_READ` (new `IARR_TYPE_LAST`); `_arr_get` reuses
`TYPE_BIG_SHIFT`, already `((ref null eq), i32) -> (ref null eq)`. Appended, so existing
`FUNC_*`/`TYPE_*` values are unchanged.

Marginal cost per extra site (wasm-GC, `--optimize`): `(subseq v i)` 2,316 -> 11;
`(aref v i)` ~342 -> 202; `(%aset v i x)` ~292 -> 187. Whole modules fell 4.5%-35.9%.

## Re-evaluation triggers
- `%schar-set-runtime` stays spelled `%subseq-core`, not `%subseq-runtime`: its rebuild
  runs only where `%arrayp` said no, so `%subseq-core` reaches the string lane directly
  while `%subseq-runtime` would re-test `stringp`/`%arrayp`.
- If `_arr_get`/`_arr_set` become hot, add a fast path at the SITE (a `ref.test` for the
  non-displaced shape before the call), not a return to inlining the walk.
- A string-only wasm program still pays the array arm through the helper (~5 KB for one
  `subseq`); declining it would need the wasm backend to trust
  `programUsesGeneralArrayOp` the way the JVM does -- a behavior change, separate work.

## Pinning tests
- `LispMacroExpanderTest.aSubseqSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch`
- `LispMacroExpanderTest.theSharedSubseqDispatchAnswersTheSameThingAsTheInlinedOne` --
  one body, two homes; the helper must not call `subseq` itself.
- `LispMacroExpanderTest.theGeneralArrayGateNamesTheOperatorsThatCanProduceOne` -- both
  directions, string-only case explicitly false.
- `WasmLispCompilerTest.anElementAccessSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime` --
  the byte budget. Nothing else notices: every arrangement compiles and runs correctly.
- Behavior pinned by the displaced-array and fill-pointer cases in
  `WasmLispCompilerIntegrationTest`, `JvmLispCompilerTest`, `ci-spec.yaml` sequence cases.
