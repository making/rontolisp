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
  `unsigned-byte` -> `(and (integerp v) (>= v 0))`; `vector`/`simple-vector`/
  `array`/`simple-array` -> `(or (stringp v) (%arrayp v))` (the rank is NOT
  checked -- a rank read would drag the gated array helpers into every
  typecase-using program); `sequence` adds `listp`; `t`/`otherwise` -> t;
  literal `nil` spec -> nil (the empty type). `functionp` is a real public
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
| `(make-array 4 :fill-pointer 0)` / `:adjustable t` | `(VECTOR T 4)` |
| `"abc"` | `STRING` (SBCL: `(SIMPLE-ARRAY CHARACTER (3))`) |

Where this lives and why:

- **`type-of` is the PRELUDE defun** (`LispPreludeLibrary`), so one source
  serves all four backends. Its array arm fires only where
  `%class-designator` answers the uninformative `T` AND `%arrayp` is true. That
  guard is what keeps CHARACTER arrays out: a rank-1 character array is a
  string VALUE on the interpreter and a marked general array on the compile
  paths, and both DESIGNATE `STRING` -- so all four answer `STRING` rather than
  diverging. The element-type arm is tested BEFORE the fill-pointer arm because
  a packed representation is always simple (make-array degrades to the general
  one the moment `:fill-pointer`/`:adjustable` appears) and
  `array-has-fill-pointer-p`/`adjustable-array-p` REFUSE a packed array on
  every backend.
- **`array-element-type` answers the BOOLEAN `t`** for a general array, on the
  interpreter too since todo-604 -- it used to answer a SYMBOL spelled `"T"`
  there while all three compile backends answered the boolean, so
  `(eq (array-element-type a) t)` disagreed across backends. `type-of` reads
  exactly that answer to choose between `(simple-vector n)` and
  `(simple-array et dims)`.
- **`makeArrayTypeTest`** (`LispMacroExpander`) builds the test for
  `(array ET DIMS)` / `(simple-array ET DIMS)` / `(vector ET SIZE)` /
  `(simple-vector SIZE)`. It is the union of a STRING arm and an ARRAY arm,
  because a string is a rank-1 character array in CL but not one of the
  representations `%arrayp` knows: the string arm survives only while the
  specifier can still describe one (element type character or unstated, rank 1
  or unstated) and sizes itself with `length`, since the array-info functions
  do not take a string on the compile paths (`.todo/464`). The array arm reads
  `array-element-type` and the dimensions behind the `%arrayp` guard.
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

**Deliberately NOT closed: `subtypep`.** It takes the same computed specifiers
and is still name-only past `(or ...)`, and the two halves already DISAGREE
across backends -- measured 2026-08-31, `(let ((s '(or fixnum ratio)))
(subtypep s 'number))` answers `T` on the interpreter (whose `subtypep` builtin
calls the Java `LispMacroExpander.subtypep`, which handles an `or` on either
side) and `NIL` on the JVM (whose `%subtypep-runtime` scans an ancestor table by
NAME and matches no cons). Closing it means the static lattice AND the runtime
table in one pass, which is why it is its own item (`.todo/608`), not a rider on
this one.

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
plain general array and `type-of` answers `(SIMPLE-ARRAY T dims)` everywhere.
The model and its cost are `.kb/array-literals.md`, "A SPECIALIZED element type
above rank 1 is the general array".

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
