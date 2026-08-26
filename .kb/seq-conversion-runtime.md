# The literal sequence conversions are shared callees, not per-site code

**Invariant: no literal `(coerce x 'list/'string/'vector)` site -- including the
string/vector dispatch every generic sequence lowering wraps its scan in -- emits the
conversion body inline when the program carries the `%seq-to-list` / `%seq-to-string` /
`%seq-to-vector` trio. The program carries each conversion at most once.**

Same lesson as `.kb/subseq-runtime.md` (its direct precedent -- read that file's
mechanics first, this one only records the deltas), `.kb/string-write-runtime.md`,
`.kb/wasm-shared-coercion.md` and `.kb/format.md`'s `%fixed-decimal`: a per-site
expansion that grew past a few hundred bytes becomes a callee.

## The lowering

Every generic sequence operator's expansion funnels its representation handling into
two `LispMacroExpander` builders: `seqResultDispatchForm` (bind, `stringp`/`vectorp`,
convert to list, run the scan, convert back -- `reverse`, `remove`/`-if`/`-if-not`,
`substitute`/`-if`/`-if-not`, `remove-duplicates`/`delete-duplicates`, the
`sort`/`stable-sort` string wrap) and `seqAsListForm` (convert to list only -- the
`position`/`find` family, `count`/`count-if`, `every`/`some`/`notany`/`notevery`,
`reduce`'s keyword forms). Both spell the conversions as literal `coerce` FORMS, and
`(coerce x 'list)` / `'string` each inline a whole `map` loop (the string builder
drags `princ-to-string` and with it the value printer), `'vector` a `make-array` fill
loop: **8-10 KB of wasm-GC PER SITE**, measured one-site against a 5,165-byte base at
`--no-wasi --optimize` (list 8.8 KB, string 10.6 KB, vector 9.3 KB; one `reverse`,
which carries all three plus a reduce, 23.9 KB).

A program that takes any function as a value carries the whole
`BuiltinFunctionWrappers` catalog, and each wrapper body is one such lowering -- 217 KB
of a 261 KB minesweeper was exactly this (`.todo/288`'s finding, now closed).

## The trio and its routing

`LispMacroExpander.seqConversionWrappers()` answers the three definitions,
wrapper-shaped (`(setq %seq-to-list (lambda (%stl_x) ...))`), each body the SAME
conversion the inline lowering spells (`coerceToListBody(x, true)` /
`coerceToStringBody(x, true)` / `coerceToVectorBody(x)`). None contains a literal
`coerce` form -- that would re-enter the routing forever.

**One routing point serves every operator.** The builders keep emitting literal
`coerce` forms; the compilers re-enter them through their `coerce` case
(`JvmExprCompiler` / `WasmExprCompiler`), which calls
`expandCoerce(cons, arraysExist, helpersPresent)` with `helpersPresent =
ctx.functions.containsKey(%seq-to-list)`. With the trio present a literal conversion
is ONE call (`(%SEQ-TO-LIST X)`), and the computed-type dispatch
(`expandComputedCoerce`) routes each of its arms the same way; without it the
pre-existing inline lowering comes back, so a gate that under-predicts costs the
module its sharing and never its correctness. No expander signature changed -- that is
what routing at the `coerce` case buys, and why `expandMap`'s `'vector` path returns a
coerce FORM instead of pre-expanding it.

**Injection is the BACKEND's**, in the same place as the `%subseq-runtime` helper
(right after `BuiltinFunctionWrappers.generate`, where most conversion sites live --
`JvmLispCompiler` / `WasmLispCompiler`), gated by
`LispMacroExpander.programUsesSeqConversion` over the program and the generated
wrappers: a pre-expansion name scan (`SEQ_CONVERSION_USERS`) of every operator whose
lowering can reach a conversion. `nreverse` is deliberately NOT on that list -- it is
an in-place cons splice with no dispatch (a site is ~0.2 KB). The trio always travels
together: which arm of a dispatch runs is a runtime fact.

**The two backends gate injection differently, the same asymmetry as
`%subseq-runtime` and for the same reason:** wasm injects on the name scan alone; the
JVM additionally requires `programUsesAnyArrayOp` (the trio's vector arms name
`aref`/`%aset`/`make-array`, exactly what the array-runtime gate scans for --
mis-firing it costs ~120 KB, `.kb/subseq-runtime.md`). When the JVM gate is off, the
coerce case inlines the vector-arm-free dispatch as before, so nothing calls the
missing trio.

The interpreter never sees any of this: `LispEvaluator`'s `coerce` case calls the
one-argument `expandCoerce`, which is `helpersPresent` false by definition.

## What it bought

Marginal cost of one more site, wasm-GC (`WasmLispCompilerTest` budgets; the
before-numbers are the same sites against the pre-trio compiler):

| site | before | after |
| --- | ---: | ---: |
| `(coerce v 'list)` | ~8,800 | 66 |
| `(reverse v)` | ~7,215 | 489 |
| `(position i v)` | ~6,699 | 591 |
| `(remove i v)` | ~7,656 | 852 |

What stays at a site is its own scan loop with the `:test`/`:key` designators still
inlined -- the answer and the evaluation order are the SAME forms as before, only the
conversion arms became calls.

Whole modules, before/after measured in one session against the same parent commit
(`--no-wasi --optimize` unless noted):

| program | before | after | |
| --- | ---: | ---: | ---: |
| `browser/wasm-browser/hello` (none) | 257,475 | 124,959 | **-51.5%** |
| `browser/minesweeper` | 255,102 | 140,192 | **-45.0%** |
| `browser/hiragana` (`infer`, `--optimize`) | 789,854 | 540,291 | -31.6% |
| `browser/webgl-robot-arm` | 305,230 | 229,768 | -24.7% |
| `browser/webgl-battlefront` | 474,237 | 382,469 | -19.4% |
| `browser/rainbow` | 38,170 | 32,035 | -16.1% |
| `browser/webgl-heat3d` | 71,997 | 61,308 | -14.8% |
| cl-ppcre probe (`--optimize=size`, `.todo/297`) | 748,091 | 678,977 | -9.2% |

`webgl-cube`, `wasm-browser/dice`/`greet`, and `hello --optimize` are byte-identical
(no conversion site survives, or none exists); `webgl-galaxy`/`webgl-platformer`/
`webgl-triangle` are size-identical +-1 byte with reindexing residue (the trio is
injected, then fully shaken out). The JVM wrapper catalog's biggest bodies went
7-11 KB -> 0.8-2.6 KB of bytecode (`POSITION` 2,582 is now the largest).

## The re-evaluation trigger

- **If a conversion becomes hot.** `%seq-to-list` still walks `map`'s `nth`-based
  index loop (quadratic on long lists) -- that predates this change and is the same
  code, just emitted once. The fix would be a better loop INSIDE the helper (one place
  now), or a `listp` fast path at the SITE before the call -- never re-inlining.
  `seqResultDispatchForm` already skips the call entirely for a list input; only
  `seqAsListForm` sites call unconditionally, and the helper's `listp` arm returns the
  input untouched.
- **If the residual per-site scan loops (0.5-0.9 KB) ever matter**, the next lever is
  per-OPERATOR callees with the `:test`/`:key` designators as runtime parameters
  (nil = default), stacked on top of this mechanism. That was `.todo/288`'s "other
  half"; it pays only where one operator recurs many times in USER code, because each
  wrapper body is already one definition. Measured for cl-ppcre (todo-297 follow-up,
  2026-08-08): the engine has only ~28 such sites (reverse 4, mapcar 6, position
  family 3, find 3, count 4, one or two each of the rest), a ~2% bound on that
  module -- the real density there was the CLOS dispatcher tails, taken instead
  (`.kb/clos.md`, `.kb/optimize-dead-code-elimination.md`). Re-open this lever only
  for a module measured to have dozens of sites of ONE operator.
- **A string-only JVM program keeps the inline (vector-arm-free) dispatch** because
  the array gate keeps the trio out. Same trade as `%subseq-runtime`, same answer: the
  gate under-predicting costs bytes, never correctness.

## The computed-coerce fall-through is a `typep`, and that couples two gates

A COMPUTED result type (`(coerce x type)` with `type` a variable) dispatches on the
designator's head over the float, list, string and vector families, and what is left
over -- `fixnum`, `character`, a class name, anything -- lands in one arm that applies
CLHS's general rule: **"if the object is already of the specified type, it is
returned"**, spelled `(if (typep x spec) x (error ...))`. Nothing else is honest: the
alternative is a second, growing list of type names. iterate's `make-initial-value`
is the consumer that forced it (`(coerce 0 type)` for every `iter` clause carrying a
`:type`), and with it cl-sqlite loads (`.kb/cffi.md`).

The coupling this creates on the compile paths: that arm is a computed `typep`, i.e. a
call to the shared `%typep-runtime` defun, which `expandTopLevelDefinitions` injects
only when `LispMacroExpander.needsRuntimeTypep` says so -- and that scan sees the
SOURCE program, never the injected wrappers. So three things must stay in step:

- the scan counts a computed `coerce` beside a computed `typep`, and `(function
  coerce)` beside `(function typep)` -- including inside QUOTED data, because the
  wrapper's own gate (`BuiltinFunctionWrappers.referencesFunctionValue`) walks in
  there;
- `#'coerce` is in `REFERENCE_GATED_FUNCTIONS`, so its wrapper -- whose body IS a
  computed coerce -- is injected only for a program that names it, the `#'typep`
  precedent exactly;
- the `#'map` wrapper dispatches its result type onto three LITERAL coerces instead of
  handing it to a computed one. It rides the EVAL runtime's gate rather than a name of
  its own, so no reference scan could ever see it coming.

## Pinning tests

- `LispMacroExpanderTest.aCoerceSiteIsOneCallWhenTheProgramCarriesTheSharedConversions`
  -- literal and computed sites route; the float type never routes; without the trio
  the inline lowering comes back.
- `LispMacroExpanderTest.theSharedConversionsAnswerTheSameThingAsTheInlinedOnes` --
  the trio is the inline bodies verbatim, and none spells a literal `coerce`.
- `LispMacroExpanderTest.theSeqConversionGateNamesTheOperatorsThatCanReachAConversion`
  -- the injection gate, both directions, `nreverse` explicitly off it.
- `WasmLispCompilerTest.aSequenceOperatorSiteDoesNotCarryItsOwnCopyOfTheSharedConversions`
  -- the marginal byte budgets above. Nothing else notices: every arrangement compiles
  and runs correctly.
- The behavior itself is pinned where it already was: the sequence cases in
  `ci-spec.yaml` (all four backends), `LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest`, and `ExamplesE2eTest` over every checked-in
  browser artifact.
