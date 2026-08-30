# `replace` / `fill` / `map-into` are shared callees, not per-site code

**Invariant: no `replace`, `fill` or `map-into` site emits its runtime dispatch
inline when the program carries the matching helper. The program carries each of
them once.**

**Second invariant (todo-325): the shared `replace` / `fill` runtime is TWO
functions -- the wide dispatch and its `%arrayp` arm -- and a site whose
DESTINATION is provably an array calls the arm directly. A program with no
unproven site leaves the wide dispatch without a caller for the shaker.** See
"Narrowing the arms to the ones a site can reach" below for the soundness
argument that qualifies the sharing argument above it.

Same lesson as `.kb/subseq-runtime.md` (its direct precedent -- read that file's
mechanics first, this one records only the deltas), `.kb/seq-conversion-runtime.md`,
`.kb/string-write-runtime.md` and `.kb/format.md`'s `%fixed-decimal`: a per-site
expansion that grew past a few hundred bytes becomes a callee.

**The bulk-copy arm (todo-413, 2026-08-16).** `%replace-runtime-array`'s element
loop is fronted by `(%replace-bulk dst src s1 s2 n)` (`LispNames.REPLACE_BULK`,
emitted ONLY in the narrow helper's body by `replaceDispatch`; inline
`SeqOpArms.ALL` sites keep the plain loop): true = the n elements moved in one
engine-level copy, nil = nothing happened and the loop runs. On the wasm-GC
backend (`WasmArrayCompiler.compileReplaceBulk`) it fires when both sequences
are DISTINCT packed integer vectors of the SAME width, the bounds are
non-negative i31s and both ranges are in bounds -- then it is one `array.copy`
-- and declines everything else so the loop keeps owning the error shape, the
overlap-forward semantics of a same-object replace, and every mixed-width /
general-array pair. The JVM compiles the form to constant nil (no bulk path
yet; its element loop was never the JVM profile's problem -- `.todo/412`); the
interpreter never sees it (native `replace`). The driver: ironclad's HMAC
re-keys per PBKDF2 iteration, and `(replace padded-key key)` + the block fills
put ~13% of the wasm profile into the element loop's checked-add/`_iv_set`
machinery.

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

Builders in `LispMacroExpander`, each the body its `expand*` used to inline:

- `replaceRuntimeWrapper()` -- `(%replace-runtime seq1 seq2 start1 end1 start2 end2)`
- `fillRuntimeWrapper()` -- `(%fill-runtime seq item start end)`
- `mapIntoRuntimeWrapper(n)` -- `(%map-into-runtime-<n> result fn s0 ... s<n-1>)`
- `replaceArrayRuntimeWrapper()` / `fillArrayRuntimeWrapper()` -- the same call-site
  shapes for `%replace-runtime-array` / `%fill-runtime-array`, the `%arrayp` arm of
  the two above (todo-325, below)

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
emitted and still right. (`replace`/`fill` route on a second fact as well, below.)

## Injection

**The BACKEND's**, in the same loop that adds the `BuiltinFunctionWrappers`
(`JvmLispCompiler` / `WasmLispCompiler`), for the reason `.kb/subseq-runtime.md`
gives: the `#'replace` and `#'fill` wrapper bodies are sites of their own, and they
do not exist until the backend generates them.

**Before the `%subseq-runtime` injection, and scanned by it.** The `replace` and
`fill` bodies call `subseq` themselves, so the subseq gate takes the seq-op helpers
as a third form group; without that a `replace`-only program would carry three
inlined subseq dispatches inside the helper.

The three OPERATORS are **gated apart** -- unlike the `seqConversionWrappers` trio,
which must travel together because which arm of ITS dispatch runs is a runtime fact.
These are three independent lowerings, so an unused one is dead weight, not a
correctness hole. `replace` and `fill` each inject a PAIR, though, because which of
the two a site can reach is a compile-time fact this scan cannot see yet (below).

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

## Narrowing the arms to the ones a site can reach (todo-325)

Sharing made a SITE 21 bytes; it did not make the runtime small. One
`%REPLACE-RUNTIME` was **4,463 B, 3.2% of the zlib `--optimize=size` artifact and
its second largest function**, because that one copy is three representation arms
wide -- and chipz replaces into `(unsigned-byte 8)` vectors and nothing else.
Measured attribution of the 4,445-byte body (same helper in a two-site probe):

| part | bytes |
| --- | ---: |
| prologue -- the seven `let*` bindings, the two `length` defaults, `min` | 789 |
| destructive arm, `aref` source loop | 964 |
| destructive arm, list source loop (was `elt` per element) | 1,570 |
| list destination rewrite + immutable-string rebuild + the `%arrayp` test | 1,097 |

Two changes, and the order matters -- the second is only worth having because the
first made the destructive arm cheap enough to be the whole helper:

**1. A LIST source is walked with a cursor, not indexed with `elt`.** That arm is
reached only under `(listp seq2)`, so every `elt` there was a list access: an
`nth` walk from the head per element (quadratic) through a full representation
dispatch, where `car` is one field read. It is now the same `nthcdr`/`cdr` cursor
the list DESTINATION arm has always used, with the same `(null cell)` stop.
**1,570 -> ~450 bytes, and O(n^2) -> O(n)**; this alone is why the un-shaken
`(none)` build got smaller rather than larger.

**The `(null cell)` stop changes an INVALID call's behavior on the compile paths,
deliberately.** `(replace <array> '(1 2) :end2 4)` names a bounding index the
source does not have, which CLHS requires to be valid. The compile paths used to
answer `#(1 2 NIL NIL 0)` -- `elt` past a list's end is nil here, so the loop
stored nils (and a PACKED destination trapped on the first one); they now copy
what there is and stop, which is what the destination arm already did and what
the interpreter answers for the mirror case `(replace (list 1 2) #(5 6 7 8) :end1
4)` -> `(5 6)`. The interpreter's native `replace` still SIGNALS on the source
side (`sequence-ref: index 2 out of range`), so the three-way disagreement in this
one invalid case is now interpreter-signals / compile-paths-truncate instead of
interpreter-signals / compile-paths-store-nil. **Re-evaluation trigger: if the
error case is ever made uniform, the honest fix is the interpreter's check on both
sides of both arms, not an `elt` per element back in the loop.**

**The `search`/`mismatch` prelude bodies took the same cursor and could NOT take
the same stop** (2026-08-31, `.kb/seq-coerce-runtime.md`): the interpreter's
native scan arm declines every out-of-range bound specifically so those bodies
keep owning what they answer there, so their cursor falls back to the original
`elt` call instead of stopping, and the rewrite changed no answer at all. That is
also why theirs GREW (+634 B of `--optimize=size` wasm a body) where this one
shrank -- a branch in front of the `elt` loop rather than a replacement for it.

### The list DESTINATION arm read its source with `elt` for another fifteen days

**Fixed 2026-08-31.** The change above gave the `%arrayp` arm's LIST SOURCE a
cursor and left the arm six lines below it -- the list DESTINATION rewrite --
still spelling `(rplaca cell (elt r2 (+ vs2 k)))`. Its destination had walked a
`nthcdr`/`cdr` cursor since the arm existed; its SOURCE had not. So a
list-into-list `replace` -- which is what `(setf (subseq <list> ...))` lowers to
-- stayed quadratic on all three compile paths. ms per call, 4,000 elements on
both sides, one locked run:

| `(replace <4000-list> <4000-list>)` | interpreter | JVM | WASM p1 | `--component` |
| --- | ---: | ---: | ---: | ---: |
| before | 10.6 | 11.35 | 16.5 | 16.6 |
| **after** | **0.023** | **0.019** | **0.051** | **0.051** |

**This cursor could not take the `(null cell)` stop either**, for the reason the
prelude bodies could not: the stop belongs to the destination walk, which owns
running out of cells, and putting one on the SOURCE would change what a bad
`:start2`/`:end2` answers a second time. It falls back to the same `elt` call
instead -- `(if (consp c) (prog1 (car c) (setq c (cdr c))) (elt r2 (+ vs2 k)))`,
seeded `(nthcdr start2 source)` only when the source is a `listp` and `start2` is
a non-negative integer, so a non-list source, a computed non-integer bound and a
negative one all keep indexing exactly as they did. It costs **+248 B once**,
inside `%REPLACE-RUNTIME`, and **0 per site** (43 B before and after) -- the
sharing this file is about, working as designed.

**The INTERPRETER's native `replace` had the mirror defect, on the source side.**
`Environment`'s `replace` entry read `sequenceRef(source, start2 + k)` per
element, and `sequenceRef`'s list arm walks from the head -- so a LIST source was
quadratic HERE while the compile paths, fixed by todo-413 above, were linear:

| `(replace <4000-element array> <n-element list>)` | 500 | 1000 | 2000 | 4000 |
| --- | ---: | ---: | ---: | ---: |
| interpreter before | 0.141 | 0.626 | 2.63 | **10.6** |
| interpreter **after** | 0.0023 | 0.0042 | 0.0083 | **0.0169** |
| JVM `.class`, unchanged | 0.0015 | 0.0028 | 0.0051 | 0.0125 |

**627x at n = 4000**, and the interpreter goes from 850x SLOWER than the compiled
program to slightly faster than WASM. One `Environment.SequenceSourceCursor`
serves all three destination arms (`LispArray`, `LispIntVector`, list), since all
three read the source the same way; it is monotonic, re-seeds if a caller ever
reads backwards, and keeps `sequenceRef` for every non-list representation, so a
string, a general array and a packed vector read the same slot they always did
and their timings do not move at all (a null check, not a `consp` per element).

**It still SIGNALS.** The stop this arm's compile-path twin took would have
erased `sequence-ref: index N out of range`, and with it the one surviving
three-way disagreement recorded above. The cursor raises the same message from
the same element instead -- for a proper list run out, for a dotted tail, and for
a `:start2` past the end -- so **interpreter-signals / compile-paths-truncate is
unchanged**, and the re-evaluation trigger above still stands as written.

**2. The `%arrayp` arm is its own function, and a site that proves an array calls
it.** `%replace-runtime-array` / `%fill-runtime-array` hold that arm; the wide
helper's array arm is a CALL to it, so the two hold ONE copy of the copy loop
between them and a program that reaches both pays one extra call, not a second
loop. `LispMacroExpander.sequenceOpRuntimeWrappers` therefore answers them as a
PAIR -- which of the two a site can reach is decided per site at compile time,
long after that pre-expansion scan, so gating them apart there is not possible.

### Why routing per SITE and not narrowing the one helper

A whole-program scan cannot answer this. The `#'replace` wrapper body is a site of
its own, generated for every program, and it takes whatever it is handed -- so a
scan that had to cover it would keep every arm in every program, and the one
artifact this is for would gain nothing. Per-site routing lets chipz's five proven
sites call the arm while the wrapper (and any unproven user site) keeps the wide
dispatch; the shaker then drops whichever is left uncalled. This is the
`map-into`-one-helper-per-source-count shape, one level down.

### The soundness argument

The gate answers ONE question -- *can this value be a list or an immutable
string?* -- and only ever narrows when the answer is a definite no
(`WasmArrayCompiler.provesArrayValue`). Three sources, each already trusted at the
same site for array EMISSION (`.kb/declarations-type-checks.md`):

1. a pinned non-`STRING` representation kind (`arrayKindOfExpr`: a `declare (type
   ...)`, a `defstruct` slot `:type` read through its accessor, a `(the ...)`
   wrap, a `let` init this compile chose a representation for);
2. `Ctx.arrayLocals` -- a `let`-bound name whose init proves the WEAKER fact and
   which the body never reassigns;
3. a `make-array` call right at the site.

**Source 2 is the one that is new, and it exists because the weak fact is not the
kind.** `(make-array (* 2 (length output)) :element-type '(unsigned-byte 8))` --
chipz's output-buffer growth in `%decompress/null-vector` -- has no KIND at
compile time: an integer size packs, a dimension LIST would not, and the size is
computed. But `%arrayp` is true either way. `initExprKind` must keep refusing it
(it picks an accessor, and a wrong rank is a wrong accessor); `provesArrayValue`
must not, or the two sites that grow that buffer keep the wide helper alive and
the narrowing buys nothing. `Ctx.arrayLocals` carries exactly that weaker fact,
scoped, shadowed and restored beside `Ctx.declaredArrays` in `WasmLetCompiler`.

`makeArrayBuildsArrayValue` is conservative in three places, each a shape
`compileMake` can route to a string: a CHARACTER element type (with
`:initial-contents` it builds a fresh string), an element type spelled as a bare
symbol (a VARIABLE holding a designator computed at run time, which can name
`character`), and `:displaced-to` (whose target may itself be a string).

What a WRONG answer costs: only source 1 can be wrong, and only through a false
declaration, which CL calls undefined behavior. It lands on the array arm's
`%row-major-aset`, whose general lane casts -- so a list or a string TRAPS,
deterministically, exactly as `.kb/declarations-type-checks.md` records for the
single-arm accessors. Never silent wrong data. Sources 2 and 3 are this compile's
own construction and cannot be wrong. Under-predicting costs the module the
narrower callee and nothing else: `expandReplace`/`expandFill` fall back to the
wide helper, whose array arm is a call to the same function.

**wasm-GC only, and the asymmetry is the same one `%subseq-runtime` has.** Both
backends inject the pair (the JVM under its existing array gate), but only the
wasm backend routes: `DeclaredArrayTypes` has no JVM consumer yet, so
`JvmExprCompiler` passes `arrayHelperTarget` false at every site and its modules
keep the wide helper -- which is still correct, and still smaller than before by
the cursor-walk change. **Re-evaluation trigger: when the JVM adopts
`DeclaredArrayTypes` (`.kb/declarations-type-checks.md` says it could), give
`JvmExprCompiler`'s `REPLACE`/`FILL` cases the same third argument -- nothing else
has to move.**

### What the narrowing bought

Helper bodies, wasm-GC at `--optimize=size`:

| helper | before | after |
| --- | ---: | ---: |
| `%REPLACE-RUNTIME` (wide, all arms) | 4,445 | 1,900 |
| `%REPLACE-RUNTIME-ARRAY` (the arm alone) | -- | 2,328 |
| `%FILL-RUNTIME` (wide, all arms) | 1,680 | 1,347 |
| `%FILL-RUNTIME-ARRAY` (the arm alone) | -- | 727 |

The zlib artifact keeps only the two ARRAY helpers -- all five of its live
`replace` sites and both live `fill` sites prove their destination -- so
`%REPLACE-RUNTIME`, `%FILL-RUNTIME` and, with them, `%SEQ-TO-STRING` (which only
the string-rebuild arms reached) leave the module entirely: 290 -> 281 functions.

| `size-report zlib` | before | after | |
| --- | ---: | ---: | ---: |
| `--optimize` | 147,734 | 137,775 | **-6.7%** |
| `--optimize=size` | 117,118 | 109,290 | -6.7% |
| `--component --optimize=size` | 121,723 | 113,843 | -6.5% |
| (none) | 322,775 | 322,183 | -0.2% |

All four still gunzip the row's fixture byte for byte. `hello_world` and
`pi_approx` are byte-identical at every `--optimize` level (no site survives
their shake) and their un-shaken `(none)` rows fell 596 bytes each, which is the
cursor walk inside the wrapper catalog's own `#'replace`.

**What a program that reaches BOTH pays.** `hello-ningle` keeps all four helpers
(the `#'replace` wrapper is live, so the wide dispatch is): 6,381 bytes where the
two wide helpers were 6,160. The +221 is `fill`'s prologue, now spelled in both
of its helpers -- the narrow one must still or-default its own bounds, because a
site calls it directly. The Worker module moved +741 in total, the rest being
index re-encoding across 1,987 functions where two were added; the same residue
`.kb/seq-conversion-runtime.md` records, scaled to that module. Splitting
`replace` alone would have avoided it and cost the zlib artifact ~970 bytes of
`%FILL-RUNTIME`, which is the wrong trade for the artifact this is for.

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
- **A program that reaches BOTH the wide helper and its array arm pays one extra
  call per `replace`/`fill` into an array**, since the wide dispatch delegates.
  That is one frame per call, not per element, and it is what keeps the pair from
  holding two copies of the copy loop. Re-measure before trading it back.
- **`map-into` was deliberately left wide.** Its arms are `elt` reads and a
  runtime-dispatching `(setf (elt ...))` store rather than the `%arrayp`/`listp`/
  string split `replace` and `fill` share, and no measured artifact carries a live
  `%map-into-runtime-<n>` -- zlib has none. Reopen it against a module that does.
  Its LOWERING carries a cursor per source and one for the result
  (`mapIntoDispatch`), and since 2026-08-31 the `#'map-into` WRAPPER
  (`BuiltinFunctionWrappers.mapIntoWrapper`) carries the same result cursor --
  its store was `(setf (elt r i) v)`, an O(i) head-walk into a list destination
  and the last member of the `elt`-per-element family
  (`.kb/seq-coerce-runtime.md`). `(funcall #'map-into <n-element list> #'1+
  <n-element list>)`, ms per call, before -> after: n = 4000 reads 11.40 ->
  **0.031** on the JVM (365x), 21.85 -> **0.181** on wasm-GC (121x) and 18.13 ->
  **3.89** in the interpreter (4.7x, where what is left is the tree-walker's
  per-node cost), and every after-row DOUBLES per doubling of n where every
  before-row quadrupled. An ARRAY destination keeps the indexed store exactly as
  it was and pays one `consp` per element for a cursor it never uses: +25% in the
  interpreter (5.10 -> 6.38 ms at n = 4000), flat on the JVM and on wasm.
- **`%SEQ-INT-VECTOR` (2,136 B), `%SEQ-TO-LIST` (1,592) and `%SUBSEQ-RUNTIME`
  (1,114) are still whole-representation bodies** in the same artifact, and were
  todo-325's table alongside `replace`/`fill`. They are NOT this shape: the
  conversions are one arm each already (`.kb/seq-conversion-runtime.md`),
  `%subseq-runtime`'s narrow arm IS its expensive one, and `%seq-int-vector` is a
  `concatenate` result-family builder (`.kb/concatenate-result-families.md`). Each
  needs its own measurement before anyone splits it.

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
  `map-into` routes to the helper of its own source count; a PROVEN array
  destination routes to the array arm on the same call-site shape.
- `LispMacroExpanderTest.theSharedSequenceOpDispatchesAnswerTheSameThingAsTheInlinedOnes`
  -- one body, two homes; the bounds are or-defaulted parameters; no helper calls its
  own operator; the wide helper's array arm is a CALL and the array helper carries no
  dispatch, no `rplaca` and no `concatenate`; the list SOURCE is a cursor walk, not
  an `elt` index.
- `LispMacroExpanderTest.theSequenceOpRuntimeGateInjectsOnlyTheHelpersThatWouldHaveACaller`
  -- the injection gate, both directions, `replace`/`fill` answering a PAIR, one
  `map-into` helper per source count, and that the helpers reach `subseq` (so the
  subseq gate must scan them).
- `WasmLispCompilerTest.aDestructiveSequenceOperatorSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime`
  -- the marginal byte budgets above. Nothing else notices: every arrangement compiles
  and runs correctly.
- `WasmLispCompilerTest.aProvenArrayDestinationLeavesTheSharedRuntimesNonArrayArmsWithoutACaller`
  -- the narrowing gate at the module level: the same program with and without the one
  fact it rests on, differing by more than 2 KB.
- The behavior itself is pinned where it already was, plus the new list arm and the
  narrowing: `replace-into-a-list`, `sequence-op-runtime-arm-routing` (both sides of
  the gate in one program) and the sequence cases in `ci-spec.yaml` (all four
  backends), `LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest` (`replaceIntoAList`, `sequenceOpRuntimeArmRouting`).
- The two source cursors above:
  `LispEvaluatorTest.replaceReadsAListSourceThroughACursorRatherThanIndexingItFromTheHead`
  -- every destination arm, a self-aliased `replace`, a 2,000-element source, and
  the three `sequence-ref: index N out of range` signals a silent cursor stop
  would have erased;
  `JvmLispCompilerTest.compileAndRunAListIntoAListReplaceReadsItsSourceWithACursor`
  and the list-source rows appended to `WasmLispCompilerIntegrationTest.replaceIntoAList`;
  the LIST-source rows added to `ci-spec.yaml`'s `replace-into-a-list`, which runs
  all four backends over the same program and is what would catch the interpreter
  and the compile paths drifting apart on this arm.
