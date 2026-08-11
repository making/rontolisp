# `replace` / `fill` / `map-into` are shared callees, not per-site code

**Invariant: no `replace`, `fill` or `map-into` site emits its runtime dispatch
inline when the program carries the matching helper. The program carries each of
them once.**

Same lesson as `.kb/subseq-runtime.md` (its direct precedent -- read that file's
mechanics first, this one records only the deltas), `.kb/seq-conversion-runtime.md`,
`.kb/string-write-runtime.md` and `.kb/format.md`'s `%fixed-decimal`: a per-site
expansion that grew past a few hundred bytes becomes a callee.

## Why these three were the last ones left

`subseq`, `coerce`, `search` and `concatenate` had already become calls; these
three were still spelled out at every call site. Marginal cost of one more site,
wasm-GC at `--optimize=size`, before this change:

| operator | bytes per site |
| --- | ---: |
| `replace` | 3,806 |
| `map-into` | 1,949 |
| `fill` | 1,718 |
| `sort` | 533 |
| `position` | 449 |
| `reduce` | 105 |
| `search` | 15 |
| `concatenate` | 23 |
| `subseq` | 15 |

`replace` is the expensive one because its body is three runtime arms -- a
`%row-major-aset` copy loop over an `aref`/`elt` source split, a list rewrite, and
an immutable-string rebuild made of three `subseq`s and a `concatenate` -- and every
one of those is itself a representation dispatch. `CHIPZ::UPDATE-WINDOW` was
**18,149 B, 9.5% of the whole zlib module**, for thirty lines of Lisp whose body is
four `replace` calls.

## The lowering

Three builders in `LispMacroExpander`, each the body its `expand*` used to inline:

- `replaceRuntimeWrapper()` -- `(%replace-runtime seq1 seq2 start1 end1 start2 end2)`
- `fillRuntimeWrapper()` -- `(%fill-runtime seq item start end)`
- `mapIntoRuntimeWrapper(n)` -- `(%map-into-runtime-<n> result fn s0 ... s<n-1>)`

**The bounds are PARAMETERS, nil meaning "the default".** That is what the inline
form's `(or expr default)` wrapper already allowed at run time, so ONE call-site
shape serves every keyword combination -- the same trick as `%subseq-runtime`'s nil
`end`. Argument order is the canonical keyword order, which is the order the inline
`let*` bound them in, so evaluation order is unchanged.

**`map-into` gets one helper per SOURCE-SEQUENCE COUNT**, not one taking the sources
as a list. Its loop body is a `funcall` of exactly that many arguments; a list would
need an `apply`, and `_apply` drags in the spread dispatcher, which on this very
module is 12 KB -- an order of magnitude more than the sharing saves. The count is
read off the call shape (`LispMacroExpander.sequenceOpRuntimeWrappers`), capped at 8
(`MAX_MAP_INTO_SOURCES`) so a helper cannot outgrow a callable arity.

Routing is per site, at the compilers' existing `REPLACE` / `FILL` / `MAP_INTO`
cases, on `ctx.functions.containsKey(<helper name>)`. So a gate that under-predicts
costs the module its sharing and never its correctness -- the inline form is still
emitted and still right.

## Injection

**The BACKEND's**, in the same loop that adds the `BuiltinFunctionWrappers`
(`JvmLispCompiler` / `WasmLispCompiler`), for the reason `.kb/subseq-runtime.md`
gives: the `#'replace` and `#'fill` wrapper bodies are sites of their own, and they
do not exist until the backend generates them.

**Before the `%subseq-runtime` injection, and scanned by it.** The `replace` and
`fill` bodies call `subseq` themselves, so the subseq gate takes the seq-op helpers
as a third form group; without that a `replace`-only program would carry three
inlined subseq dispatches inside the helper.

The three are **gated apart** -- unlike the `seqConversionWrappers` trio, which must
travel together because which arm of ITS dispatch runs is a runtime fact. These are
three independent lowerings, so an unused one is dead weight, not a correctness hole.

**The two backends gate differently, the same asymmetry as `%subseq-runtime` and for
the same reason:** wasm injects on the name scan alone; the JVM additionally requires
`programUsesAnyArrayOp`, because each body's destructive arm names
`aref`/`%row-major-aset` and mis-firing the array gate costs ~120 KB. When the JVM
gate is off, every site keeps its inline lowering, so nothing calls a missing helper.

The interpreter never sees any of this: `replace` and `fill` are native built-ins
there, and its `map-into` expands the inline form.

## The list arm of `replace`, and why it landed here

`replace` into a LIST was broken on both compile paths and had been since the
lowering existed: a list `seq1` is not `%arrayp`, so it fell through to the
immutable-string rebuild. The JVM threw `ClassCastException`, WASM printed the three
subsequences concatenated as text (`"(1)(9 9)(4 5)"`), and `(setf (subseq l ...))` on
a list -- which lowers to `replace` -- was a **silent no-op** on WASM, the rebuilt
string dropped in statement position. Only the interpreter's native `replace` was
right.

The dispatch is now `%arrayp` -> element store, else `listp` -> a `nthcdr`/`rplaca`
cursor walk (what `fill` already did), else the string rebuild. **Fixing it here is
why it was affordable**: the arm costs 641 bytes, and in the shared runtime a program
pays that once instead of once per site (chipz has eight). Pinned on all four
backends by the `replace-into-a-list` case in `ci-spec.yaml`, plus
`JvmLispCompilerTest.compileAndRunReplaceIntoAList` and
`WasmLispCompilerIntegrationTest.replaceIntoAList`.

## What it bought

Marginal cost of one more site, wasm-GC at `--optimize=size`:

| site | before | after |
| --- | ---: | ---: |
| `(replace a b :start1 i :start2 1 :end2 9)` | 3,806 | 21 |
| `(map-into a #'1+ b)` | 1,949 | 17 |
| `(fill a i :start 1)` | 1,718 | 15 |

Whole modules, before/after measured in one session against the same parent commit
(macOS, so the absolute numbers differ from the CI-measured `size-report/results/`;
the deltas are the point):

| program | before | after | |
| --- | ---: | ---: | ---: |
| `size-report zlib` `--optimize` | 243,840 | 197,812 | **-18.9%** |
| `size-report zlib` `--optimize=size` | 191,872 | 166,286 | -13.3% |
| `size-report zlib` (none) | 423,094 | 367,174 | -13.2% |
| `size-report zlib` `--component --optimize=size` | 196,613 | 171,027 | -13.0% |
| Worker `hello-ningle` | 2,656,475 | 2,593,313 | -2.4% |
| Worker `httpbin-ningle` | 2,662,312 | 2,599,154 | -2.4% |
| `browser/wasm-browser/hello` (none) | 127,260 | 126,184 | -0.8% |
| `browser/minesweeper` | 142,715 | 141,655 | -0.7% |
| `browser/hiragana` (`infer`) | 349,424 | 348,462 | -0.3% |

`CHIPZ::UPDATE-WINDOW` left the artifact's largest-function list entirely; what
replaced it is one 4,463-byte `%REPLACE-RUNTIME`. `hello_world` and `pi_approx` are
byte-identical at `--optimize`/`--optimize=size` (no site survives the shake); the
other Workers and browser artifacts move by +12/+4 bytes of index re-encoding where
the helper is injected and then fully shaken out, the same residue
`.kb/seq-conversion-runtime.md` records.

## The re-evaluation trigger

- **A program with EXACTLY ONE site of an operator pays a little more** -- measured
  +419 bytes for one `replace` at `--optimize=size`, because the helper body plus the
  call is slightly larger than the one inline copy it replaces. Break-even is between
  one and two sites, and from two on it is not close. A site-count threshold was
  considered and rejected: at `--optimize` the un-shaken `#'replace` wrapper is itself
  a site, so counting before the shake answers 2 for a one-site program and the
  threshold would not fire where it was wanted. If this ever matters, the honest fix
  is to ask the SHAKER, not the pre-expansion scan.
- **The un-shaken `(none)` build of a program with no site of its own grew by 132
  bytes**, because the wrapper catalog now carries the helper as well as the wrapper.
  That is the `replace` list arm's 641 bytes minus the sharing; before the list arm
  the same build was 1,076 bytes SMALLER than the baseline.
- **If a helper becomes hot.** The bodies are the same code as before, just emitted
  once, and each call is one extra frame per `replace`/`fill`/`map-into` CALL, not per
  element -- chipz's inflate loop shows no measurable change. The packed-integer fused
  store still fires inside the helper: it is a syntactic match on the
  `(%row-major-aset dst i (aref src j))` shape, which the shared body still spells
  (`.kb/packed-integer-vectors.md`). If a site ever needs more, the answer is a fast
  path at the SITE before the call, never re-inlining the dispatch.

## The spread dispatcher on this module was NOT an over-approximating scan

`_dispatch_spread` was another 12,156 B of the zlib artifact, and chipz's only literal
`apply` does not force it (a literal `#'f` target compiles to a direct call). What
turned `needsApplyRuntime` on was **`WasmArityBundler.spreadOverArityFuncalls`**: a
`funcall` with more arguments than the per-arity dispatchers can take was rewritten
into `(apply f (list ...))`, and chipz's

```lisp
(funcall fun state input output :input-start s :input-end e
                                :output-start s :output-end e)
```

is eleven of them. The designator is a variable, so the rewritten `apply` needed
`_apply` and the spread dispatcher for real -- the per-arity dispatchers take one
wasm parameter per Lisp argument and stop at the ceiling. Nothing there was
over-approximating; the gate was right, and the ceiling was the cause.

**Which is now derived, not fixed at 10.** A call site 11..14 arguments wide gets its
own per-arity dispatcher, APPENDED after the fixed block so no existing index moves
(`MAX_CALLABLE_ARITY` is an index origin -- see `.kb/wasm-callable-arity.md` for the
mechanics and the audit). Nothing spreads chipz's call any more, `needsApplyRuntime`
answers false, and the spread dispatcher goes back to being the stub it should have
been: `_dispatch_11` costs 975 B where it cost 12,156. zlib `--optimize=size`
149,054 -> **137,430 (-7.8%)**.

## Pinning tests

- `LispMacroExpanderTest.aDestructiveSequenceOperatorSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch`
  -- each site routes with the helper and keeps the inline lowering without it;
  `map-into` routes to the helper of its own source count.
- `LispMacroExpanderTest.theSharedSequenceOpDispatchesAnswerTheSameThingAsTheInlinedOnes`
  -- one body, two homes; the bounds are or-defaulted parameters; no helper calls its
  own operator.
- `LispMacroExpanderTest.theSequenceOpRuntimeGateInjectsOnlyTheHelpersThatWouldHaveACaller`
  -- the injection gate, both directions, one `map-into` helper per source count, and
  that the helpers reach `subseq` (so the subseq gate must scan them).
- `WasmLispCompilerTest.aDestructiveSequenceOperatorSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime`
  -- the marginal byte budgets above. Nothing else notices: every arrangement compiles
  and runs correctly.
- The behavior itself is pinned where it already was, plus the new list arm:
  `replace-into-a-list` and the sequence cases in `ci-spec.yaml` (all four backends),
  `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`.
