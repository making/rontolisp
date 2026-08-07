# `subseq` and the general-array element access are shared callees, not per-site code

**Invariant: no `subseq` site emits the array copy loop inline, and no `aref` /
`%aset` / `row-major-aref` / `%row-major-aset` site emits the displacement-chain
walk inline. The program carries each of them once.**

Two helpers, at two different levels, for the same reason -- a per-site expansion
that grew past a few hundred bytes becomes a callee. Same lesson as
`.kb/string-write-runtime.md` (a spliced Lisp defun), `.kb/wasm-shared-coercion.md`
(a wasm runtime function) and `.kb/format.md`'s `%fixed-decimal` (a compiler
primitive).

## 1. `%subseq-runtime` -- a spliced defun, both compile paths

`LispMacroExpander.expandSubseqCompat` lowers `(subseq seq start [end])` to a
dispatch on the RUNTIME type of `seq`: a string or a cons chain goes to
`%subseq-core` (the lane the per-backend `subseq` compilers emit), a general array
is copied element by element into a fresh `%array-alike`. That array arm is a
`dotimes` whose body is an `aref` and a `%aset`, each of which is itself a
multi-arm representation dispatch, so the whole thing was **2,316 bytes of wasm at
every site** -- and string-only code paid it, because nothing in `(subseq s i j)`
says `s` is not a vector. `replace` carries three, `string-capitalize` three, the
`format` number renderers several more.

`LispMacroExpander.subseqRuntimeWrapper()` is the callee. Its `end` is a
PARAMETER, nil when the caller omitted it -- both backends' `%subseq-core` lane
already accepts a runtime nil there and defaults to the length -- which is what
lets ONE call-site shape, `(%subseq-runtime seq start end)`, serve the
two-argument and three-argument calls alike.

**Injection is the BACKEND's**, in the same loop that adds the
`BuiltinFunctionWrappers` (`JvmLispCompiler` / `WasmLispCompiler`), not
`expandTopLevelDefinitions`. That is deliberate and was the second attempt: most
`subseq` sites in a wrapper-carrying program live in those wrapper bodies, and the
wrappers do not exist until the backend generates them. Injecting from the
top-level pass left `minesweeper` -- 250 KB of wrappers, no `subseq` in its own
source -- completely unimproved.

**The two backends gate it differently, and the difference is the point:**

- **wasm**: whenever the program (or a generated wrapper) calls `subseq`. This
  backend holds every representation unconditionally, so the helper is a strict
  improvement wherever there is a caller.
- **JVM**: that, AND `programUsesAnyArrayOp`. The helper's copy arm names `aref`
  and `%aset`, which is exactly what the JVM's array-runtime gate scans for, so
  injecting it into a string-only program would pull **~120 KB** of array runtime
  into a class with no use for it (measured: 174,682 -> 297,121 on
  `(print (subseq "hello" 1 3))`). When the gate is off, `JvmSubseqCompiler`
  declines the rewrite anyway, so nothing calls it.

Both compilers route a site to the helper **only when `%subseq-runtime` really is
among the program's functions**, and inline the dispatch otherwise. So a gate that
under-predicts costs the module its sharing and never its correctness -- which is
what makes the asymmetry above safe to have.

`programUsesGeneralArrayOp` (in `LispMacroExpander`, so `macro` owns it and
`codegen.jvm` reads it) is the ONE list of "this program can hold an array":
`JvmLispCompiler.programUsesAnyArrayOp` is that list plus its `concatenate` term.
One list, so the gate and the array runtime cannot drift apart.

## 2. `_arr_get` / `_arr_set` -- wasm runtime functions

A general array is a cell whose field 0 is the header cons
`(dims . (meta . data))`. When `data` is itself a cell the array is a DISPLACED
VIEW, and a read has to add that view's offset to the index and continue at the
target's header, repeatedly. `WasmArrayRuntimeBuilder` is that walk plus the
`array.get` / `array.set`, once per module; it used to be spelled out at all five
accessor sites (`aref` rank-1 and rank-n, `row-major-aref`, `%aset`,
`%row-major-aset`), ~45 instructions and **two never-released temps** each.

The packed float and packed integer arms deliberately STAY inline at the site: the
integer one is the fused raw-`i64` store (`.kb/packed-integer-vectors.md`), which a
call would give up. So a site is still a representation dispatch -- it is only its
general arm that became a call.

Indices: `FUNC_ARR_GET` / `FUNC_ARR_SET` are appended after `FUNC_AS_F64` (the new
`FX_FUNC_LAST`), and `TYPE_ARR_SET` after `TYPE_UB_READ` (the new
`IARR_TYPE_LAST`); `_arr_get` reuses `TYPE_BIG_SHIFT`, already
`((ref null eq), i32) -> (ref null eq)`. Appended, so every existing `FUNC_*` /
`TYPE_*` keeps its value.

## What it bought

Marginal cost of one more site, wasm-GC at `--optimize`:

| site | before | after |
| --- | ---: | ---: |
| `(subseq v i)` | 2,316 | 11 |
| `(aref v i)` | ~342 | 202 |
| `(%aset v i x)` | ~292 | 187 |

Whole modules, `--no-wasi --optimize` unless noted:

| program | before | after | |
| --- | ---: | ---: | ---: |
| `browser/hiragana` (`infer`, `--optimize`) | 1,232,436 | 789,854 | **-35.9%** |
| `browser/rainbow` | 49,774 | 38,170 | -23.3% |
| `browser/minesweeper` | 303,308 | 255,102 | -15.9% |
| `browser/webgl-battlefront` | 558,732 | 476,314 | -14.8% |
| `browser/webgl-robot-arm` | 360,982 | 307,808 | -14.7% |
| `browser/webgl-platformer` | 140,177 | 123,250 | -12.1% |
| `browser/webgl-heat3d` | 81,052 | 71,897 | -11.3% |
| `browser/webgl-cube` | 37,202 | 33,669 | -9.5% |
| `browser/webgl-galaxy` | 25,620 | 24,476 | -4.5% |

`wasm-size/pi_approx` and `hello_world` are byte-identical at every `--optimize`
level (neither reaches an array), and their un-shaken `(none)` builds fell 307 KB
-> 257 KB because the wrapper catalog itself shrank.

## The re-evaluation trigger

- **`.kb/string-write-runtime.md` said `%schar-set-runtime` could go back to a
  plain `subseq` "if subseq on a string ever becomes one call on both compile
  paths".** It now is -- but the answer is still no: the helper's rebuild runs only
  where `%arrayp` said no, so `%subseq-core` reaches the string lane DIRECTLY,
  while `%subseq-runtime` would re-test `stringp`/`%arrayp` first. The spelling
  there stays `%subseq-core` on purpose.
- **If `_arr_get`/`_arr_set` become hot**, the answer is a fast path at the SITE (a
  `ref.test` for the non-displaced shape before the call), not a return to
  inlining the walk. The three programs that would show it -- `webgl-robot-arm`,
  `webgl-heat3d`, `hiragana` -- all still run at their frame budget with the call.
- **A string-only wasm program still pays the array arm** through the helper
  (`c2.lisp`-shaped probes: ~5 KB for one `subseq`). Declining the array arm there
  would need the wasm backend to trust `programUsesGeneralArrayOp` the way the JVM
  does, which is a behavior change and not just a size one -- so it is a separate
  decision, not part of this.

## Pinning tests

- `LispMacroExpanderTest.aSubseqSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch`
  -- the site is `(%SUBSEQ-RUNTIME ...)` with the helper, and the pre-existing
  inline lowering without it.
- `LispMacroExpanderTest.theSharedSubseqDispatchAnswersTheSameThingAsTheInlinedOne`
  -- one body, two homes; and the helper must not call `subseq` itself.
- `LispMacroExpanderTest.theGeneralArrayGateNamesTheOperatorsThatCanProduceOne` --
  the shared gate, in both directions, with the string-only case explicitly false.
- `WasmLispCompilerTest.anElementAccessSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime`
  -- the byte budget above. Nothing else notices: every arrangement of this code
  compiles and runs correctly.
- The behavior is pinned where it already was: the displaced-array and
  fill-pointer cases in `WasmLispCompilerIntegrationTest`, `JvmLispCompilerTest`
  and the `ci-spec.yaml` sequence cases.
