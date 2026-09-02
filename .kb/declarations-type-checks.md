# Declarations: no-op semantics, declaration-driven array emission, eval-when, check-type/assert

Origin: the first unit of the ASDF Phase 3 split in
`.todo/054-asdf-support.md` (shipped 2026-07-05). Goal: real CL library sources
parse and load -- nearly every library body contains `declare`/`declaim`, and
macro-exporting libraries wrap `defmacro` in `eval-when`.

**Since 2026-08-11 declarations are no longer PURE no-ops on the wasm-GC
backend**: a `(declare (type ...))` (and a `defstruct` slot `:type`) can drive
single-arm array-access EMISSION -- see "Declaration-driven array emission"
below. **Since 2026-08-29 the JVM backend reads the SCALAR float family too**
(`.todo/569`): a `(declare (type double-float ...))` routes arithmetic onto the
unboxed IEEE path and keeps declared let locals in raw `double` slots -- the
mechanics live in `.kb/jvm-double-arithmetic.md`, the policy below. The VALUE
semantics are unchanged everywhere for a CORRECT declaration: no true
declaration ever changes a result, and the interpreter/`--no-gc` backends still
ignore every declaration entirely.

## The false-declaration policy (all backends, decided once)

**A declaration may change EMISSION, never the RESULT of a correctly-declared
program -- and where a backend TRUSTS a declaration, a FALSE one becomes a
DETERMINISTIC error at the site the trusted representation meets the
contradicting value, never a silently coerced or wrong value.** Decided by
todo-320 for the wasm-GC array kinds and adopted unchanged by todo-569 for the
JVM scalar floats; per-backend shape:

- **wasm-GC** (Preview 1 and `--component`): a `ref.cast` TRAP at the access.
  Uncatchable; the cheapest diagnosable shape, since a checked signal would
  cost the dispatch bytes the feature removes.
- **JVM**: a `checkcast java/lang/Double` failure at the declared read or
  store -- a `ClassCastException`, which the condition bridge surfaces as a
  CATCHABLE Lisp error (`handler-case` sees it). Catchability is a side effect
  of the JVM's existing error channel, not a promise; the guarantee is only
  determinism-at-the-site. One deliberate softening: an integer LITERAL
  assigned to a declared float variable widens at compile time (`(setq sum 0)`
  on a declared accumulator answers `0.0d0`) -- the same widening the double
  path's literal operands always had, chosen over a trap because the shape is
  common in sloppy-but-working sources and the widened value is what the very
  next float operation would have computed anyway.
- **Interpreter and `--no-gc`**: declarations ignored, the generic answer. The
  interpreter is the oracle for CORRECTLY-declared programs only.

So a falsely-declared program DIVERGES across backends (defined answer where
declarations are ignored, deterministic trap/error where they drive emission)
-- CL calls it undefined behavior, and the divergence is documented in
`doc/*/reference/macros/declare.md` rather than papered over. The alternative
-- coercing through `_dbl` so every backend answers SOMETHING -- was rejected
because it answers silently WRONG data (`(setq x n)` of an integer reading
back as a float) and still diverges from the interpreter, which keeps the
integer. Cross-backend agreement for true declarations is pinned by the
`declared-float-scalars-answer-what-undeclared-code-answers` ci-spec case and
the wasm `declared-array-types-single-arm-access` case.

## What ships

All seven operators are `LispMacroExpander` expansions (no per-backend
codegen), classified in `PackageRegistry.CL_MACROS` (precedent: `error` is a
"macro" here too) with `expandBuiltinMacro` cases so `macroexpand-1` works:

- `declare`, `declaim`, `proclaim` -> `nil`. Arguments are never evaluated or
  validated (`proclaim` deviates from CL, where it is a function with an
  evaluated argument). A `declare` in a body is harmless because bodies
  discard non-final values; a `declare` as the LAST body form returns nil,
  which is invalid CL code anyway.
- `the` -> its value form (identity).
- `eval-when` -> `progn` of the body (every situation = "evaluate now").
- `check-type` -> `(let ((__check-type place)) (if <type-test> nil (error
  "The value of <place> is ~s, which is not of type <spec>." __check-type)))`.
  The place and spec are printed into the message at expansion time; only the
  value is a runtime `~s` argument. An optional third string replaces the
  "of type ..." part.
- `assert` -> `(if test nil (error ...))`; with the full form the datum+args
  become the error call and the places list is dropped (it establishes no
  `continue`/`store-value` restart, so there is nothing to re-store into --
  the restart system itself exists, see `.kb/error-handling.md`).

## Declaration-driven array emission (wasm-GC, todo-320)

**Invariant: a declaration may change EMISSION, never a RESULT. A rank-1
`aref`/`(setf aref)`/`length` site whose array's representation is pinned down
emits that ONE representation's accessor behind a trapping `ref.cast` instead
of the inline 4-way dispatch chain -- and a FALSE declaration becomes a
deterministic trap at the access, never silent wrong data.** wasm-GC backends
only (Preview 1 AND `--component`); the interpreter, the JVM and `--no-gc`
still treat every declaration as a no-op, so a program with a false
declaration DIVERGES across backends (works there, traps here). That is the
todo-320 design decision (now the shared policy above): CL calls a false
declaration undefined behavior, and a trap is the cheapest diagnosable shape --
a checked signal would cost the very dispatch bytes the feature removes. Re-evaluation trigger: if a real
library ever needs the failure catchable, route the cast failure through a
shared trap-with-message helper instead of widening per-site code.

### Why this exists (the todo-320 measurements)

Measured on the chipz `zlib` artifact at `--optimize=size` (2026-08-11):
**21.1% of the module's instruction bytes were `aref`/`%aset` inline dispatch
chains** -- 196 bytes per rank-1 `aref` site (62 sites), 232-1540 per `%aset`
site (60 sites, whose index/value expressions were re-emitted once per ARM).
The ceiling measurement: chipz's fully-declared `crc32` compiled to 822 bytes
generically; the hand-written ideal (raw i64 locals, direct `array.get_u`) is
120 bytes. **Arithmetic, by contrast, is NOT where the size is**: with fusion
off a generic `+`/`logand`/`ash` is one 2-byte helper call, so
declaration-driven arithmetic emission was measured to be worth ~nothing at
size level and deliberately NOT built (it remains a possible SPEED lever at
the default level, where a declared tree could drop fusion's double emission
-- re-measure before building that). Result on the `zlib` rows: 161,976 ->
149,054 (`--optimize=size`, -8.0%), 193,382 -> 182,934 (`--optimize`),
166,656 -> 153,734 (`--component --optimize=size`), check stream gunzipping
byte-identically on all four backends.

### The representation kinds and their sources

`am.ik.rontolisp.compiler.DeclaredArrayTypes` (backend-free, so the JVM could
adopt it later) maps a type specifier to a `Kind`: `U8`/`U16`/`U32` (packed
integer vector -- requires an explicitly rank-1 dims spec, `(simple-array
(unsigned-byte 8) (*))`; an unknown-rank spec could be a rank-n GENERAL array
of the same element type), `FLOAT` (packed float array, either width),
`GENERAL` (boxed general array: `simple-vector`, `fixnum`/`t`/other unpacked
element types at any rank) and `STRING`. A character element type maps to
NOTHING (a character vector is a marked general array OR a string after
normalization -- two representations, and above rank 1 it is neither but the
plain general array, `.kb/array-literals.md`); a bare `vector`/`array` proves
nothing (a string is a vector too). Specifier symbols resolve through the
`ClosRegistry` deftype table, including the NEW defaulted registration: an
all-`&optional` deftype (`simple-octet-vector`) now registers its bare-name
DEFAULT expansion, folded by a closed pure evaluator over the
`quote`/`list`/`cons`/`append`/`or`/`let`/`let*` shapes a backquoted body
reads as (`LispMacroExpander.defaultedDeftypeExpansion`; the CLHS
deftype-specific unsupplied-optional default is the symbol `*`). This aligns
the direct-compiler path with the CLI path, whose `UserMacroExpander`
pre-pass already folded such deftypes by evaluation (`LispEvaluator
.foldDeftype`).

A site's kind comes from four sources (`WasmArrayCompiler.arrayKindOfExpr`):

1. **A declared lexical variable** -- `Ctx.declaredArrays`, scoped exactly
   like `Ctx.locals`: registered from body-head `(declare (type ...))` forms
   by the defun/lambda body setup (`WasmLispCompiler`, following the sole
   trailing `%fn-block`/`(block name ...)` wrapper `LambdaLists` and the flet
   lowering produce) and by `WasmLetCompiler` (bound AND free declarations --
   a free declaration covers the body, which is also where a lambda list's
   generated `let*` prologue leaves its parameter declarations); shadowed
   names removed, restored on scope exit. Specials are never registered;
   registration is skipped at top level (the eval mirror owns those slots)
   and in async bodies, like the raw locals.
2. **A binding INITIALIZER this compile chose a representation for**
   (`WasmArrayCompiler.initExprKind`): a literal packed/general `make-array`
   (rank-1 literal size, no fill-pointer/adjustable/displacement keywords), a
   slot-typed accessor call, or a kinded outer variable -- but only while the
   body never reassigns the name (a declaration needs no such check: it
   covers assignments too). This is what types `do`-bound table variables
   (`(do ((counts (hdt-counts table))) ...)`) and `loop with` bindings, which
   chipz leaves undeclared.
3. **A `defstruct` slot `:type` read through its accessor call** -- captured
   by `expandDefstruct` into `ClosRegistry.registerStructSlotType` (a side
   table; `LispLayout` stays type-free), `:include` children inheriting the
   parent's slot types, accessors over inherited slots included. Trusted only
   while the accessor's generated body is the ONE definition the call can
   reach: off under `--dynamic`, and off for any name the program defines
   more than once (`Ctx.duplicatedDefunNames`).
4. **A `(the spec expr)` wrap** at the array position.

### What the kinded sites emit

- `aref` rank-1 (`emitKindedAref1`): `ref.cast` to the one representation +
  its direct read -- u8/u16 box `ref.i31` inline (they always fit), u32 goes
  through `_int_new`, FLOAT/GENERAL/STRING reuse the generic chain's own arm
  bodies. ~19-30 bytes against the 196-byte chain.
- `%aset` rank-1 (`emitKindedAset1`): same single arm; the packed-int arm
  keeps the raw-value fast path (`tryCompileRaw`) because a single arm needs
  the value only once. A STRING kind never stores (strings are immutable
  structs; the generic path's general-arm cast traps there too, so kinded
  emission declines and the generic path answers it).
- `length` of a packed kind: `ref.cast` + `array.len` + `ref.i31` (rank-1 by
  construction, no fill pointer possible). The other kinds keep the generic
  chain (a GENERAL vector's length must honour a fill pointer).
- `(setf (aref var i) v)` on a VARIABLE place whose kind is non-string skips
  the expansion's `stringp`/`schar-set` branch entirely
  (`WasmArrayCompiler.nonStringArefStore`, intercepted at both
  `WasmExprCompiler` setf sites) -- that branch exists because a string is a
  rank-1 character array, and a pinned non-string kind proves it dead.
- A `replace` / `fill` site whose DESTINATION is pinned non-string calls the
  array-arm-only shared runtime instead of the wide one
  (`WasmArrayCompiler.provesArrayValue`, `.kb/sequence-op-runtimes.md`). Same
  trust, same failure mode: a false declaration traps in the arm's
  `%row-major-aset` rather than silently taking the wrong branch. That predicate
  also answers on a WEAKER fact this file's `Kind` cannot carry -- "an array of
  some representation", from a `make-array` whose rank is a runtime fact --
  tracked in `Ctx.arrayLocals` beside `Ctx.declaredArrays`; `initExprKind` must
  keep refusing those, because it picks an accessor and a wrong rank is a wrong
  accessor.
- Independent of declarations: at `--optimize=size` the GENERIC rank-1
  `%aset` now hoists array/index/value into temps once and lets the three
  arms read the slots (`emitHoistedAset1`) -- the legacy shape re-emitted the
  index and value expressions per arm, tripling their bytes. The speed levels
  keep the legacy shape on purpose: its packed-int arm compiles the value RAW
  through the fusion machinery, which a pre-boxed temp would defeat.

Emission is at EVERY optimize level (the `WasmDotimesCompiler` precedent:
strictly smaller AND faster, so it is not a speed-for-size trade). Evaluation
order (array, index, value) is the generic order; a false declaration may
observe index/value side effects before the cast traps where the generic
general arm would have trapped first -- acceptable inside UB, noted here so
nobody chases it as a bug.

### Pinning tests

`WasmLispCompilerIntegrationTest.declaredArrayTypesEmitSingleArmAccessorsWithoutChangingResults`
(every source and kind, mask/readback edges past the i31 range, inherited
slot types, the defaulted deftype, shapes that must STAY generic; both
optimize levels against the interpreter/JVM text) and the
`declared-array-types-single-arm-access` ci-spec case (all four backends).
`ChipzE2eTest` is the end-to-end pin on the real library.

## makeTypeTest (shared type-specifier tests)

`LispMacroExpander.makeTypeTest(value, spec)` builds a truthy test form; the
old `makeTypecaseTest` now delegates to it, so `typecase`/`etypecase` clause
heads accept the same specs as `check-type`:

- `pathname` -> the `%PATHNAME` instance-tag test (todo-304;
  `.kb/pathnames.md`). Its history is a three-step correction: an EMPTY type
  first (rontolisp had no pathname object), then `stringp` (todo-249, because
  the producers answered namestrings and mito's `(check-type directory
  pathname)` rejected them), now a DISTINCT value -- the producers answer
  pathname instances, so the check-type passes again AND a `typecase` listing
  `pathname` beside `string` discriminates a file from text by the type
  itself, as CL specifies. `pathnamep` answers the same test, as CL requires
  the two to agree; with the instance gate off the test compiles to constant
  nil, which is then also correct (no pathname can exist).
- EMPTY types (constant-nil tests, todo-243 widened the family):
  `bit-vector`/`simple-bit-vector` (no bit-vector value exists —
  the bit type is dead, `.todo/180`; a typecase's bit-vector clause falls
  through to its vector clause), `generic-function`/
  `standard-generic-function` (a defgeneric's dispatcher is a plain function
  value with no marker — routes trivia level2's `(etypecase fn
  (generic-function ...))` onto its portable test-call fallback) and
  `structure-class`/`built-in-class` (a defstruct's class metaobject IS a
  standard-class, `.kb/instance-syntax.md` — routes trivia's
  `(typecase (find-class type) ...)` onto its `t` = slot-value branch).
  All six agree with runtime `typep`'s nil and are in
  `PackageRegistry.CL_TYPES` so they resolve bare in user packages; `CLASS`/
  `STRUCTURE`/`TYPE` joined CL_TYPES for a different reason — they are the
  CL symbols trivia NAMES its class/structure/type patterns with, and the
  defpattern site and a user's pattern site must resolve to the same bare
  spelling or the pattern-namespace lookup misses.
- Atomic map (`atomicTypePredicate`): integer/fixnum/bignum -> `integerp`,
  float* -> `floatp`, number/real -> `numberp`, rational/ratio ->
  `rationalp`, string, symbol, keyword, cons, list, null, atom, character,
  hash-table, function -> `functionp`; `boolean` -> `(or (null v) (eq v t))`;
  `unsigned-byte` -> `(and (integerp v) (>= v 0))`; `array` ->
  `(or (stringp v) (%arrayp v))` (the rank is NOT checked -- that is what
  separates it from `vector`, whose atomic arm goes through
  `makeArrayTypeTest`); `sequence` adds `listp`; `t`/`otherwise` -> t;
  literal `nil` spec -> nil (the empty type). The three `simple-` spellings
  add `%simple-array-p` (below). `functionp` is a real public
  builtin (Environment + Jvm/WasmFunctionpCompiler: JVM = Object[] with an
  Integer funcId in slot 0, WASM = `ref.test TYPE_CLOSURE`); `%arrayp` is a
  CL_INTERNALS-only predicate (JVM = `instanceof java.util.ArrayList`; WASM
  distinguishes an array cell from a hash table by testing the header car
  for the dims array vs the i31 count).
- Compound: `(or ...)`/`(and ...)`/`(not ...)` recurse; `(member items...)`
  -> `(member v '(items))` (eql, truthy tail); `(eql obj)` -> `(eql v 'obj)`;
  `(satisfies fn)` -> `(fn v)`; `(integer|float|rational|real|number [low
  [high]])` -> base predicate + bound checks, `*` = unbounded, `(n)` =
  exclusive bound; the ARRAY family is its own section below.
- Type-specifier symbols match by their package-STRIPPED name
  (`plainTypeName`): standard type names are not all registered CL symbols,
  so inside a user package the resolver qualifies e.g. `unsigned-byte` to
  `pkg::unsigned-byte` (found by the split-sequence e2e, todo-061).
  Complementing that, `PackageRegistry.CL_TYPES` registers the common
  type-only names (float family, unsigned-byte, sequence, satisfies,
  otherwise, ...) so they resolve BARE in the first place -- required where
  the name reaches RUNTIME data, e.g. parse-number's `'double-float` compared
  by the runtime `coerce` dispatch (symbols compare by name, so a qualified
  spelling would never match).
- **The four float type names are ONE type, and `subtypep` says so on purpose**
  (`LispMacroExpander.canonicalSubtypeName` collapses `single-float`/
  `double-float`/`short-float`/`long-float` to `FLOAT`). rontolisp has exactly
  one float format -- `1.0s0`, `1.0f0`, `1.0d0` and `1.0l0` all read to the same
  value, `type-of` answers `FLOAT` for each and a float is `typep` of all four
  names -- and CLHS explicitly permits an implementation to have as few as one
  distinct float format, in which case the four names denote the same type and
  `(subtypep 'single-float 'double-float)` is genuinely `T`. So this is NOT the
  "dishonest lattice" `.todo/200` suspected: postmodern's
  `json-encoder.lisp` `eval-when` probes exactly these pairs and, answered this
  way, takes its `:cl-json-only-one-float-type` branch, which is the correct one
  here. **Re-evaluation trigger**: this stays right only while there is one float
  representation. If a distinct single-float ever lands, `canonicalSubtypeName`
  must stop collapsing the names and the lattice must gain the real
  `single-float <= double-float <= long-float` edges in the SAME pass, or every
  library that probes the float lattice silently takes the wrong branch. Pinned
  by the `postmodern-language-incidentals` ci-spec case, which asserts the pair
  `(T T)`. Separately, `subtypep` returns ONE value here (CL returns a second
  "certain?" value); every known consumer uses only the primary.
- **A collapse in `canonicalSubtypeName` is a claim that two names denote the
  SAME SET, so it answers `T` in both directions** -- that is the whole test for
  membership there, and the `simple-` names failed it (below).
- `deftype` is a parsed no-op returning nil (the name is NOT registered;
  using it in a later type test errors) -- enough for the library shape
  where the type only appears in no-op declaim/declare declarations.
- The value form may be evaluated several times, so callers bind a temp
  first (`__check-type` / typecase's `__typecase`).

## The array type lattice: type-of BUILDS the compound specifier

**`type-of` answers an array's COMPOUND specifier, and `makeTypeTest` takes the
same one back -- the two are one contract and must move together.** Before
todo-604 every array answered `T` (legal by the letter of CLHS, useless in
practice: nothing could tell a vector from a matrix) and
`(typep a '(simple-array single-float (2 2)))` answered nil on an array that IS
one. The shapes are SBCL's, checked against SBCL 2.2.9 on the pinning program:

| value | `type-of` |
| --- | --- |
| `(make-array 4)` | `(SIMPLE-VECTOR 4)` |
| `(make-array '(2 3))` | `(SIMPLE-ARRAY T (2 3))` |
| `(make-array nil)` | `(SIMPLE-ARRAY T NIL)` -- the rank-0 array, `.todo/603` |
| `(make-array 4 :element-type 'single-float)` | `(SIMPLE-ARRAY SINGLE-FLOAT (4))` |
| `(make-array 4 :element-type '(unsigned-byte 8))` | `(SIMPLE-ARRAY (UNSIGNED-BYTE 8) (4))` |
| `(make-array 4 :fill-pointer 0)` / `:adjustable t` / `:displaced-to v` | `(VECTOR T 4)` |
| `(make-array '(2 3) :adjustable t)` | `(ARRAY T (2 3))` -- non-simple above rank 1 |
| `"abc"` | `STRING` (SBCL: `(SIMPLE-ARRAY CHARACTER (3))`) |

Where this lives and why:

- **`type-of` is the PRELUDE defun** (`LispPreludeLibrary`), so one source
  serves all four backends. Its array arm fires only where
  `%class-designator` answers the uninformative `T` AND `%arrayp` is true. That
  guard is what keeps CHARACTER arrays out: a rank-1 character array is a
  string VALUE on the interpreter and a marked general array on the compile
  paths, and both DESIGNATE `STRING` -- so all four answer `STRING` rather than
  diverging. The SIMPLICITY arm is tested FIRST -- since todo-611 because a
  remembered element type and a fill pointer can coexist
  (`(make-array 4 :element-type 'double-float :fill-pointer 0)` is
  `(VECTOR DOUBLE-FLOAT 4)`, not a `simple-array`), and since todo-610 because
  the DISPLACEMENT belongs in the same answer: a non-simple array is
  `(VECTOR et size)` at rank 1 and `(ARRAY et dims)` above it, whatever it
  holds, which is SBCL's answer for a fill-pointered, an `:adjustable` and a
  displaced array alike. It asks `%simple-array-p`, ONE total predicate, rather
  than `array-has-fill-pointer-p`/`adjustable-array-p`: those two became safe
  for a packed array in todo-611 (they answer nil, what CL says of a simple
  array and what the interpreter always answered while the JVM threw and wasm
  trapped), but neither can see a displacement at all -- which is why a
  displaced array used to answer `(SIMPLE-VECTOR 2)` here.
- **`array-element-type` answers the BOOLEAN `t`** for a general array asked for
  nothing narrower, on the interpreter too since todo-604 -- it used to answer a
  SYMBOL spelled `"T"` there while all three compile backends answered the
  boolean, so `(eq (array-element-type a) t)` disagreed across backends.
  `type-of` reads exactly that answer to choose between `(simple-vector n)` and
  `(simple-array et dims)`. Since todo-611 a general array that WAS asked for a
  narrower type -- `character` or `(unsigned-byte n)` above rank 1, any of them
  with `:fill-pointer`/`:adjustable` -- answers that type instead, so the
  `simple-array` arm carries a real element type where it used to carry `t`
  (`.kb/array-literals.md`, "The degraded array REMEMBERS its element type").
- **`makeArrayTypeTest`** (`LispMacroExpander`) builds the test for
  `(array ET DIMS)` / `(simple-array ET DIMS)` / `(vector ET SIZE)` /
  `(simple-vector SIZE)`. It is the union of a STRING arm and an ARRAY arm,
  because a string is a rank-1 character array in CL but not one of the
  representations `%arrayp` knows: the string arm survives only while the
  specifier can still describe one (element type character or unstated, rank 1
  or unstated) and sizes itself with `%string-dimension` -- the array
  DIMENSION, which is what a sized specifier means and what `length` is NOT
  (below, todo-613). The array arm reads
  `array-element-type` and the dimensions behind the `%arrayp` guard. A
  `simple-` spelling ANDs ONE `%simple-array-p` call in front of the whole
  union (todo-610, below).
- **The element type is compared UPGRADED** (`upgradedArrayElementType`),
  mirroring exactly the representation `make-array` selects: the two float
  widths and the three packed `(unsigned-byte 8|16|32)` widths keep their name,
  the character family answers `character`, and everything else -- `fixnum`,
  `integer`, `bit`, a class -- lands in the general boxed array whose element
  type is `t`. So `(typep a '(simple-array fixnum (4)))` is a `t`-array test:
  conformant, since there is no fixnum-specialized array to upgrade to, and it
  is the one shape array-operations' suite still fails on (below). A deftype
  ALIAS resolves first (`resolveElementTypeAlias`), like make-array's own.
- **The dimensions are compared** as a whole when every one is literal (one
  `array-dimensions` read against the quoted list, `nil` for the rank-0 array),
  otherwise as a rank check plus one `array-dimension` read per pinned
  dimension. Both VECTOR spellings pin the rank to 1 -- that is the dimension
  information the specifier carries, and it keeps a rank-2 array out of an
  `(simple-vector 41)` test rather than reaching `length`, which refuses a
  non-sequence.
- **The ATOMIC `vector` spellings check the rank too** (todo-605, 2026-08-31):
  `vectorp` and a bare `vector`/`simple-vector` clause answer `T` only for a
  rank-1 array (a string included) -- a vector IS a rank-1 array and nothing
  else, and SBCL 2.2.9 answers `NIL` for `(vectorp #2A((1 2) (3 4)))` and
  `(vectorp (make-array nil))`. They are not a second copy of the test: the
  atomic arm and `vectorp`'s expansion both call `makeArrayTypeTest` with an
  unspecified element type and a one-dimension `(*)` shape, so predicate and
  specifier cannot drift apart. Only the atomic `array`/`simple-array`
  spellings stay rank-blind, which is what separates them from `vector`. The
  earlier rank-blind answer was a size bargain; it bought bytes with a wrong
  answer, so it went. Measured cost on the `zlib` size-report artifact at
  `--optimize=size` (2026-08-31): 102,704 -> 103,158 wasm bytes, +0.44%. The JVM
  backend pays nothing on an array-free program: `expandVectorp` takes the
  `arraysExist` flag and drops the whole array arm when the gate is off, which it
  must -- `vectorp` has an injected first-class wrapper every program carries
  until the shaker runs, and an ungated rank read there put `_arrayDims` in
  `(print 1)` and forced the entire array runtime back on
  (`.kb/adjustable-arrays.md`).
- **A COMPUTED specifier takes the same set as a literal one** (todo-606).
  `type-of` handing a program a compound specifier made
  `(typep a (type-of a))` a normal idiom that answered nil, because the runtime
  dispatch was a `cond` keyed on the specifier SYMBOL and a cons matched no arm.
  Both dispatch shapes now route a CONS specifier to the section below.

## The COMPOUND half of the runtime typep dispatch

**A computed type specifier takes exactly the set a literal one does, because
the compound families are interpreted out of the specifier VALUE by one Lisp
source both dispatch shapes carry** --
`LispMacroExpander.RUNTIME_COMPOUND_TYPEP_SOURCE`, instantiated over a value
form, a specifier form and the operator its sub-specifier recursion calls
(`substituteSymbols` on three placeholder symbols). There is no second
implementation to drift: the interpreter's `expandRuntimeTypep` inlines it with
the recursion spelled `typep` (which the interpreter re-expands per call), and
the compile paths put it in a `%typep-compound-runtime` defun whose recursion is
`%typep-runtime`. `%typep-runtime`'s FIRST cond arm is the `consp` route into
it -- before the instance branch, since an instance is an ordinary member of an
`(or foo bar)` and the tag table only knows type NAMES.

The defun is separate from `%typep-runtime` for the reason that defun exists at
all: the JVM's 16-bit branch offsets, which one wider `cond` would push back
toward.

Every arm is the runtime twin of a `makeCompoundTypeTest` arm, reading the
arguments out of the value instead of the AST: the array family (element type
compared UPGRADED, exactly as `upgradedArrayElementType` folds it statically;
the string arm and the array arm are the same union `makeArrayTypeTest`
builds), `or`/`and`/`not`, `member`/`eql`/`satisfies`, `(cons car cdr)`, the
sized `string` spellings, `(unsigned-byte n)`/`(signed-byte n)`, and a default
arm that recurses on the head symbol ALONE and then applies the range bounds --
which is what makes a compound spelling of a NON-numeric atomic type
(`(hash-table ...)`) answer through its base predicate, since the bounds are
applied only to a numeric value.

The head is matched by its `symbol-name`, the runtime spelling of
`plainTypeName`: a specifier read inside a user package carries the qualified
symbol and the member name is what identifies the family. The three narrower
float names joined `RUNTIME_TYPEP_BUILTINS` in the same pass -- they name the
same type here (one float format), and a ranged `(double-float 0d0 10d0)`
reaches the atomic dispatch through that default arm.

**The size it costs, measured 2026-08-31.** The defun is emitted once per
program and only under the computed-`typep` gate, but it reaches the generic
array accessors and `funcall`, which a program may not otherwise pull in. On the
`hello-clack` Worker (`--no-wasi --optimize=size`, the size-sensitive row):
355,961 -> 374,619 raw (+18,658, +5.2%), 106,415 -> 112,717 gzipped (+6,302,
+5.9%, so 3.4% -> 3.6% of Cloudflare's 3 MB limit). Roughly half of that is the
ARRAY arm alone (364,853 raw / 109,118 gzipped with that one arm stubbed out).
The floor, on a program that uses nothing but a computed `typep`: 32,920 ->
55,096 raw. A program with no computed `typep` is byte-identical (`zlib`
measured unchanged), and the interpreter's inline expansion grows by the same
source at every computed-`typep` site -- bounded, and small beside the one arm
per registered class it already inlines.

That price bought a normal CL idiom that answered nil, and it is recorded rather
than avoided. **Re-evaluation trigger**: if a Worker row ever needs the bytes
back, the array arm is the half to move -- into a defun of its own, injected on
a narrower gate than "the program has a computed typep".

Pinned by `LispEvaluatorTest#evalComputedCompoundTypeSpecifiers`,
`JvmLispCompilerTest#compileComputedCompoundTypeSpecifiers`,
`WasmLispCompilerIntegrationTest#computedCompoundTypeSpecifiers` and the
`computed-compound-type-specifier` ci-spec case -- one program whose every answer
is SBCL 2.2.9's on that very program.

Pinned by `LispEvaluatorTest#evalTypeOfAndTypepAnswerTheCompoundArraySpecifier`,
`JvmLispCompilerTest#compileTypeOfAndTypepAnswerTheCompoundArraySpecifier`,
`WasmLispCompilerIntegrationTest#typeOfAndTypepAnswerTheCompoundArraySpecifier`
and the `array-type-of-and-compound-array-specifier` ci-spec case -- one program,
one expected text, all four backends.

The atomic `vector` half is pinned the same way by
`LispEvaluatorTest#evalVectorpChecksTheRank`,
`JvmLispCompilerTest#compileVectorpChecksTheRank`,
`WasmLispCompilerIntegrationTest#compileVectorpChecksTheRank` and the
`vectorp-and-the-vector-specifier-check-the-rank` ci-spec case, whose expected
text is SBCL 2.2.9's verbatim.

The rank-n (n>1) CHARACTER array this did NOT close -- a general array on the
interpreter, a character-marked array on wasm, a `make-array` REFUSAL on the
JVM -- was closed by todo-607 the same day: above rank 1 a character element
type selects no representation of its own on ANY backend, so the value is the
plain general array -- which since todo-611 still REMEMBERS the element type, so
`type-of` answers `(SIMPLE-ARRAY CHARACTER dims)` everywhere. The model and its
cost are `.kb/array-literals.md`, "A SPECIALIZED element type above rank 1 is the
general array" and "The degraded array REMEMBERS its element type".

## A `deftype` ALIAS resolves at RUN TIME too (todo-618, 2026-09-02)

**A type designator held in a VALUE resolves a user `deftype` exactly as its
literal spelling does, on all four backends.** `(deftype octet () '(unsigned-byte
8))` followed by `(let ((ty 'octet)) (typep 3 ty))` answers `T`, not the `NIL` it
answered until this landed -- the literal `(typep 3 'octet)` was resolved at
expansion time by `makeTypeTest`, and a designator in a variable reached no
recognizer. `coerce` with a computed result type is the same hole twice over
(its fall-through arm IS a computed `typep`, and its family dispatch reads the
designator's head), so both halves close together: `(coerce 3 ty)` answers `3`
and `(coerce '(#\a) ty)` with `ty` an alias of `string` builds `"a"`.

The resolution is ONE normalization at the top of each dispatch shape, and the
shapes differ only in where the table lives:

- the interpreter's `expandRuntimeTypep` re-expands per call against the live
  registry, so it spells `(setq tn (cond ((equal tn 'ALIAS) '<expansion>) ... (t
  tn)))` inline -- `LispMacroExpander.deftypeAliasResolution`, which nothing
  emits and nothing pays for;
- the compile paths put the alias set in a quoted DATA table
  (`%deftype-alias-table%`, through `chunkedTableForms`) scanned by one shared
  `%deftype-alias` defun, and `%typep-runtime` calls it once
  (`runtimeTypepDefun`'s `(setq tn (%deftype-alias tn))`), after the metaobject
  normalization that turns a class object into the name this reads.

`%typep-compound-runtime` recurses back into `%typep-runtime`, so an alias
INSIDE a compound specifier -- `(typep 3 (list 'or 'octet 'null))` -- resolves
through the same normalization. Alias CHAINS are followed when the table is
built, so one hop suffices at run time, and a self-referential `(deftype a ()
'a)` terminates on the same 16-hop bound `resolveElementTypeAlias` uses. A name
the dispatch already decides -- a built-in spelling, a registered class, a
struct -- is left OUT of the table: the literal path resolves a `deftype` only
after those three, so normalizing first would silently reorder the reading.

### The narrowing is what makes it affordable (the measurements)

`.todo/618` was filed on a measurement that said this could not be bought: one
`cond` arm per alias cost the array-operations program (`ql:quickload`, one
`aops:zeros*`, raw wasm `--optimize=size`) **+10,075 bytes, +10.7%**, because
alexandria registers **43** `deftype` aliases and a program that merely loads it
carried all 43. The item's plan was to try the data-table shape instead.

**Measured, the data table alone bought nothing: 93,672 -> 103,713, +10,041,
+10.7% -- the same bill.** The cost is not the dispatch arms; it is the alias
NAMES. 43 aliases at three spellings each (`ALEXANDRIA::POSITIVE-FIXNUM`,
`ALEXANDRIA:POSITIVE-FIXNUM`, `POSITIVE-FIXNUM`) put 129 long symbols into a
module that had none of them, at ~56 bytes apiece: with the expansions replaced
by `nil` the table still cost +7,231, and with one spelling per alias instead of
three it cost +5,904. A quoted constant compiles to construction code
proportional to its size, and a symbol's size is its name.

**What bought it is a SECOND narrowing, on a different axis: the table carries
only the aliases the program SPELLS** (`narrowedDeftypeAliases`, closed
afterwards under the alias references its own entries make, since
`proper-sequence` expands to `(or proper-list ...)` and the compound recursion
has to resolve the inner name). A designator symbol a runtime `typep` can be
handed has to come from somewhere, and short of `intern`/`read` on a computed
string that somewhere is a spelling in the program -- the same reasoning the
funcall-dispatch gate's name probes rest on (`LispNames.UNSPELLED_QUOTE` exists
to stay OUT of that set). Narrowed, array-operations carries **2** entries and
pays **+1,765 bytes, +1.9%**; the `size-report` programs `zlib` (chipz/salza2,
which register `octet` aliases of their own), `pi_approx` and `hello_world` are
**byte-identical**, as is any program with no computed `typep` at all.

The one shape the narrowing does not cover is a designator built at run time out
of characters -- `(typep x (intern (read-line)))` -- which answers `nil` on the
compile paths, exactly as it did before this item; the interpreter, which has no
program to probe and pays nothing for the full table, resolves it. That is the
lite deviation this buys the 8.8 points with, and it is the only one.

Pinned by `LispEvaluatorTest#evalRuntimeTypepResolvesADeftypeAlias`,
`JvmLispCompilerTest#compileRuntimeTypepResolvesADeftypeAlias`,
`WasmLispCompilerIntegrationTest#compileRuntimeTypepResolvesADeftypeAlias` and
the `runtime-typep-deftype-alias` ci-spec case -- one program, one expected text,
all four backends, every answer SBCL 2.2.9's on that very program except the
trailing unregistered name, where the lite model answers `nil` and SBCL
signals.

The `make-array :element-type` half of the same gap is `.kb/array-literals.md`,
"A `deftype` ALIAS held in a VARIABLE resolves before the dispatch" -- a
DIFFERENT narrowing (only the aliases naming one of the six specialized codes),
because there an unresolved designator and 42 of the 43 land on the same `t` arm
anyway.

## The COMPOUND half of `subtypep` (todo-608)

**A compound type specifier works on either side of `subtypep`, quoted or
computed, and answers the same on all four backends.** Until todo-608 it was
name-only past `(or ...)` and the two halves DISAGREED: `(let ((s '(or fixnum
ratio))) (subtypep s 'number))` answered `T` on the interpreter (whose builtin
calls the Java `LispMacroExpander.subtypep`, which took an `or` on either side)
and `NIL` on the JVM (whose `%subtypep-runtime` scans an ancestor table by NAME
and matched no cons), while the quoted `(subtypep '(integer 0 10) 'integer)`
answered `NIL` everywhere.

The rules, decided once and written twice -- `LispMacroExpander.subtypep` over
the AST (the interpreter's builtin AND the literal fold) and
`RUNTIME_COMPOUND_SUBTYPEP_SOURCE` over a runtime specifier VALUE (the arm the
emitted `%subtypep-runtime` routes a cons to, before the by-name table scan).
They are twins, not one source: the static side must stay Java because the
emitted ancestor table is GENERATED from it. Change them together.

- **A type is a subtype of itself**, compound included (`equal` on the two
  specifiers) -- `(subtypep (type-of a) (type-of b))` over two same-shaped
  arrays is a normal probe that no other rule below reaches.
- **`(or ...)`**: any branch as the super, EVERY branch as the sub. **`(and
  ...)`**: every conjunct as the super, ANY conjunct as the sub.
- **Any other head as the SUB reduces to that head and re-tests**: a RESTRICTING
  specifier denotes a subset of its head, so `(integer 0 10)` <= `integer`,
  `(simple-array t (2 2))` <= `array`, `(string 2)` <= `string`. The same
  reduction on the SUPER would be unsound -- there the compound is the SMALLER
  type -- so `(subtypep 'integer '(integer 0 10))` stays nil, which is SBCL's
  answer too.
- **`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)` stay unknown**
  (`OPAQUE_COMPOUND_TYPE_HEADS`): none relates to its head by inclusion. SBCL
  answers `T` for `(member 1 2)` <= `number` and `(eql 1)` <= `number`; the lite
  single-value `subtypep` is allowed its nil, and both compiled and interpreted
  answers agree on it.

**Two premises this item was written on, both overturned by measurement
(2026-08-31).** (1) It expected `SUBTYPEP_PARENTS` to need
`SIMPLE-VECTOR`/`SIMPLE-ARRAY`/`SIMPLE-STRING` edges "or the array half of the
reduction buys nothing" -- it did not need them then: `canonicalSubtypeName`
collapsed those three names onto `VECTOR`/`ARRAY`/`STRING`, and
`subtypepUniverse` already listed them, so `(simple-vector 4)` <= `vector`
answered `T` on every backend the moment the head reduction fired. That collapse
was an ALIAS, though, so the reverse `(subtypep 'vector 'simple-vector)`
answered `T` too, where SBCL answers `(NIL T)`. Fixed by todo-609 -- the section
below.
(2) The reduction exposed a DIFFERENT gap the item did not name: a lattice LEAF
with no `SUBTYPEP_PARENTS` entry (`hash-table`, `function`, `package`,
`stream`, `atom`) had no ancestor-table row at all, so a runtime
`(subtypep 'hash-table 'hash-table)` answered nil on the compile paths and `T`
on the interpreter -- pre-existing, and fatal to `(subtypep (type-of h)
'hash-table)`. `subtypepUniverse` now adds every `RUNTIME_TYPEP_BUILTINS` name
except `T` (not a symbol at run time; the generated `(eq b t)` edge answers it,
and a row would only add the universal ancestor to every other row), so the two
runtime dispatches know the same names.

**The size it costs, measured 2026-08-31.** On the FLOOR program -- a
`(defun st (a b) (subtypep a b))` and one call, i.e. nothing but the gated
dispatch -- 15,937 -> 21,977 wasm bytes at `--optimize=size` (+6,040) and
14,182 -> 21,356 JVM `.class` bytes (+7,174). The compound arm reaches
`symbol-name`/`string=`/`equal` and the widened universe adds table rows; the
gate is unchanged, so a program without a COMPUTED `subtypep` is byte-identical.
No size-report, bench-report or `examples/` program calls `subtypep` at all, so
no tracked row moves. Cheap beside the computed-`typep` floor above (+22 KB) for
the same reason: there is no array arm here, only a head reduction.

Pinned by `LispEvaluatorTest#evalComputedCompoundSubtypepSpecifiers`,
`JvmLispCompilerTest#compileComputedCompoundSubtypepSpecifiers`,
`WasmLispCompilerIntegrationTest#computedCompoundSubtypepSpecifiers` and the
`computed-compound-subtypep-specifier` ci-spec case -- one program whose every
answer is SBCL 2.2.9's on that very program, except the two lite `member`/`eql`
nils above.

## The `simple-` names are lattice EDGES, not aliases (todo-609)

**`simple-vector`, `simple-array` and `simple-string` name strictly smaller
types than `vector`/`array`/`string`, so `subtypep` answers `T` one way and
`NIL` the other, on all four backends.** Until todo-609 `canonicalSubtypeName`
collapsed the three onto their general counterpart. A collapse is symmetric --
it claims two names denote the same SET -- so `(subtypep 'vector
'simple-vector)` answered `T` where SBCL 2.2.9 answers `(NIL T)`, and that
contradicted the very specifier `type-of` builds: since todo-604 it spells
`(SIMPLE-VECTOR 4)` for `(make-array 4)` and `(VECTOR T 4)` for
`(make-array 4 :fill-pointer 0)`.

The edges (`LispMacroExpander.SUBTYPEP_PARENTS`), and what they answer:

- `simple-string` -> `simple-array`, `string`; `simple-vector` ->
  `simple-array`, `vector`; `simple-array` -> `array`.
- So `simple-vector` <= `sequence` (through `vector`) while `simple-array` is
  NOT a sequence -- the atomic `array`/`simple-array` spellings stay rank-blind,
  and SBCL agrees on both.
- `simple-string` is NOT a `simple-vector`: a `simple-vector` is
  `(simple-array t (*))`, and neither is a `string` a `simple-vector`. Both were
  `T` under the collapse.

**Why `string` was a decision and not a fix.** The item offered the "every
string here is immutable, so `simple-string` and `string` are one type" reading
(the float argument). That is true of a LITERAL only, and has not been true of
the string surface as a whole for some time: a fill-pointered character vector,
an `:adjustable t` string and a displaced string VIEW are all `stringp` and none
of them is simple (`.kb/adjustable-arrays.md`). So `simple-string` is a proper
subtype exactly as in CL, and `(subtypep 'string 'simple-string)` is `NIL`.

**`base-string`/`simple-base-string` stay collapsed** (onto `string` /
`simple-string`), because "a string of `base-char`" is the whole content of
those names and rontolisp has ONE character type -- `(subtypep 'character
'base-char)` and its reverse both answer `T`. Same argument as the float family,
and the same **re-evaluation trigger**: if a narrow character type ever lands,
these two must become edges in the same pass. It is the only place the pinning
program deviates from SBCL, which answers `NIL` for `(subtypep 'simple-string
'simple-base-string)` and `(subtypep 'string 'base-string)`.

**The reverse direction was not load-bearing.** The only `(subtypep ...
'simple-*)` in the loadable corpus is quri's `parse-uri` compiler macro, gated
`#+(or sbcl openmcl cmu allegro)` and so dead here; its runtime dispatch is an
`etypecase`, i.e. `typep`, which this did not touch.

**The size, measured 2026-08-31** on the 608 floor program (`(defun st (a b)
(subtypep a b))` and one call): the three names left their alias group and took
rows of their own in the emitted `%subtypep-ancestor-table%`, 21,977 -> 22,087
wasm bytes at `--optimize=size` (+110) and 21,351 -> 21,577 JVM `.class` bytes
(+226). A program with no COMPUTED `subtypep` emits no table and is
byte-identical; no size-report, bench-report or `examples/` program calls
`subtypep` at all.

Pinned by `LispEvaluatorTest#evalSimpleTypeNameSubtypepLattice`,
`JvmLispCompilerTest#compileSimpleTypeNameSubtypepLattice`,
`WasmLispCompilerIntegrationTest#simpleTypeNameSubtypepLattice` and the
`simple-type-name-subtypep-lattice` ci-spec case -- one program whose every
answer is SBCL 2.2.9's on that very program except the two `base-string`
reverse directions above.

## `typep` checks SIMPLICITY, through `%simple-array-p` (todo-610)

**`(typep x 'simple-vector)` and its two siblings answer `NIL` for a value that
is not simple -- a fill pointer, `:adjustable t` or a displacement -- on all
four backends, and the one predicate that decides it is `%simple-array-p`.**
Until todo-610 `makeArrayTypeTest` mapped the `simple-` spellings onto their
general counterpart, so `(typep (make-array 4 :fill-pointer 0) 'simple-vector)`
answered `T` where SBCL 2.2.9 answers `NIL` -- the `typep` half of the lattice
todo-609 had just fixed on the `subtypep` side.

**Why a NEW internal predicate and not a composition.** The obvious spelling,
`(and (not (array-has-fill-pointer-p x)) (not (adjustable-array-p x))
(not (%array-disp-target x)))`, is not total, measured 2026-08-31:

- `array-has-fill-pointer-p` / `adjustable-array-p` REFUSED a packed vector on
  the compile backends (JVM: `not applicable to a packed integer vector`; wasm:
  a cast trap) where the right answer is `t` -- a packed array is simple by
  construction. todo-611 fixed exactly that pair the same day, so this half of
  the argument is now history; the two below are not.
- `%array-disp-target` casts its argument to the general array shape, so it
  throws on a plain string and on a packed vector -- and the displacement is the
  one condition of the three the public surface cannot be asked about at all.
- A value can be BOTH `stringp` and `%arrayp` (a character vector on the
  compile backends) or neither predicate's representation (an interpreter
  `LispString` is not `%arrayp`), so no ordering of the guards covers every
  backend.
- Three calls answer what one does, at four call sites (`typep`, `type-of`,
  `simple-string-p`, `coerce`) that must not drift apart.

So `%simple-array-p` (`LispNames.SIMPLE_ARRAY_P_INTERNAL`, `CL_INTERNALS`) is
TOTAL: it answers `t` for a simple array or string, `nil` for a non-simple one
AND for every non-array value, so a call site needs no guard. Per backend, each
reading the representation it owns:

- **Interpreter** (`Environment`): `LispString` -> no fill pointer, not
  adjustable, not displaced; `LispArray` -> the same three fields;
  `LispFloatArray`/`LispIntVector` -> `t`; anything else -> `nil`.
- **JVM** (`JvmSimpleArrayPCompiler`, inline like `JvmArraypCompiler`): a
  QUOTE-FRAMED `java.lang.String` -> `t` (the frame test is `stringp`'s -- a
  symbol shares the class without it and is no array); `long[]`/`double[]`/
  `float[]` -> `t` behind their program gates; an `ArrayList` -> its slot-0
  header, `nil` when slot 1 (fill pointer) or slot 2 (`:adjustable`) is
  non-null or when the header is length-5+ WITH a non-null slot 3 (displaced --
  the packed general array's length-6 header has a null slot 3 and stays
  simple); anything else -> `nil`.
- **WASM** (`WasmArrayCompiler.compileSimpleArrayP`, so it can read the private
  header helpers): a quote-framed `TYPE_STRING`, `TYPE_FARRAY` or a packed
  integer vector -> 1; a `TYPE_CELL` whose header car is a dims bucket array
  (the `%arrayp` test) -> 0 when `meta.car` is an i31 (fill pointer),
  `meta.cdr.car` is non-null (`:adjustable`) or the data slot holds a target
  (`emitDataSlotIsTarget`, the displacement rule `%array-disp-target` reads).

Where it is wired: the `simple-` arm of `makeArrayTypeTest` (ONE call in front
of the whole string-or-array union), the atomic `simple-array` /
`simple-string` / `simple-base-string` arms, `RUNTIME_COMPOUND_TYPEP_SOURCE`
(both the array and the string family), `simple-string-p`, which CL requires to
agree with `(typep x 'simple-string)` and which used to answer `stringp`, and
`coerce`'s "already of the result type" guard: `(coerce x 'simple-string)` on a
NON-simple string used to answer that string, so `simple-string-p` said nil for
the value the portable idiom had just coerced. It now `copy-seq`s it (literal
and computed designator alike) -- every string BUILDER here already answers a
simple string on all four backends (`concatenate`, `map`, `copy-seq`, `subseq`,
`make-string`, `format nil`, `string-upcase`, `reverse`, measured 2026-08-31),
so the copy converges. `simple-vector` also stopped being "vector, spelled differently":
atomic and compound both build `(simple-array t (*))`, so a string and a packed
vector are not one -- SBCL's answer, and the compound spelling already did it.
`base-string`/`simple-base-string` stay collapsed onto `string`/`simple-string`
(one character type, the todo-609 argument).

**One JVM representation bug this forced out.** A `make-array :element-type
'character` with no `:fill-pointer` -- `make-string` included -- defaulted its
header fill-pointer slot to the CAPACITY, so
`(array-has-fill-pointer-p (make-string 3))` answered `T` on the JVM and `NIL`
on the other three backends and in SBCL. Mutability is the character-vector
MARKER's, not the fill pointer's (`_strv` already falls back to `dims[0]`, and
`_subseqCv` already CLEARED that slot to make a `copy-seq` result simple), so
the slot now stays nil. Without it the JVM could not tell a simple character
vector from a fill-pointered one at all.

**The size, measured 2026-08-31** (baseline: `origin/develop` at e3126bdc):

| program | wasm `--optimize=size` | JVM `.class` |
| --- | --- | --- |
| `(print 1)` (array-free) | 497 -> 497, byte-identical | 3,007 -> 3,007, byte-identical |
| size-report `hello_world` / `pi_approx` | 0 | 0 |
| size-report `zlib` | 103,158 -> 103,158 | 160,877 -> 160,875 (-2) |
| a literal `(typep v 'simple-vector)` over one array | 11,401 -> 12,036 (+635) | 8,973 -> 9,602 (+629) |
| `(type-of v)` over one array | 21,485 -> 21,648 (+163) | 45,137 -> 44,995 (-142) |
| computed-`typep` FLOOR (`(defun tp (v s) (typep v s))`) | 50,914 -> 53,833 (+2,919) | 86,243 -> 91,370 (+5,127) |
| computed-`subtypep` floor | 22,087 -> 22,087, byte-identical | 21,576 -> 21,576, byte-identical |

No tracked size-report or bench-report row moves. The computed-`typep` floor is
where it costs: three new `RUNTIME_TYPEP_BUILTINS` rows (`SIMPLE-STRING`,
`SIMPLE-VECTOR`, `SIMPLE-ARRAY`, without which a computed
`(typep x 'simple-vector)` answered `NIL` for every value) plus the `simple-`
conjuncts in the compound source. "Check only where the specifier says
`simple-`" is what shipped, so a program with no `simple-` specifier and no
computed `typep` pays nothing.

Pinned by `LispEvaluatorTest#evalSimpleTypeNameTypepChecksSimplicity`,
`JvmLispCompilerTest#compileSimpleTypeNameTypepChecksSimplicity`,
`WasmLispCompilerIntegrationTest#simpleTypeNameTypepChecksSimplicity` and the
`simple-type-name-typep-simplicity` ci-spec case -- one program whose every
answer is SBCL 2.2.9's on that very program.

That gap is closed by the section below.

## A sized string specifier measures the DIMENSION (`%string-dimension`, todo-613)

**`(typep x '(string n))` -- and every sibling spelling, `(simple-string n)`,
`(base-string n)`, `(vector character n)`, `(simple-array character (n))` --
compares `n` against the array DIMENSION of the string, never against `length`,
on all four backends.** `length` of a fill-pointered character vector is the
FILL POINTER, so until todo-613 a capacity-4 vector with fill pointer 0 was a
`(string 0)` and not a `(string 4)`; SBCL 2.2.9 answers the other way round, and
CL's sized specifier is the dimension. The ARRAY arm was already right (it goes
through `array-dimension`), which is why `(vector t 4)` over a fill-pointered
general array always agreed with SBCL; only the STRING arm was wrong.

It was wrong because at the time it could not use those functions:
`array-dimensions` / `array-rank` / `array-total-size` / `adjustable-array-p` /
`array-has-fill-pointer-p` all REFUSED a string on the compile paths. (They no
longer do -- the arms landed with the todo-610/611 simplicity work and the
element-type-preserving `adjust-array`, and are pinned as of 2026-09-02;
`.kb/adjustable-arrays.md`, "Every array-info reader answers for a string".
`%string-dimension` still stands, for the size reason below.) The two ways out
were to fix that refusal and read the size through the public surface, or to
give the string arm an internal accessor of its own. **The internal accessor
won on measurement** (2026-08-31, wasm at
`--optimize=size` / JVM `.class`, `stringp`-only floor 9,642 / 3,136):

| the sized-string test, one program | wasm | `.class` |
| --- | --- | --- |
| before, through `length` | 10,856 | 5,632 |
| **`%string-dimension` (shipped)** | **10,582** | **5,391** |
| `(and (stringp v) (eq (array-dimension v 0) n))` | 10,212 | 6,499 |

The `array-dimension` row is a LOWER BOUND that option cannot reach: it does not
include the string arm `array-dimensions` would have to grow, which for an
immutable string is the same `_str_char_count` walk `%string-dimension` inlines
(~900 wasm bytes here) plus the character-vector dispatch beside it. Add that
and it is the largest of the three on wasm as well as on the JVM, where it is
already +1,108 bytes -- there because reading a dimension turns `ctx.usesArrays`
on and drags the whole array runtime into a program that has a string test and
no arrays. **The shipped answer is SMALLER than the `length` it replaced on both
backends**, because `length` is the wide `_seq_len` / `_length` sequence
dispatch and this is two representations.

`%string-dimension` (`LispNames.STRING_DIMENSION_INTERNAL`, `CL_INTERNALS`) is
NOT total the way `%simple-array-p` is, and does not need to be: every call site
is inside a string arm that `stringp` has already gated, which is how a type
test reaches it at all. Per backend:

- **Interpreter** (`Environment`): `LispString.capacity()`, which reports a
  displaced string view's own span.
- **JVM** (`JvmStringDimensionCompiler`, a per-class `_strDim` shared helper so a
  site is one `invokestatic`): a quote-framed `java.lang.String` -> `_scount`,
  the character-visible count, so a supplementary code point counts as one
  exactly as `length` counts it; an `ArrayList` -> `header[0][0]`, the boxed
  `Long` dimension of the length-4 character vector or the length-7 string view.
  The `ArrayList` arm is emitted only under `ctx.usesArrays`, like
  `stringp`'s -- no character vector can exist without it.
- **WASM** (`WasmArrayCompiler.compileStringDimension`, inline, for the private
  header helpers): `TYPE_STRING` -> `_str_char_count`; otherwise the `TYPE_CELL`
  header's car is the dims bucket array and `dims[0]` is the answer.

Wired at the four sizing sites, which is every place a specifier carries a
string size: the string arm of `makeArrayTypeTest`, the `STRING` /
`SIMPLE-STRING` / `BASE-STRING` / `SIMPLE-BASE-STRING` arm of
`makeCompoundTypeTest`, and BOTH string arms of
`RUNTIME_COMPOUND_TYPEP_SOURCE` (the string family and the array family's string
half), so a computed specifier answers what a literal one does.

**`.todo/464` is not closed by this and is not blocked on it either.** That item
is about the PUBLIC surface -- `(array-dimensions "abc")` still traps on both
compile paths where the interpreter answers `(3)` -- and when it is done, its
`array-dimensions` string arm is the natural CALLER of `%string-dimension`
rather than a second copy of the same dispatch. Nothing here reads a string's
rank, so the rest of that family is untouched.

**Size elsewhere**: the array-free program and the `(typep v 'simple-vector)`
floor are BYTE-IDENTICAL (497 / 12,036 wasm, 3,017 / 9,601 `.class`). The
computed-`typep` floor pays for the two inlined reads in
`%typep-compound-runtime`: 53,833 -> 53,919 wasm (+86), 91,376 -> 91,487
`.class` (+111).

One divergence this measurement found and did NOT touch, because it is a
different mechanism: `vector-push-extend`'s DEFAULT extension. Growing a
capacity-2 vector to five elements leaves dimension 8 in SBCL, 8 on the
interpreter for a character vector but 5 for a general one, and 5 on the JVM for
both -- so `array-dimension` (and therefore a sized specifier over a grown
vector) disagrees across backends and within the interpreter. `.todo/614`.

Pinned by the same four places todo-610 uses -- the two new groups at the end of
`LispEvaluatorTest#evalSimpleTypeNameTypepChecksSimplicity`,
`JvmLispCompilerTest#compileSimpleTypeNameTypepChecksSimplicity`,
`WasmLispCompilerIntegrationTest#simpleTypeNameTypepChecksSimplicity` and the
`simple-type-name-typep-simplicity` ci-spec case, whose answers are SBCL 2.2.9's
on that very program.

## Top-level flattening (flattenTopLevel)

`LispMacroExpander.flattenTopLevel(program)` recursively splices top-level
`(progn ...)` and `(eval-when (sits) ...)` into top-level forms. Called at:

1. `UserMacroExpander.expand` entry (CLI path) -- so the
   `(eval-when (:compile-toplevel ...) (defmacro ...))` idiom registers the
   macro at compile time,
2. `JvmLispCompiler.compile` / `WasmLispCompiler.compile` /
   `NoGcWasmCompiler.compile`, right before/at the top-level preprocessing
   (`expandTopLevelDefstructs`) -- so Pass 1 collects nested defuns in direct
   compiler invocations (compiler unit tests bypass the CLI).

The interpreter does NOT flatten: it evaluates `eval-when` natively as
`progn`, which reaches the same result at runtime. A malformed
`(eval-when)` without a situation list is left unspliced so the expander's
validation error surfaces.

Known gap: `LoadInliner` runs BEFORE UserMacroExpander, so a top-level
`(load ...)`/`(require ...)`/`(asdf:load-system ...)` wrapped in `eval-when`
is NOT inlined on the compile path (it falls through to the runtime `load`).
Unwrapped directives are unaffected.

## Wiring points (the usual macro checklist)

`LispNames` constants; `LispEvaluator.evalCons` cases; `Jvm/WasmExprCompiler`
compileCons cases; `NoGcWasmCompiler.expandMacro`; `FreeVarAnalyzer` BOTH
methods (explicit cases that expand first -- the default walk would misread
the type symbol in `(the integer x)` as a free variable reference and collect
declaration specifiers); `expandBuiltinMacro`.

## Pinning tests

- `LispEvaluatorTest`: `evalDeclareIsANoOp`, `evalDeclaimAndProclaimAreNoOps`,
  `evalTheReturnsTheValue`, `evalEvalWhenActsAsProgn`,
  `evalEvalWhenWrappedDefmacro`, `evalCheckType`,
  `evalCheckTypeSatisfiesAndMember`, `evalAssert`,
  `evalTypecaseCompoundSpecifiers`.
- `JvmLispCompilerTest`: `compileAndRunDeclarations`, `compileAndRunThe`,
  `compileAndRunEvalWhen`,
  `compileAndRunEvalWhenWrappedDefmacroThroughUserMacroExpander`,
  `compileAndRunCheckType`, `compileAndRunAssert`.
- `WasmLispCompilerIntegrationTest`: `declarationsTheAndEvalWhen`,
  `checkTypeAndAssertForms`.
- ci-spec: `declarations-eval-when-check-type-assert`.
