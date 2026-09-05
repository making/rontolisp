# `subseq` and general-array element access are shared callees, not per-site code

**Invariant: no `subseq` site emits the array copy loop inline, and no `aref` / `%aset` /
`row-major-aref` / `%row-major-aset` site emits the displacement-chain walk inline. The program
carries each once.** Same lesson as `.kb/string-write-runtime.md`, `.kb/wasm-shared-coercion.md`.
The STRING lane answers a MUTABLE CHARACTER VECTOR (`_subseqCv` JVM, `_subseq_str` /
`FUNC_SUBSEQ_STR` wasm) -- `.kb/string-write-runtime.md`.

## `%subseq-runtime` -- a spliced defun, both compile paths
- `LispMacroExpander.expandSubseqCompat` dispatches on runtime type: string or cons ->
  `%subseq-core` (what the per-backend `subseq` compilers emit); a general array is copied
  element-wise into a fresh `%array-alike`. `subseqRuntimeWrapper()` is the callee; `end` is a
  PARAMETER, nil when omitted, so one call shape serves 2- and 3-arg calls.
- **Injection is the BACKEND's**, in the same loop that adds the `BuiltinFunctionWrappers`
  (`JvmLispCompiler` / `WasmLispCompiler`), not `expandTopLevelDefinitions`: most `subseq` sites
  live in wrapper bodies, which do not exist until the backend generates them.
- Gates -- wasm: any `subseq` call. JVM: that AND `programUsesAnyArrayOp`, because the copy arm
  names `aref`/`%aset` and would pull ~120 KB of array runtime into a string-only program.
  `subseq`/`copy-seq`/`replace` raise that gate themselves (~9.5 KB on a minimal program).
- A site routes to the helper only when `%subseq-runtime` really is among the program's functions,
  inlining otherwise -- an under-predicting gate costs sharing, not correctness.
- `LispMacroExpander.programUsesGeneralArrayOp` is the ONE list of "this program can hold an
  array"; `JvmLispCompiler.programUsesAnyArrayOp` is that list plus its `concatenate` term.
- `%schar-set-runtime` stays spelled `%subseq-core`, not `%subseq-runtime`.

## `_arr_get` / `_arr_set` -- wasm runtime functions
- A general array's field 0 is the header cons `(dims . (meta . data))`; a cell `data` means a
  DISPLACED VIEW, so a read adds the view offset and continues at the target's header, repeatedly.
  `WasmArrayRuntimeBuilder` holds that walk once per module for all five accessor sites.
- The packed float and packed integer arms deliberately STAY inline (the integer one is the fused
  raw-`i64` store, `.kb/packed-integer-vectors.md`).
- Indices: `FUNC_ARR_GET`/`FUNC_ARR_SET` appended after `FUNC_AS_F64` (new `FX_FUNC_LAST`),
  `TYPE_ARR_SET` after `TYPE_UB_READ` (new `IARR_TYPE_LAST`); `_arr_get` reuses `TYPE_BIG_SHIFT`.
  Appended, so existing `FUNC_*`/`TYPE_*` values are unchanged.
- If these become hot, add a fast path at the SITE, not a return to inlining the walk.

## Tests
- `LispMacroExpanderTest.aSubseqSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch`,
  `.theSharedSubseqDispatchAnswersTheSameThingAsTheInlinedOne` (the helper must not call `subseq`
  itself), `.theGeneralArrayGateNamesTheOperatorsThatCanProduceOne`.
- `WasmLispCompilerTest.anElementAccessSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime` -- the byte
  budget; nothing else notices, every arrangement compiles and runs correctly.
- Displaced-array and fill-pointer cases in `WasmLispCompilerIntegrationTest`,
  `JvmLispCompilerTest`, `ci-spec.yaml`.
