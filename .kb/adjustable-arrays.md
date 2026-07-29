# Fill-pointer / adjustable / displaced arrays (`copy-array` surface)

Split out of todo-071 (itself split from the
cl-utilities residue todo-065). This is the extended-array surface that
cl-utilities `copy-array` touches:

- `make-array` options `:fill-pointer`, `:adjustable`, `:displaced-to` /
  `:displaced-index-offset`.
- `fill-pointer` (+ `setf`), `array-has-fill-pointer-p`, `adjustable-array-p`,
  `array-element-type`.
- `vector-push` / `vector-pop` / `vector-push-extend`.
- `adjust-array`, `array-displacement` (two values), `copy-array` itself.

## Status (2026-07-06)

**ALL sub-steps DONE on all four backends** (interpreter, JVM, WASM Preview 1,
WASM component): the fill-pointer surface, `adjust-array`, and `:displaced-to`
displacement + `array-displacement`. The verbatim cl-utilities `copy-array`
definition runs everywhere (pinned by
`LispEvaluatorTest.clUtilitiesCopyArrayRunsOnInterpreter`,
`JvmLispCompilerTest.compileClUtilitiesCopyArray`,
`WasmLispCompilerIntegrationTest.compileClUtilitiesCopyArray`, and the
`fill-pointer-arrays-cross-backend` ci-spec case). `--no-gc` rejects the whole
surface with its usual clear compile error (arrays are ineligible on the scalar
backend: `--no-gc: unsupported operation 'vector-push' ...`; the new names go
through the same default path), satisfying the todo-71 "gate explicitly"
acceptance without a dedicated gate.

**Fill-pointered STRINGS (mutable character vectors, ALL backends
2026-07-18)**: `make-array :element-type 'character` — with or without
`:fill-pointer`/`:adjustable` — builds a mutable string everywhere. On the
interpreter it is a mutable `LispString` (char[] buffer + fill pointer +
adjustable flag). On the COMPILED backends it is the GENERAL array
representation holding character elements, marked "character vector" —
JVM: a **length-4** slot-0 header `Object[]{dims, fp, adj, null}` built by
`_charVecMake` (displacement detection tightened to `header.length > 4`,
i.e. displaced ⇔ length 5, at `emitResolveDisplacement` /
`_arrayDispTarget` / `_arrayDispOffset`); WASM: the **meta offset i31 == 1**
(an ordinary array's is 0; a displaced array's data slot is a cell, so the
marker is unambiguous, and `%array-disp-offset` now reports the offset only
when the data slot IS a cell). The whole fill-pointer surface
(push/pop/push-extend, setf fill-pointer, `%array-become`, `_rmGet/_rmSet`)
runs on it unchanged, and the marker survives `adjust-array` because become
mutates the existing header in place.

String behavior comes from ON-DEMAND NORMALIZATION into the immutable
runtime string: JVM `_strv(Object)` (JvmArrayRuntimeBuilder, emitted under
the same array gate) renders the active prefix quote-framed; WASM
`_charvec_to_str` (a fixed always-emitted function right after
`FUNC_WRITE_STR_GC` — `FUNC_VEC_BASE`/`FUNC_USER_BASE` shifted by one,
reusing the unary `TYPE_CALLABLE_BASE + 0` signature, capture-aware scratch
so mid-capture normalization cannot clobber `*-to-string` output). Insert
points: the string-op compile sites (char/schar, subseq, string=/-equal,
case/trim/concat, write-string, string designator, read-from-string,
make-string-input-stream, intern, make-symbol — the last four were WASM-only
until todo-208 made a plain `make-string` result reach them on the JVM too,
where `_readFromString` and `intern`/`make-symbol`'s quote strip both
`checkcast String` and so threw `ClassCastException` on a char vector), plus
the shared runtime bodies — JVM `emitArrayBranch` of
`_lispToString`/`_lispToDisplayString` (which also covers equal-hash-table
keys, keyed by rendered string) and `_eqv`'s equals fallback; WASM the
entries of `_equal`, `_hash`, `_print_val`, `_princ_val` plus `stringp`.
On the JVM everything is gated on `programUsesAnyArrayOp || usesFloatArray`
(`Ctx.usesArrays`), so array-free programs stay byte-identical; on WASM the
helper is always emitted (all module bytes shifted once, flag-dimension
byte-identity contracts unaffected).

Mutation flows through SHARED expansions (`LispMacroExpander`):
`expandReplace` and `expandScharSetFunctional` branch at runtime on
`(%arrayp seq)` — a vector (char vector included) is written in place via
`%row-major-aset` + `elt` and returned; an immutable string keeps the
functional rebuild (fresh string; the setf form still requires a variable
place). `lowerCharacterInitialContentsMakeArray` lowers rank-1 character
`:initial-contents` to a fresh string copy (`subseq` of a stringp contents,
else `coerce 'string`) — both compilers try it BEFORE the general
`:initial-contents` lowering. Default `:initial-element` for a char vector
is `#\Space`. Lite residue: `adjust-array` on a NON-adjustable char vector
returns a general (unmarked) vector; `#'make-array`'s variadic wrapper
(`apply` path) has no `:element-type` cue, so it builds an unmarked vector;
char reads past the fill pointer see the rendered (fp-bounded) content.
E2E: ci-spec `mutable-strings-cross-backend`; unit: the
`compileCharVector*` / `compileScharSetfMutatesCharVectorInPlace` /
`compileJzonAccumulatorPattern` sets in both compiler tests.

**A PLAIN `(make-string n)` is a character vector too (all backends,
todo-208)**. `LispMacroExpander.expandMakeString` — shared by the
interpreter and both compilers — lowers to
`(make-array n :element-type 'character :initial-element c)`, so every
make-string result IS the mutable representation above. `make-sequence`
reaches it through the same door (`expandMakeSequence` maps the string
family onto `make-string`).

Why, and the trigger to revisit: the old lowering was a `dotimes` that
`concatenate`d ONE character per iteration into a fresh IMMUTABLE string.
That was O(n^2) to allocate, and — because the result was immutable — the
`(%arrayp seq)` test in `expandReplace` / `expandScharSetFunctional` was
false, so `(replace buf x)` and `(setf (subseq buf ...) x)` took their
FUNCTIONAL branch, built the right string, and had it discarded in statement
position. `(setf (char buf i) c)` only appeared to work because the
functional branch `setq`s the rebuild back into the variable, which an alias
never sees. The visible casualty was s-sql's verbatim `strcat` ("allocate a
`make-string` buffer, `replace` into it, return it"): every S-SQL form
carrying a non-literal value — `:insert-rows-into`, a variable, `(* 3 100)`
— reached PostgreSQL as a BLANK string of exactly the right length. Nothing
signalled; the server answered `WARNING: Empty query sent.` and the row was
not inserted. If a future change makes make-string build anything other than
the character vector, that whole class of "allocate a buffer, write into it"
CL code silently regresses again. Pinned by the ci-spec case
`make-string-mutability-cross-backend` (the four write shapes plus what a
buffer is read through) and by `PostmodernE2eTest`'s `runtimeSql*` leg on
the interpreter, the JVM and the component.

Consequences of the lowering, all decided rather than inherited:

- **The JVM array gate now includes `make-string`.** `Ctx.usesArrays` is
  `programUsesAnyArrayOp(program) || usesFloatArray || usesIntArray`
  (plus the forced-group term below), and both backends'
  `programUsesAnyArrayOp` list `MAKE-STRING` and
  `MAKE-SEQUENCE` alongside `MAKE-ARRAY` (they lower to it during
  `compileExpr`, after the scan runs). So a string-only program that
  allocates a buffer does pull in the array runtime and grows. That is
  deliberate: the alternative — lowering conditionally on a scan for "does
  this program mutate the string" — cannot see mutation through a call
  (`strcat` mutates its own local), which is precisely the bug. Array-free
  programs that never mention make-string are still byte-identical
  (`.kb/emitted-output-determinism.md`). `make-string` also joined
  `BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS` so its wrapper is
  injected exactly when the runtime it calls is emitted.
- **`eq`/`eql` on a make-string result answers like any other computed
  string** — content on the interpreter and the JVM, identity on WASM — and
  that is the pre-existing general string divergence, not a character-vector
  one: `(eq "abc" (concatenate 'string "ab" "c"))` is already `T` on the
  interpreter/JVM and `NIL` on both WASM backends (WASM strings carry an id
  field; `_eq` compares it). `(eq s s)` is `T` everywhere. CL leaves `eq` on
  strings unspecified, so no backend is wrong and the ci-spec case
  deliberately does not cover it; the trigger to revisit is WASM gaining
  content-`eq` for strings generally, which would close this row with it.
- **`--no-gc` is unaffected**: `make-string` was already an unsupported
  operation on the scalar backend and still is (its `make-array` takes float
  element types only).
- **The functional branches stay.** A LITERAL string is still immutable, so
  `expandReplace` / `expandScharSetFunctional` keep their `%arrayp`-false
  paths for `(replace "abc" ...)`; what changed is which values reach them.

## The JVM array gate is a CONSEQUENCE, not a prediction (todo-209)

**The invariant: on the JVM the array runtime is emitted whenever the emitted
bytecode calls it, no matter which lowering introduced the call.** Before
2026-07-29 it was emitted when `programUsesAnyArrayOp` -- a scan of the SOURCE
program for a hand-maintained list of operator names -- said so. Several
lowerings introduce an array primitive only during `compileExpr`, i.e. AFTER
that scan, and when one of those was a program's only array use the class
carried an `invokestatic` to a method that was never generated. JVM method
resolution is lazy, so it survived verification and threw `NoSuchMethodError`
the first time the branch ran. The list was right up to whichever lowering was
added last without a matching entry, and it could not be right by construction.

How it works now, in `JvmLispCompiler`:

1. The scan stays -- it is still what decides `Ctx.usesArrays`, and that flag
   changes CODE, not just emission (the `_strv` normalization at the string-op
   sites, the character-vector arm of `stringp`, the array print branch), so it
   has to be known before any body is compiled.
2. After the class bytes are assembled, `JvmClassShaker.unresolvedSelfMethods`
   (in `am.ik.jvm`, language-independent) walks every emitted body and reports
   each own-class method that some `invoke*` names but the class does not
   declare.
3. A reported name is matched against the per-group rosters
   (`JvmArrayRuntimeBuilder.METHOD_NAMES`, `JvmHashRuntimeBuilder.METHOD_NAMES`,
   the eval runtime's four methods + `_lookup`). A match means that gate
   under-predicted, so `compile` RE-RUNS itself with the group forced on: the
   result is the build a source that DID mention an array operator would have
   produced, not merely one that no longer crashes. Each retry strictly grows
   the forced set, so the loop terminates.
4. Anything left unmatched is an internal inconsistency no re-run can fix, and
   the compile fails loudly naming the call and its callers instead of handing
   the user a class that dies later.

**The check covers EVERY caller, wrappers included (todo-210).** It briefly did
not: a call referenced only by injected built-in wrappers was skipped, because a
wrapper is injected in every class whether or not the program mentions the
operator and most of them dragged in the array runtime (`#'+`, `#'reverse`,
`#'sort`, `#'subseq`, `#'string=`, ... ~33 of them called `_aref1`) -- without
the skip every class carried the whole array runtime, ~35 KB. The skip was sound
(each reference sat behind a runtime type test for a value the absent runtime
cannot construct) but it cost the check its strength: a genuinely reachable
dangling call inside a wrapper was invisible, and `#'funcall`'s `(apply f r)`
was exactly that shape -- caught only because it needed the eval runtime
UNCONDITIONALLY, which is reference-gated so wrapper and runtime are decided by
the same fact.

The skip is gone because its cause is: **each lowering that dispatches over the
sequence representations now takes an `arraysExist` flag and drops the array arm
when it is false** -- the same move as `expandClassOf`'s `hash-table-p` clause
below and `JvmStringpCompiler`'s character-vector arm. The interpreter and both
WASM backends pass `true` (their array primitives are unconditional); the JVM
passes `Ctx.usesArrays`. The gated lowerings, all in `LispMacroExpander` unless
noted:

| lowering | what the flag drops |
| --- | --- |
| `expandCoerce` | the vector scan behind `'list` / `'string` (`coerceVectorToList`) |
| `expandElt` | the `aref` arm of the string/list/array dispatch |
| `expandMap` | the same `aref` arm in the per-element read |
| `seqResultDispatchForm` (private; `wrapSortForStringSeq`, `expandReverse`, `expandRemoveDuplicates`, `expandRemove`, `expandRemoveIf`, `expandRemoveIfNot`, `expandSubstitute` forward the flag) | the `__seq_vec` binding and the `(coerce __seq_res 'vector)` rebuild |
| `expandSubseqCompat` | the whole rewrite -- it exists only to reach the array arm, so it returns `null` and the compiler emits its plain `%subseq-core` |
| `expandReplace` | the destructive `%row-major-aset` loop, leaving the functional string rebuild |

Reading and BUILDING are gated differently, and the distinction is the reason
this is sound. Dropping a READ of an array element is a local fact: no array
exists, so no read of one can run. Dropping a form that CONSTRUCTS an array is
not -- only the caller's guard can say it is dead. So `expandCoerce` gates the
reading direction only, and the one place that drops a builder is
`seqResultDispatchForm`, which rebuilds a vector result exactly when the INPUT
was a vector. Every other coerce-to-vector keeps its builder and is therefore
still caught by step 3: a source-level `coerce` raises the gate by name, and
`(map 'vector ...)` expands to an ungated `expandCoerce` whose `_arrayMake` call
comes from a real method and forces the re-run.

`(print (+ 1 2))` went from 186,066 to 127,630 bytes (-31%) on this, and a
reference from anywhere -- user code, a defun, a lowering, a wrapper -- is now
program-dependent evidence that does force the gate, even where the branch would
have been dead; conservative in the safe direction. Pinned by
`JvmLispCompilerTest.anArrayFreeProgramReferencesNoArrayRuntimeHelper` (nothing
dangles in an array-free class) plus
`compileSequenceOperatorsWithoutTheArrayRuntime` (the survivors still answer for
lists and strings).

The packed `_fv*` / `_iv*` tiers need no equivalent: `Ctx.usesArrays` is true
whenever `usesFloatArray` / `usesIntArray` is, and an `aref` only compiles to
`_ivAref1` / `_fvAref1` when that tier is emitted, so a wrapper body can never
name one the class lacks.

`(class-of x)` was the one lowering whose gated call was NOT behind such a test
from the gate's point of view: its `cond` chain included a `hash-table-p` clause
unconditionally, so every class that compiled a `class-of` referenced `_hashP`.
`expandClassOf` now takes a `hashTablesExist` flag (the interpreter and both
WASM backends pass `true`; the JVM passes `Ctx.usesHashTables`) and drops the
clause when no hash table can exist -- the same reasoning that keeps the
character-vector arm out of a compiled `stringp`.

Why this shape and not the alternatives: scanning the expanded program is not
available (the expansions are lazy, per-site, inside `compileExpr`), and
emitting the group unconditionally would end the byte-identity of array-free
programs (`.kb/emitted-output-determinism.md`). Step 2 costs one linear pass
over the class on every build; step 3 costs a second compile only for a program
that actually trips a gate.

`(setf (elt s i) v)` is the worked example, and it needed a semantic fix too:
`expandSetf`'s `ELT` branch yielded `(%aset seq i v)` for everything non-`consp`,
so a STRING target reached `%aset` -- `NoSuchMethodError` on the JVM (no gate
name in the source at all), `cast failure` trap on both WASM backends, and only
the interpreter got it right. The branch now carries the same three-way dispatch
`(setf (aref s i) v)` has -- `consp` -> `rplaca`, `stringp` -> the `schar-set`
rebuild, else `%aset` -- with the same lite restriction: only a VARIABLE place
takes the string arm (the rebuild of an immutable string `setq`s the result
back), so a variable place dispatches on the variable itself and any other place
expression keeps the two-way list/array dispatch over a temp. Pinned by the
`setf-elt-cross-backend` ci-spec case, `LispEvaluatorTest`'s
`evalSetfEltDispatchesOverListStringAndVector`, `JvmLispCompilerTest`'s
`compileSetfEltOnAStringMutatesIt` (the gate) plus
`compileSetfEltOnAVectorAndAList`, and
`WasmLispCompilerIntegrationTest.compileSetfEltDispatchesOverListStringAndVector`.

Two long-latent bugs of the same family surfaced the moment step 2 ran over the
corpus, both now fixed at the source rather than by a retry:

- **`#'funcall` died with `NoSuchMethodError` on the JVM.** Its injected wrapper
  body is `(apply f r)`, which compiles to the eval runtime's `_apply`, but the
  wrapper was emitted in EVERY class while `_apply` stayed gated on the SOURCE
  mentioning `eval`/`apply`. `(reduce #'funcall fns)` -- cl-utilities' `compose`
  -- was the shape that hit it. The rule the parse-integer / read-from-string
  wrappers already followed now covers it: a wrapper whose body needs a gated
  runtime is injected exactly when the program references the operator as a
  value, and that same reference forces the runtime on. Pinned by
  `JvmLispCompilerTest.compileFuncallAsAFirstClassValue`.
- **`_tlsConnect` instantiated a constructor that was not always emitted.** The
  whole socket group is emitted for any TCP/TLS operator, but `<init>` was gated
  on `tls-connect` alone; a `tcp-connect`- or `tls-listen`-only program declared
  no constructor. `needsInstanceCtor` follows the socket gate now. (The
  X509TrustManager interface and its three methods stay on `usesTlsConnect`:
  nothing calls them from bytecode -- JSSE does -- and only a `tls-connect` call
  site can reach `_tlsConnect`.)

Costs that came with it, both deliberate:

- Any program using `(setf (elt ...))` now pulls in the array runtime, because
  the string arm's `schar-set` rebuild reaches `%arrayp`/`%row-major-aset`. Same
  trade as `make-string` above.
- The `_top$N` chunk budget dropped from 40000 to 24000. The string arm costs
  ~6 KB per `(setf (elt ...))` site, which took the ci-spec corpus's largest
  single top-level form to ~39 KB -- and a single form cannot be split, so the
  budget has to leave room for it under the JVM's 65535-byte method cap. To
  re-measure, compile with the budget set to 1 (every top-level form gets its own
  chunk) and read `-Drontolisp.jvm.debug-method-sizes=true`, which now ranks the
  top-level chunks alongside the defuns and lambdas.

The rosters in step 3 are the one thing still written by hand, so
`JvmRuntimeGroupNamesTest` pins each against what its builder actually emits, in
both directions. WASM has no equivalent gap: it emits the array builtins inline,
so there is no group to under-predict -- its `programUsesAnyArrayOp` only
excludes wrapper groups.

## adjust-array

`(adjust-array array new-dims &key initial-element fill-pointer)` on every
backend: elements are preserved at the subscripts valid in BOTH shapes
(per-subscript, not flat -- resizing a matrix keeps `(i, j)` at `(i, j)`); an
`:adjustable` array is adjusted IN PLACE and returned itself (`eq`), otherwise a
fresh array is returned; without an explicit `:fill-pointer` the old fill
pointer carries over (make-array range-checks it against the new size, so
shrinking below it errors like CL); rank mismatch and displaced inputs signal
clear errors; `:displaced-to` in adjust-array is rejected.

Implementation split, chosen to keep the n-dimensional copy logic OUT of
per-backend codegen:

- **Interpreter**: a real `Environment` built-in (`adjustArray` in
  `Environment.java`, runtime keyword parsing) over `LispArray.become(other)`
  (replaces dims/data/fillPointer in place, keeps the adjustable flag).
- **Compile path**: `LispMacroExpander.expandAdjustArray` -- a Lisp-level
  expansion over existing primitives (`make-array` with the carried-over
  `:fill-pointer`/`:adjustable`, `array-dimensions`, `array-total-size`,
  two-arg `floor` for the subscript decomposition, `row-major-aref` /
  `%row-major-aset` for the copy) plus ONE new internal primitive
  `%array-become` per backend (JVM `_arrayBecome`: header dims/fp copy +
  ArrayList resize/copy; WASM: three inline `struct.set`s swapping the header's
  dims car, meta fp and data slot). Both compilers dispatch
  `ADJUST_ARRAY -> compileExpr(expandAdjustArray(cons))`.

## Displacement (`:displaced-to`)

Lite semantics on every backend: a displaced array is a BARE VIEW -- it cannot
be combined with `:fill-pointer`/`:adjustable`/`:initial-element` (compile-time
`UnsupportedOperationException` on JVM/WASM since make-array keywords are
literal; runtime error on the interpreter), cannot itself be adjusted, and
`:displaced-index-offset` requires `:displaced-to`. The view is bounds-checked
at creation (`total + offset <= target total`, target dims product). Chains
(view of view) resolve transitively, and access follows the CURRENT storage of
each hop, so a view keeps aliasing an adjustable target after
`vector-push-extend`/`adjust-array` grow it in place -- pinned on all backends
(`displacedArraySeesTheTargetGrowInPlace` / `compileDisplacedArrays` / the
`adjust-displaced-arrays-cross-backend` ci-spec case). Rank may differ from the
target's (vector view over a matrix row).

`array-displacement` returns target + offset as TWO values via the syntactic
multiple-value tier: `isMvProducerForm`/`lowerMvProducer` recognize
`(array-displacement x)` and read the two internal accessors
`%array-disp-target` / `%array-disp-offset` over one temp; in an ordinary
context `expandArrayDisplacement` yields the primary
`(%array-disp-target x)`. No `%mv-spill` involvement.

Semantics shared by all backends: only rank-1 arrays may have a fill pointer
(`:fill-pointer t` = the vector size, an integer = that value, range-checked);
the fill pointer is the effective length (`length`, `#(...)` printing, the
sequence view) while `aref`/`row-major-aref` still reach the full backing
store; `vector-push-extend` grows any fill-pointer vector regardless of
`:adjustable` (the flag is reported verbatim by `adjustable-array-p`);
`array-element-type` always returns `t` (`:element-type` is parsed and
ignored). `%set-fill-pointer` is the internal `setf` target wired in
`LispMacroExpander.expandSetf`. On the compile path `array-element-type`
expands to `(progn <array> t)` (`LispMacroExpander.expandArrayElementType`).

First-class values: `#'vector-push` etc. work via
`BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS` (fill-pointer,
array-has-fill-pointer-p, adjustable-array-p, array-element-type, vector-push,
vector-pop, vector-push-extend -- the last in its 2-arg form -- plus
make-array), gated like `HASH_FUNCTIONS`: both compilers inject the group only
when `programUsesAnyArrayOp` (which also gates the JVM array helpers and now
lists the fill-pointer names) is true, so the wrappers and their helpers stay
gated together. `#'make-array` is a variadic wrapper (`variadicMakeArray`)
whose runtime keywords are re-extracted with `getf`: a `:displaced-to`
argument selects the bare-view shape, everything else the general
`:adjustable`/`:fill-pointer`/`:initial-element` shape, and `:element-type` is
accepted and ignored like the call position -- this is what makes
cl-utilities' verbatim `copy-array` (`apply #'make-array (list* dims
options...)`) work on the compile path.

Interpreter/JVM errors carry `fn: message` text (e.g. "vector-pop: empty
vector"); WASM traps (`unreachable`) on the same conditions. Compiled `list`
argument evaluation is right-to-left (.todo/014), so tests/ci-spec sequence
side-effecting pushes/pops through separate bindings.

## Representation

### Interpreter (`LispArray`)

`LispArray` has mutable state (fields are no longer `final`):

- `int fillPointer` -- the fill pointer, or `-1` for none. When present it is
  the effective length; only rank-1 arrays may have one.
- `boolean adjustable` -- the `:adjustable` flag (verbatim; reported by
  `adjustable-array-p`).

`data` / `dimensions` are non-final so `vector-push-extend` can reallocate. Key
methods: `effectiveLength()` (fill pointer if present, else `data.length`),
`vectorPush` (returns the index or `-1` when full), `vectorPop`,
`vectorPushExtend` (grows `data` + `dimensions[0]` when full), `setFillPointer`.
`render()` (the `#(...)` printer) iterates `effectiveLength()`, so a
fill-pointer vector prints only up to the fill pointer. `length` (in
`Environment`) uses `effectiveLength()`.

`aref` / `row-major-aref` still reach the FULL backing store (CL semantics: the
fill pointer bounds the sequence view, not element access).

That holds for STRINGS too, and the interpreter was the odd one out until
2026-07-26: `Environment.charRef` (which backs `char` / `schar` / `aref` on a
string) and `%schar-set` bounded the index by `LispString.length()`, i.e. the
fill pointer, so `(aref s 5)` on a `:fill-pointer 3` string of capacity 8
signalled on the interpreter and returned the inactive slot on all three compile
backends. Both now bound by `capacity()`. CL is explicit that `char`, `schar` and
`aref` ignore fill pointers ("it is permissible to use `aref` to access any array
element, whether active or not"), so the compile backends were right and the
divergence was silent -- no test covered an inactive string slot. Pinned now by
the `string-fill-pointer-inactive-slots` ci-spec case (read AND write through
`aref`, all four backends byte-identical).

**`char` / `schar` past the fill pointer are still three-way divergent, and the
ci-spec case deliberately does not cover them**: the interpreter now returns the
slot, the JVM throws a raw `String.offsetByCodePoints` exception, and both WASM
backends return `#\Nul` (they materialize the string at its fill-pointer length
and have no bounds check at all -- writing past the end silently APPENDS there).
That is the general missing-bounds-check gap, not a fill-pointer question;
`.todo/186` holds it with the measurements.

Displacement: `displacedTo` (a `LispArray` or null) + `displacedOffset` fields;
a displaced array's `data` is a shared empty array and all element access goes
through `readFlat`/`writeFlat`, which walk the chain adding each hop's offset
(so growth of the target's storage is followed -- the chain holds the OBJECT,
not the storage). `totalSize()` (dims product) replaced `data.length` in every
bounds check; `effectiveLength()` = fp or `totalSize()`. `become(other)`
replaces dims/data/fillPointer in place (adjust-array's adjustable half).

`make-array` parses `:fill-pointer` (`t` = size, integer = that value, `nil` =
none; rank-1 only), `:adjustable`, and ignores unknown keywords such as
`:element-type`. New builtins live in `Environment.registerArrays`.

### JVM (implemented)

An array is still a `java.util.ArrayList`, but slot 0 now holds a header
`Object[]{dims, fillPointer, adjustable}` -- `dims` the `Object[]` of Long
dimension sizes (length = rank), `fillPointer` a `Long` or null, `adjustable`
the RAW `:adjustable` argument (null = nil; kept verbatim so
`_adjustableArrayP` is a null test). Data stays at slots `1..` (the `1 + flat`
offset in `_aref*`/`_aset*` is untouched); every dims reader gained one extra
`aaload 0` (`emitFlat2`, `emitFlatN`, `_arrayDims`, `buildToString`,
`JvmLengthRuntimeBuilder`). BOTH header producers build the wrapper:
`_arrayMake` (signature grew to `(dims, init, fillPointer, adjustable)`;
`JvmArrayCompiler.compileMake` compiles the keyword value expressions or pushes
null) and `JvmQuoteCompiler.compileQuotedArray` (literals: slots 1/2 null).
`buildToString` + `_length` clamp the element count to the fill pointer.

A DISPLACED array carries a 5-element header
`Object[]{dims, null, null, target, offsetLong}` and holds NO data slots
(`_arrayMakeDisplaced(dims, target, offset)`, bounds-checked against the
target's dims product). Every data access now funnels through the two
displacement-aware primitives `_rmGet(list, idx1based)` / `_rmSet(...)`: a loop
`while header.length > 3 && header[3] != null { idx += header[4]; list =
header[3] }` (the offset composes with the 1-based list index directly), then
`get/set(1+flat)`. `_aref1/2/N`, `_aset1/2/N` and `buildToString`'s element
read end in `invokestatic _rmGet/_rmSet` (the builders take the generated
class's `selfClass` for the methodref); `buildToString`'s no-fp element count
and `_length`'s no-fp fallback switched from `size() - 1` to the dims product /
`dims[0]` (same value for ordinary arrays, correct for displaced).
`_arrayDispTarget`/`_arrayDispOffset` read header slots 3/4;
`_arrayBecome(a, b)` copies dims+fp into a's header and resizes/copies the
ArrayList elements in place.

New static helpers in `JvmArrayRuntimeBuilder` (same array gate):
`_fillPointer`, `_setFillPointer`, `_arrayHasFillPointer`, `_adjustableArrayP`,
`_vectorPush`, `_vectorPop`, `_vectorPushExtend` (push-extend appends nulls to
the ArrayList and updates the inner dims[0]), `_rmGet`, `_rmSet`,
`_arrayMakeDisplaced`, `_arrayBecome`, `_arrayDispTarget`, `_arrayDispOffset`.
Call sites are wired in `JvmArrayCompiler` + `JvmExprCompiler.compileCons`;
`--optimize` (`JvmClassShaker`) keeps used helpers via the ordinary
invokestatic call graph (verified with `--optimize` on fill-pointer and
displaced/adjust-array programs).

### WASM (implemented)

The `TYPE_CELL` box now holds a header `TYPE_CONS` of
`(dims . (meta . data))`: `dims`/`data` are the same `TYPE_HASH_BUCKETS`
arrays; `meta` is `(fillPointer-i31-or-null . (adjustableRaw . offset-i31))`
(the offset is 0 for an ordinary array). The header's CAR is still the dims
buckets array, so the array-vs-hash-table discriminator used by `%arrayp` /
`WasmLengthCompiler` / the printer is unchanged, and
`compileDims`/`emitFlatIndex` (car readers) needed no change. Producers:
`compileMake` (resolves `:fill-pointer` at runtime -- null / bounds-checked
i31 / `t` -> dims[0] -- only when the keyword appears at the call site) and
`WasmQuoteCompiler.compileQuotedArray` (meta `(null . (null . 0))`).

A DISPLACED array stores the TARGET CELL in the data slot (instead of a
buckets array) and its offset in the meta chain
(`compileMakeDisplaced`; bounds-checked against the target's dims product,
traps when too small). Every data access site
(`compileAref`/`compileAset`/`compileRowMajorAref`/`compileRowMajorAset`) runs
the inline `emitResolveDataAndIndex` walk: while the data slot `ref.test`s as
`TYPE_CELL`, add the meta offset to the flat index and hop to the target's
header -- an ordinary array falls straight through. Because the walk re-reads
each hop's CURRENT header, a view keeps aliasing a target grown in place by
push-extend/adjust-array. The printer (`WasmRuntimeBuilder.emitPrintArray`)
gained the same walk (one extra i32 local, `baseSlot`, in
`buildPrintValBody`/`buildPrincValBody`) and its no-fp element count switched
from the data-buckets length to the dims product; `WasmLengthCompiler` already
used `dims[0]` and needed nothing. `compileAdjustableArrayP` reads
`meta.cdr.car`; `%array-become` is three inline `struct.set`s;
`%array-disp-target`/`%array-disp-offset` read the data slot (cell or nil) and
`meta.cdr.cdr`. The vector-push family never sees displacement (a displaced
array has no fill pointer, so its fp guard traps first).

The builtins are emitted INLINE in `WasmArrayCompiler`
(compileFillPointer/SetFillPointer/HasFillPointer/AdjustableArrayP/VectorPush/
VectorPop/VectorPushExtend/ArrayBecome/DispTarget/DispOffset; push-extend
copies into a fresh buckets array with a loop and `struct.set`s the inner cons
+ dims[0]) -- no new heap type, no new `FUNC_*` index, so the component blobs
are untouched and Preview 1 / `--component` stay identical.

### `--no-gc` scalar WASM

Unsupported, like every array operation on the scalar backend: the eligibility
scan (`NoGcWasmCompiler.collectCallsCons`) names the operation in a clear
compile error ("--no-gc: unsupported operation 'vector-push' in function 'f'
..."). Documented under the `--no-gc` section of `doc/*/compiling/wasm.md`
("vectors" in the ineligible list).

## Wiring points (adjust-array / displacement)

`LispNames`: `ADJUST_ARRAY`, `ARRAY_DISPLACEMENT`, `ARRAY_BECOME`
(`%array-become`), `ARRAY_DISP_TARGET`/`ARRAY_DISP_OFFSET`
(`%array-disp-target`/`-offset`), `DISPLACED_TO_KEYWORD`,
`DISPLACED_INDEX_OFFSET_KEYWORD`. `PackageRegistry`: the two public names in
`CL_FUNCTIONS` (list-functions count 236 -> 238), the three `%`-names in
`CL_INTERNALS`. Both compilers' `programUsesAnyArrayOp` gates list all five
names. `BuiltinFunctionWrappers`: `binary(ADJUST_ARRAY)` (2-arg form) +
`unary(ARRAY_DISPLACEMENT)` (primary value only) in the
`ARRAY_FILL_POINTER_FUNCTIONS` group. `--no-gc` rejects the new names through
its default unknown-operation error.

## Tests / docs

- Interpreter: `LispEvaluatorTest` -- `fillPointerLengthAndAccessors`,
  `fillPointerVectorPrintsUpToFillPointer`, `vectorPushStoresAndReturnsIndexOrNil`,
  `vectorPushThenReadBack`, `vectorPop`, `vectorPushExtendGrowsBeyondCapacity`,
  `setfFillPointer`, `simpleVectorHasNoFillPointer`,
  `fillPointerOnNonFillPointerVectorSignals`, `clUtilitiesCopyArrayRunsOnInterpreter`,
  `adjustArray*`, `displacedArray*`, `arrayDisplacementReturnsTargetAndOffset`,
  `makeArrayDisplacedErrors`.
- JVM: `JvmLispCompilerTest.compileFillPointer*` / `compileVectorP*` /
  `compileSetfFillPointer` / `compileSimpleVectorHasNoFillPointer` /
  `compileFillPointerFirstClassWrappers` / `compileClUtilitiesCopyArray` /
  `compileAdjustArray` / `compileDisplacedArrays` /
  `compileArrayDisplacementValues` /
  `compileMakeArrayDisplacedKeywordComboIsACompileError`.
- WASM: the same set in `WasmLispCompilerIntegrationTest`.
- E2E: ci-spec `fill-pointer-arrays-cross-backend` +
  `adjust-displaced-arrays-cross-backend` (all four backends).
- Docs: `reference/functions/{fill-pointer,array-has-fill-pointer-p,
  adjustable-array-p,array-element-type,vector-push,vector-pop,
  vector-push-extend,adjust-array,array-displacement}.md` (en+ja) + the
  make-array page + the functions table.
- The `list-functions` count (238) is pinned in `LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`, and the
  `rontolisp-package-introspection` ci-spec case.
