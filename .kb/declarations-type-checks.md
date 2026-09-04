# Declarations: no-op semantics, declaration-driven array emission, eval-when, check-type/assert

Declarations are NOT pure no-ops: on wasm-GC a `(declare (type ...))` (or a
`defstruct` slot `:type`) drives single-arm array-access EMISSION; on the JVM a
`(declare (type double-float ...))` routes arithmetic onto the unboxed IEEE path
(`.kb/jvm-double-arithmetic.md`). Interpreter and `--no-gc` ignore every
declaration.

## The false-declaration policy (all backends)

**A declaration may change EMISSION, never the RESULT of a correctly-declared
program -- and where a backend TRUSTS a declaration, a FALSE one becomes a
DETERMINISTIC error at the site the trusted representation meets the contradicting
value, never a silently coerced or wrong value.**

- **wasm-GC** (Preview 1 and `--component`): a `ref.cast` TRAP at the access,
  uncatchable. A checked signal would cost the dispatch bytes the feature removes.
- **JVM**: a `checkcast java/lang/Double` failure -- a `ClassCastException` the
  condition bridge surfaces as a CATCHABLE Lisp error. Catchability is a side
  effect, not a promise. One softening: an integer LITERAL assigned to a declared
  float variable widens at compile time (`(setq sum 0)` answers `0.0d0`).
- **Interpreter and `--no-gc`**: ignored; the oracle for CORRECTLY-declared
  programs only.

A falsely-declared program DIVERGES across backends (CL: undefined behavior);
documented in `doc/*/reference/macros/declare.md`. Coercing through `_dbl` was
rejected: silently WRONG data, still divergent. Pinned by ci-spec
`declared-float-scalars-answer-what-undeclared-code-answers` and
`declared-array-types-single-arm-access`.

## What ships

Seven `LispMacroExpander` expansions (no per-backend codegen), in
`PackageRegistry.CL_MACROS` with `expandBuiltinMacro` cases so `macroexpand-1`
works:

- `declare`, `declaim`, `proclaim` -> `nil`; arguments never evaluated or
  validated (`proclaim` deviates from CL).
- `the` -> its value form (identity).
- `eval-when` -> `progn` of the body (every situation = "evaluate now").
- `check-type` -> `(let ((__check-type place)) (if <type-test> nil (error "The
  value of <place> is ~s, which is not of type <spec>." __check-type)))`. Place
  and spec printed at expansion time; only the value is a runtime `~s` argument.
  An optional third string replaces the "of type ..." part.
- `assert` -> `(if test nil (error ...))`; the places list is dropped (no
  `continue`/`store-value` restart; the restart system exists --
  `.kb/error-handling.md`).

## Declaration-driven array emission (wasm-GC only)

A rank-1 `aref`/`(setf aref)`/`length` site whose array representation is pinned
emits that ONE accessor behind a trapping `ref.cast` instead of the inline 4-way
dispatch chain. Preview 1 AND `--component` only. It exists because 21.1% of
chipz `zlib`'s instruction bytes at `--optimize=size` were those chains (196 B per
rank-1 `aref` site; 232-1540 B per `%aset` site, index/value re-emitted per ARM).
**Arithmetic is NOT where the size is**: with fusion off a generic `+`/`logand`/
`ash` is one 2-byte helper call, so declaration-driven arithmetic emission was
deliberately NOT built (possible SPEED lever at the default level; re-measure).

### Kinds and their sources

`am.ik.rontolisp.compiler.DeclaredArrayTypes` (backend-free) maps a specifier to
a `Kind`: `U8`/`U16`/`U32` (packed integer vector -- requires an explicitly rank-1
dims spec, `(simple-array (unsigned-byte 8) (*))`, since an unknown-rank spec
could be a rank-n GENERAL array of the same element type), `FLOAT` (packed float
array, either width), `GENERAL` (boxed general array: `simple-vector`, `fixnum`/
`t`/other unpacked element types, any rank), `STRING`. A character element type
maps to NOTHING (a character vector is a marked general array OR a string after
normalization; above rank 1 neither -- `.kb/array-literals.md`); a bare
`vector`/`array` proves nothing.

Specifier symbols resolve through the `ClosRegistry` deftype table, including the
defaulted registration: an all-`&optional` deftype (`simple-octet-vector`)
registers its bare-name DEFAULT expansion, folded by a closed pure evaluator over
`quote`/`list`/`cons`/`append`/`or`/`let`/`let*`
(`LispMacroExpander.defaultedDeftypeExpansion`; the CLHS unsupplied-optional
default is the symbol `*`). The CLI path folds such deftypes by evaluation
instead (`LispEvaluator.foldDeftype`, via `UserMacroExpander`).

Four sources (`WasmArrayCompiler.arrayKindOfExpr`):

1. **A declared lexical variable** -- `Ctx.declaredArrays`, scoped exactly like
   `Ctx.locals`: registered from body-head `(declare (type ...))` by the
   defun/lambda body setup (`WasmLispCompiler`, following the sole trailing
   `%fn-block`/`(block name ...)` wrapper `LambdaLists` and the flet lowering
   produce) and by `WasmLetCompiler` (bound AND free declarations; a free
   declaration covers the body, where a lambda list's generated `let*` prologue
   leaves parameter declarations); shadowed names removed, restored on scope exit.
   Specials never registered; skipped at top level and in async bodies.
2. **A binding INITIALIZER this compile chose a representation for**
   (`initExprKind`): a literal packed/general `make-array` (rank-1 literal size,
   no fill-pointer/adjustable/displacement keywords), a slot-typed accessor call,
   or a kinded outer variable -- only while the body never reassigns the name.
   Types `do`-bound table variables and `loop with` bindings.
3. **A `defstruct` slot `:type` read through its accessor call** -- captured by
   `expandDefstruct` into `ClosRegistry.registerStructSlotType` (a side table;
   `LispLayout` stays type-free), `:include` children inheriting parent slot
   types. Trusted only while the accessor's generated body is the ONE definition
   the call can reach: off under `--dynamic`, off for any name defined more than
   once (`Ctx.duplicatedDefunNames`).
4. **A `(the spec expr)` wrap** at the array position.

### What the kinded sites emit

- `aref` rank-1 (`emitKindedAref1`): `ref.cast` + direct read; u8/u16 box
  `ref.i31` inline, u32 through `_int_new`, FLOAT/GENERAL/STRING reuse the generic
  arm bodies. ~19-30 B against 196.
- `%aset` rank-1 (`emitKindedAset1`): same single arm; the packed-int arm keeps
  the raw-value fast path (`tryCompileRaw`). A STRING kind never stores (immutable
  structs), so kinded emission declines.
- `length` of a packed kind: `ref.cast` + `array.len` + `ref.i31`. Other kinds
  keep the generic chain (a GENERAL vector's length must honour a fill pointer).
- `(setf (aref var i) v)` on a VARIABLE place of non-string kind skips the
  expansion's `stringp`/`schar-set` branch (`nonStringArefStore`, intercepted at
  both `WasmExprCompiler` setf sites).
- A `replace`/`fill` whose DESTINATION is pinned non-string calls the
  array-arm-only shared runtime (`provesArrayValue`,
  `.kb/sequence-op-runtimes.md`); a false declaration traps in the arm's
  `%row-major-aset`. That predicate also answers on a WEAKER fact `Kind` cannot
  carry -- "an array of some representation", from a `make-array` whose rank is a
  runtime fact -- tracked in `Ctx.arrayLocals`. **`initExprKind` must keep
  refusing those**: it picks an accessor and a wrong rank is a wrong accessor.
- Independent of declarations: at `--optimize=size` the GENERIC rank-1 `%aset`
  hoists array/index/value into temps once (`emitHoistedAset1`). Speed levels keep
  the legacy shape on purpose: its packed-int arm compiles the value RAW through
  fusion, which a pre-boxed temp would defeat.

Emission at EVERY optimize level. Evaluation order (array, index, value) is the
generic order; a false declaration may observe index/value side effects before the
cast traps where the generic general arm would have trapped first -- inside UB.

Pinned by
`WasmLispCompilerIntegrationTest.declaredArrayTypesEmitSingleArmAccessorsWithoutChangingResults`,
ci-spec `declared-array-types-single-arm-access`, `ChipzE2eTest`.

## makeTypeTest (shared type-specifier tests)

`LispMacroExpander.makeTypeTest(value, spec)` builds a truthy test form;
`makeTypecaseTest` delegates to it, so `typecase`/`etypecase` heads accept the
same specs as `check-type`.

- `pathname` -> the `%PATHNAME` instance-tag test (`.kb/pathnames.md`);
  `pathnamep` answers the same test. With the instance gate off it compiles to
  constant nil, also correct.
- **EMPTY types** (constant-nil): `bit-vector`/`simple-bit-vector` (no bit-vector
  value exists; a typecase bit-vector clause falls through to its vector clause),
  `generic-function`/`standard-generic-function` (a defgeneric dispatcher is a
  plain function value with no marker -- routes trivia level2's `(etypecase fn
  (generic-function ...))` onto its portable test-call fallback),
  `structure-class`/`built-in-class` (a defstruct class metaobject IS a
  standard-class, `.kb/instance-syntax.md` -- routes trivia's `(typecase
  (find-class type) ...)` onto its `t` = slot-value branch). All six agree with
  runtime `typep`'s nil and are in `PackageRegistry.CL_TYPES`.
  `CLASS`/`STRUCTURE`/`TYPE` joined CL_TYPES for a different reason: they are the
  CL symbols trivia NAMES its class/structure/type patterns with, and the
  defpattern site and a user's pattern site must resolve to the same bare spelling
  or the pattern-namespace lookup misses.
- **Atomic map** (`atomicTypePredicate`): integer/fixnum/bignum -> `integerp`,
  float* -> `floatp`, number/real -> `numberp`, rational/ratio -> `rationalp`,
  string, symbol, keyword, cons, list, null, atom, character, hash-table,
  function -> `functionp`; `boolean` -> `(or (null v) (eq v t))`;
  `unsigned-byte` -> `(and (integerp v) (>= v 0))`; `array` ->
  `(or (stringp v) (%arrayp v))` (rank NOT checked -- that separates it from
  `vector`, whose atomic arm goes through `makeArrayTypeTest`); `sequence` adds
  `listp`; `t`/`otherwise` -> t; literal `nil` -> nil. The three `simple-`
  spellings add `%simple-array-p`. `functionp` is a real public builtin
  (Environment + Jvm/WasmFunctionpCompiler: JVM = Object[] with an Integer funcId
  in slot 0, WASM = `ref.test TYPE_CLOSURE`); `%arrayp` is CL_INTERNALS-only (JVM
  = `instanceof java.util.ArrayList`; WASM tests the header car for the dims array
  vs the i31 count).
- **Compound**: `or`/`and`/`not` recurse; `(member items...)` ->
  `(member v '(items))`; `(eql obj)` -> `(eql v 'obj)`; `(satisfies fn)` ->
  `(fn v)`; `(integer|float|rational|real|number [low [high]])` -> base predicate
  + bound checks, `*` unbounded, `(n)` exclusive.
- **Type-specifier symbols match by package-STRIPPED name** (`plainTypeName`):
  standard type names are not all registered CL symbols, so in a user package the
  resolver would qualify e.g. `unsigned-byte` to `pkg::unsigned-byte`.
  `PackageRegistry.CL_TYPES` registers the common type-only names (float family,
  unsigned-byte, sequence, satisfies, otherwise, ...) so they resolve BARE --
  required where the name reaches RUNTIME data, e.g. parse-number's
  `'double-float` compared by the runtime `coerce` dispatch (symbols compare by
  name).
- **The four float type names are ONE type, and `subtypep` says so on purpose**
  (`canonicalSubtypeName` collapses `single-float`/`double-float`/`short-float`/
  `long-float` to `FLOAT`). One float format here; CLHS permits as few as one, so
  `(subtypep 'single-float 'double-float)` is genuinely `T`. postmodern's
  `json-encoder.lisp` `eval-when` probes these pairs and takes its
  `:cl-json-only-one-float-type` branch. **Re-evaluation trigger**: if a distinct
  single-float lands, `canonicalSubtypeName` must stop collapsing AND the lattice
  must gain the real `single-float <= double-float <= long-float` edges in the
  SAME pass, or every library probing the float lattice silently takes the wrong
  branch. Pinned by ci-spec `postmodern-language-incidentals` (asserts `(T T)`).
  `subtypep` returns ONE value here (CL returns a second "certain?" value).
- **A collapse in `canonicalSubtypeName` claims two names denote the SAME SET, so
  it answers `T` in both directions** -- the whole membership test.
- `deftype` is a parsed no-op returning nil (the name is NOT registered; using it
  in a later type test errors).
- The value form may be evaluated several times, so callers bind a temp first
  (`__check-type` / typecase's `__typecase`).

## The array type lattice: type-of BUILDS the compound specifier

**`type-of` answers an array's COMPOUND specifier and `makeTypeTest` takes the
same one back -- one contract, they must move together.** Shapes are SBCL 2.2.9's:

| value | `type-of` |
| --- | --- |
| `(make-array 4)` | `(SIMPLE-VECTOR 4)` |
| `(make-array '(2 3))` | `(SIMPLE-ARRAY T (2 3))` |
| `(make-array nil)` | `(SIMPLE-ARRAY T NIL)` (rank-0) |
| `(make-array 4 :element-type 'single-float)` | `(SIMPLE-ARRAY SINGLE-FLOAT (4))` |
| `(make-array 4 :element-type '(unsigned-byte 8))` | `(SIMPLE-ARRAY (UNSIGNED-BYTE 8) (4))` |
| `:fill-pointer 0` / `:adjustable t` / `:displaced-to v` | `(VECTOR T 4)` |
| `(make-array '(2 3) :adjustable t)` | `(ARRAY T (2 3))` |
| `"abc"` | `STRING` (SBCL: `(SIMPLE-ARRAY CHARACTER (3))`) |

- **`type-of` is the PRELUDE defun** (`LispPreludeLibrary`), one source for four
  backends. Its array arm fires only where `%class-designator` answers the
  uninformative `T` AND `%arrayp` is true -- the guard keeping CHARACTER arrays out
  (a rank-1 character array is a string VALUE on the interpreter, a marked general
  array on the compile paths; both DESIGNATE `STRING`). **The SIMPLICITY arm is
  tested FIRST**: a remembered element type and a fill pointer can coexist
  (`(make-array 4 :element-type 'double-float :fill-pointer 0)` is `(VECTOR
  DOUBLE-FLOAT 4)`), and a non-simple array is `(VECTOR et size)` at rank 1,
  `(ARRAY et dims)` above. It asks `%simple-array-p`, ONE total predicate, not
  `array-has-fill-pointer-p`/`adjustable-array-p`: neither can see a displacement,
  which is why a displaced array used to answer `(SIMPLE-VECTOR 2)`.
- **`array-element-type` answers the BOOLEAN `t`** for a general array asked for
  nothing narrower, on the interpreter too (it used to answer a SYMBOL spelled
  `"T"`, so `(eq (array-element-type a) t)` disagreed across backends). `type-of`
  reads exactly that to choose `(simple-vector n)` vs `(simple-array et dims)`. A
  general array asked for something narrower -- `character` or `(unsigned-byte n)`
  above rank 1, any of them with `:fill-pointer`/`:adjustable` -- answers that
  type (`.kb/array-literals.md`, "The degraded array REMEMBERS its element type").
- **`makeArrayTypeTest`** builds `(array ET DIMS)` / `(simple-array ET DIMS)` /
  `(vector ET SIZE)` / `(simple-vector SIZE)` as the union of a STRING arm and an
  ARRAY arm (a string is a rank-1 character array in CL but not a representation
  `%arrayp` knows). The string arm survives only while the specifier can still
  describe one (element type character or unstated, rank 1 or unstated) and sizes
  itself with `%string-dimension`; the array arm reads `array-element-type` and
  the dimensions behind the `%arrayp` guard. A `simple-` spelling ANDs ONE
  `%simple-array-p` in front of the whole union.
- **The element type is compared UPGRADED** (`upgradedArrayElementType`),
  mirroring what `make-array` selects: the two float widths and the three packed
  `(unsigned-byte 8|16|32)` widths keep their name, the character family answers
  `character`, everything else -- `fixnum`, `integer`, `bit`, a class -- lands in
  the general boxed array with element type `t`. So `(typep a '(simple-array
  fixnum (4)))` is a `t`-array test: conformant, and the one shape
  array-operations' suite still fails on. A deftype ALIAS resolves first
  (`resolveElementTypeAlias`).
- **Dimensions** compare as a whole when every one is literal (one
  `array-dimensions` read against the quoted list, `nil` for rank-0), else a rank
  check plus one `array-dimension` read per pinned dimension. Both VECTOR
  spellings pin the rank to 1, keeping a rank-2 array out of a `(simple-vector
  41)` test rather than reaching `length`, which refuses a non-sequence.
- **The ATOMIC `vector` spellings check the rank too**: `vectorp` and a bare
  `vector`/`simple-vector` clause answer `T` only for a rank-1 array (string
  included). Not a second copy -- both call `makeArrayTypeTest` with an
  unspecified element type and a `(*)` shape. Only atomic `array`/`simple-array`
  stay rank-blind. **Trap:** `expandVectorp` takes the `arraysExist` flag and
  drops the whole array arm when the gate is off -- `vectorp` has an injected
  first-class wrapper every program carries until the shaker runs, and an ungated
  rank read there put `_arrayDims` in `(print 1)` and forced the entire array
  runtime back on (`.kb/adjustable-arrays.md`).
- **A COMPUTED specifier takes the same set as a literal one.** `type-of` handing
  a program a compound specifier made `(typep a (type-of a))` answer nil, because
  the runtime dispatch was a `cond` keyed on the specifier SYMBOL. Both dispatch
  shapes now route a CONS specifier to the section below.

## The COMPOUND half of the runtime typep dispatch

**A computed specifier takes exactly the set a literal one does, because the
compound families are interpreted out of the specifier VALUE by one Lisp source
both dispatch shapes carry** -- `LispMacroExpander.RUNTIME_COMPOUND_TYPEP_SOURCE`,
instantiated over a value form, a specifier form and the recursion operator
(`substituteSymbols` on three placeholder symbols). The interpreter's
`expandRuntimeTypep` inlines it with the recursion spelled `typep`; the compile
paths put it in a `%typep-compound-runtime` defun whose recursion is
`%typep-runtime`. **`%typep-runtime`'s FIRST cond arm is the `consp` route into
it** -- before the instance branch, since an instance is an ordinary member of an
`(or foo bar)` and the tag table only knows type NAMES. The defun is separate from
`%typep-runtime` because of the JVM's 16-bit branch offsets.

Every arm is the runtime twin of a `makeCompoundTypeTest` arm, reading arguments
out of the value instead of the AST: the array family (element type compared
UPGRADED; string and array arm are the same union `makeArrayTypeTest` builds),
`or`/`and`/`not`, `member`/`eql`/`satisfies`, `(cons car cdr)`, the sized `string`
spellings, `(unsigned-byte n)`/`(signed-byte n)`, and a default arm recursing on
the head symbol ALONE then applying the range bounds -- which makes a compound
spelling of a NON-numeric atomic type (`(hash-table ...)`) answer through its base
predicate. The head is matched by `symbol-name`, the runtime spelling of
`plainTypeName`. The three narrower float names are in `RUNTIME_TYPEP_BUILTINS`.

Size: emitted once per program under the computed-`typep` gate, but it reaches the
generic array accessors and `funcall`. `hello-clack` Worker (`--no-wasi
--optimize=size`) +5.2% raw / +5.9% gzipped, ~half of it the ARRAY arm alone. No
computed `typep` -> byte-identical. **Re-evaluation trigger**: if a Worker row
needs the bytes back, move the array arm into a defun of its own on a narrower
gate.

Pinned by `LispEvaluatorTest#evalComputedCompoundTypeSpecifiers`,
`JvmLispCompilerTest#compileComputedCompoundTypeSpecifiers`,
`WasmLispCompilerIntegrationTest#computedCompoundTypeSpecifiers`, ci-spec
`computed-compound-type-specifier`. The `type-of` half:
`LispEvaluatorTest#evalTypeOfAndTypepAnswerTheCompoundArraySpecifier`,
`JvmLispCompilerTest#compileTypeOfAndTypepAnswerTheCompoundArraySpecifier`,
`WasmLispCompilerIntegrationTest#typeOfAndTypepAnswerTheCompoundArraySpecifier`,
ci-spec `array-type-of-and-compound-array-specifier`. The atomic `vector` half:
`LispEvaluatorTest#evalVectorpChecksTheRank`,
`JvmLispCompilerTest#compileVectorpChecksTheRank`,
`WasmLispCompilerIntegrationTest#compileVectorpChecksTheRank`, ci-spec
`vectorp-and-the-vector-specifier-check-the-rank`.

A rank-n (n>1) CHARACTER array is the plain general array on ALL backends and
still REMEMBERS its element type, so `type-of` answers `(SIMPLE-ARRAY CHARACTER
dims)` everywhere (`.kb/array-literals.md`).

## A `deftype` ALIAS resolves at RUN TIME too

**A type designator held in a VALUE resolves a user `deftype` exactly as its
literal spelling does, on all four backends.** `coerce` with a computed result
type is the same hole twice (its fall-through arm IS a computed `typep`, its
family dispatch reads the designator's head), so both close together.

- interpreter `expandRuntimeTypep` re-expands per call against the live registry,
  spelling `(setq tn (cond ((equal tn 'ALIAS) '<expansion>) ... (t tn)))` inline
  -- `LispMacroExpander.deftypeAliasResolution`, which nothing emits;
- compile paths put the alias set in a quoted DATA table (`%deftype-alias-table%`,
  through `chunkedTableForms`) scanned by one shared `%deftype-alias` defun,
  called once from `%typep-runtime` (`runtimeTypepDefun`'s `(setq tn
  (%deftype-alias tn))`), AFTER the metaobject normalization that turns a class
  object into the name this reads.

`%typep-compound-runtime` recurses back into `%typep-runtime`, so an alias INSIDE
a compound specifier resolves through the same normalization. Alias CHAINS are
followed when the table is built (one hop at run time); a self-referential
`(deftype a () 'a)` terminates on the same 16-hop bound `resolveElementTypeAlias`
uses. **A name the dispatch already decides -- a built-in spelling, a registered
class, a struct -- is left OUT of the table**: the literal path resolves a
`deftype` only after those three, so normalizing first would reorder the reading.

**The narrowing is what makes it affordable.** One `cond` arm per alias, or the
data table alone, cost the array-operations program (`ql:quickload`, one
`aops:zeros*`, raw wasm `--optimize=size`) about **+10,000 B, +10.7%** -- the cost
is the alias NAMES, not the dispatch arms: alexandria's 43 aliases at three
spellings each (`ALEXANDRIA::POSITIVE-FIXNUM`, `ALEXANDRIA:POSITIVE-FIXNUM`,
`POSITIVE-FIXNUM`) = 129 long symbols at ~56 B apiece. What bought it is a SECOND
narrowing: **the table carries only the aliases the program SPELLS**
(`narrowedDeftypeAliases`, closed afterwards under the alias references its own
entries make, since `proper-sequence` expands to `(or proper-list ...)`).
Narrowed, array-operations carries 2 entries and pays +1,765 B (+1.9%); `zlib`,
`pi_approx`, `hello_world` byte-identical.

The one shape the narrowing does not cover: a designator built at run time out of
characters -- `(typep x (intern (read-line)))` -- answers `nil` on the compile
paths; the interpreter resolves it.

Pinned by `LispEvaluatorTest#evalRuntimeTypepResolvesADeftypeAlias`,
`JvmLispCompilerTest#compileRuntimeTypepResolvesADeftypeAlias`,
`WasmLispCompilerIntegrationTest#compileRuntimeTypepResolvesADeftypeAlias`,
ci-spec `runtime-typep-deftype-alias`.

The `make-array :element-type` half of the same gap is `.kb/array-literals.md`,
"A `deftype` ALIAS held in a VARIABLE resolves before the dispatch" -- a DIFFERENT
narrowing (only aliases naming one of the six specialized codes).

## The COMPOUND half of `subtypep`

**A compound specifier works on either side of `subtypep`, quoted or computed, and
answers the same on all four backends.** Written twice --
`LispMacroExpander.subtypep` over the AST (the interpreter's builtin AND the
literal fold) and `RUNTIME_COMPOUND_SUBTYPEP_SOURCE` over a runtime specifier
VALUE (the arm `%subtypep-runtime` routes a cons to, before the by-name table
scan). Twins, not one source: the static side must stay Java because the emitted
ancestor table is GENERATED from it. **Change them together.**

- **A type is a subtype of itself**, compound included (`equal` on the specifiers).
- **`(or ...)`**: any branch as the super, EVERY branch as the sub.
  **`(and ...)`**: every conjunct as the super, ANY conjunct as the sub.
- **Any other head as the SUB reduces to that head and re-tests**: `(integer 0
  10)` <= `integer`, `(simple-array t (2 2))` <= `array`, `(string 2)` <=
  `string`. The same reduction on the SUPER would be unsound, so `(subtypep
  'integer '(integer 0 10))` stays nil (SBCL agrees).
- **`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)` stay unknown**
  (`OPAQUE_COMPOUND_TYPE_HEADS`). SBCL answers `T` for `(member 1 2)` <= `number`
  and `(eql 1)` <= `number`; the lite single-value `subtypep` is allowed its nil.

**Trap:** a lattice LEAF with no `SUBTYPEP_PARENTS` entry (`hash-table`,
`function`, `package`, `stream`, `atom`) had no ancestor-table row, so a runtime
`(subtypep 'hash-table 'hash-table)` answered nil on the compile paths and `T` on
the interpreter -- fatal to `(subtypep (type-of h) 'hash-table)`.
`subtypepUniverse` now adds every `RUNTIME_TYPEP_BUILTINS` name except `T` (not a
symbol at run time; the generated `(eq b t)` edge answers it).

No COMPUTED `subtypep` -> byte-identical; no size-report, bench-report or
`examples/` program calls `subtypep`.

Pinned by `LispEvaluatorTest#evalComputedCompoundSubtypepSpecifiers`,
`JvmLispCompilerTest#compileComputedCompoundSubtypepSpecifiers`,
`WasmLispCompilerIntegrationTest#computedCompoundSubtypepSpecifiers`, ci-spec
`computed-compound-subtypep-specifier`.

## The `simple-` names are lattice EDGES, not aliases

**`simple-vector`, `simple-array` and `simple-string` name strictly smaller types
than `vector`/`array`/`string`, so `subtypep` answers `T` one way and `NIL` the
other, on all four backends.**

Edges (`LispMacroExpander.SUBTYPEP_PARENTS`): `simple-string` -> `simple-array`,
`string`; `simple-vector` -> `simple-array`, `vector`; `simple-array` -> `array`.
So `simple-vector` <= `sequence` (through `vector`) while `simple-array` is NOT a
sequence; `simple-string` is NOT a `simple-vector` (a `simple-vector` is
`(simple-array t (*))`), nor is a `string`.

**`string` was a decision, not a fix.** "Every string here is immutable" is true
of a LITERAL only: a fill-pointered character vector, an `:adjustable t` string
and a displaced string VIEW are all `stringp` and none is simple
(`.kb/adjustable-arrays.md`). So `(subtypep 'string 'simple-string)` is `NIL`.

**`base-string`/`simple-base-string` stay collapsed** onto `string`/
`simple-string`: ONE character type here, so `(subtypep 'character 'base-char)`
and its reverse both answer `T`. Same **re-evaluation trigger**: if a narrow
character type lands, these two must become edges in the same pass. It is the only
place the pinning program deviates from SBCL, which answers `NIL` for
`(subtypep 'simple-string 'simple-base-string)` and `(subtypep 'string
'base-string)`.

Pinned by `LispEvaluatorTest#evalSimpleTypeNameSubtypepLattice`,
`JvmLispCompilerTest#compileSimpleTypeNameSubtypepLattice`,
`WasmLispCompilerIntegrationTest#simpleTypeNameSubtypepLattice`, ci-spec
`simple-type-name-subtypep-lattice`.

## `typep` checks SIMPLICITY, through `%simple-array-p`

**`(typep x 'simple-vector)` and its two siblings answer `NIL` for a value that is
not simple -- a fill pointer, `:adjustable t` or a displacement -- on all four
backends, and the one predicate that decides it is `%simple-array-p`.**

Why not `(and (not (array-has-fill-pointer-p x)) (not (adjustable-array-p x))
(not (%array-disp-target x)))`: `%array-disp-target` casts to the general array
shape, so it throws on a plain string and on a packed vector -- and the
displacement is the one condition the public surface cannot be asked about at all;
a value can be BOTH `stringp` and `%arrayp` (a character vector on the compile
backends) or neither representation (an interpreter `LispString` is not
`%arrayp`), so no ordering of guards covers every backend; and three calls at four
call sites (`typep`, `type-of`, `simple-string-p`, `coerce`) must not drift.

`%simple-array-p` (`LispNames.SIMPLE_ARRAY_P_INTERNAL`, `CL_INTERNALS`) is TOTAL:
`t` for a simple array or string, `nil` for a non-simple one AND every non-array
value, so a call site needs no guard.

- **Interpreter** (`Environment`): `LispString` -> no fill pointer, not
  adjustable, not displaced; `LispArray` -> the same three fields;
  `LispFloatArray`/`LispIntVector` -> `t`; else `nil`.
- **JVM** (`JvmSimpleArrayPCompiler`, inline like `JvmArraypCompiler`): a
  QUOTE-FRAMED `java.lang.String` -> `t` (the frame test is `stringp`'s -- a
  symbol shares the class without it); `long[]`/`double[]`/`float[]` -> `t` behind
  their program gates; an `ArrayList` -> its slot-0 header, `nil` when slot 1
  (fill pointer) or slot 2 (`:adjustable`) is non-null, or when the header is
  length-5+ WITH a non-null slot 3 (displaced -- the packed general array's
  length-6 header has a null slot 3 and stays simple); else `nil`.
- **WASM** (`WasmArrayCompiler.compileSimpleArrayP`, for the private header
  helpers): a quote-framed `TYPE_STRING`, `TYPE_FARRAY` or packed integer vector
  -> 1; a `TYPE_CELL` whose header car is a dims bucket array -> 0 when `meta.car`
  is an i31 (fill pointer), `meta.cdr.car` is non-null (`:adjustable`) or the data
  slot holds a target (`emitDataSlotIsTarget`).

Wired at: the `simple-` arm of `makeArrayTypeTest`, the atomic `simple-array` /
`simple-string` / `simple-base-string` arms, `RUNTIME_COMPOUND_TYPEP_SOURCE`
(both array and string family), `simple-string-p` (CL requires it to agree with
`(typep x 'simple-string)`; it used to answer `stringp`), and `coerce`'s "already
of the result type" guard: `(coerce x 'simple-string)` on a NON-simple string used
to answer that string, and now `copy-seq`s it -- every string BUILDER already
answers a simple string on all four backends (`concatenate`, `map`, `copy-seq`,
`subseq`, `make-string`, `format nil`, `string-upcase`, `reverse`), so the copy
converges. `simple-vector` also stopped being "vector, spelled differently":
atomic and compound both build `(simple-array t (*))`.

**One JVM representation bug this forced out.** A `make-array :element-type
'character` with no `:fill-pointer` -- `make-string` included -- defaulted its
header fill-pointer slot to the CAPACITY, so `(array-has-fill-pointer-p
(make-string 3))` answered `T` on the JVM and `NIL` everywhere else. Mutability is
the character-vector MARKER's, not the fill pointer's (`_strv` falls back to
`dims[0]`, `_subseqCv` already CLEARED that slot), so the slot stays nil.

Size: array-free and no-`simple-`/no-computed-`typep` programs are byte-identical;
the computed-`typep` FLOOR pays for three new `RUNTIME_TYPEP_BUILTINS` rows
(`SIMPLE-STRING`, `SIMPLE-VECTOR`, `SIMPLE-ARRAY`, without which a computed
`(typep x 'simple-vector)` answered `NIL` for every value) plus the `simple-`
conjuncts. "Check only where the specifier says `simple-`" is what shipped.

Pinned by `LispEvaluatorTest#evalSimpleTypeNameTypepChecksSimplicity`,
`JvmLispCompilerTest#compileSimpleTypeNameTypepChecksSimplicity`,
`WasmLispCompilerIntegrationTest#simpleTypeNameTypepChecksSimplicity`, ci-spec
`simple-type-name-typep-simplicity`.

## A sized string specifier measures the DIMENSION (`%string-dimension`)

**`(typep x '(string n))` -- and every sibling spelling, `(simple-string n)`,
`(base-string n)`, `(vector character n)`, `(simple-array character (n))` --
compares `n` against the array DIMENSION of the string, never against `length`, on
all four backends.** `length` of a fill-pointered character vector is the FILL
POINTER. The ARRAY arm was always right (`array-dimension`); only the STRING arm
was wrong. The internal accessor is also SMALLER than the `length` it replaced on
both compile backends (`length` is the wide `_seq_len`/`_length` sequence
dispatch); the public-surface alternative would drag `ctx.usesArrays` and the
whole array runtime into a program with a string test and no arrays.

`%string-dimension` (`LispNames.STRING_DIMENSION_INTERNAL`, `CL_INTERNALS`) is NOT
total the way `%simple-array-p` is and need not be: every call site is inside a
string arm `stringp` has already gated.

- **Interpreter** (`Environment`): `LispString.capacity()`, which reports a
  displaced string view's own span.
- **JVM** (`JvmStringDimensionCompiler`, a per-class `_strDim` helper so a site is
  one `invokestatic`): a quote-framed `java.lang.String` -> `_scount`, the
  character-visible count (a supplementary code point counts as one); an
  `ArrayList` -> `header[0][0]`, the boxed `Long` dimension of the length-4
  character vector or the length-7 string view. That arm is emitted only under
  `ctx.usesArrays`, like `stringp`'s.
- **WASM** (`WasmArrayCompiler.compileStringDimension`, inline): `TYPE_STRING` ->
  `_str_char_count`; else the `TYPE_CELL` header's car is the dims bucket array
  and `dims[0]` is the answer.

Wired at the four sizing sites: the string arm of `makeArrayTypeTest`, the
`STRING` / `SIMPLE-STRING` / `BASE-STRING` / `SIMPLE-BASE-STRING` arm of
`makeCompoundTypeTest`, and BOTH string arms of `RUNTIME_COMPOUND_TYPEP_SOURCE`.

`array-dimensions` / `array-rank` / `array-total-size` / `adjustable-array-p` /
`array-has-fill-pointer-p` now answer for a string on the compile paths
(`.kb/adjustable-arrays.md`, "Every array-info reader answers for a string");
`%string-dimension` still stands, for the size reason above. `.todo/464` (the
PUBLIC `(array-dimensions "abc")` surface) is not closed by this and not blocked
on it; its string arm would be a CALLER of `%string-dimension`.

**Unresolved divergence, different mechanism:** `vector-push-extend`'s DEFAULT
extension. Growing a capacity-2 vector to five elements leaves dimension 8 in
SBCL, 8 on the interpreter for a character vector but 5 for a general one, and 5
on the JVM for both -- so `array-dimension` (and a sized specifier over a grown
vector) disagrees across backends and within the interpreter. `.todo/614`.

Pinned by the same four places as the simplicity work (two new groups in each).

## Top-level flattening (flattenTopLevel)

`LispMacroExpander.flattenTopLevel(program)` recursively splices top-level
`(progn ...)` and `(eval-when (sits) ...)` into top-level forms. Called at:

1. `UserMacroExpander.expand` entry (CLI path) -- so `(eval-when
   (:compile-toplevel ...) (defmacro ...))` registers the macro at compile time;
2. `JvmLispCompiler.compile` / `WasmLispCompiler.compile` /
   `NoGcWasmCompiler.compile`, right before/at `expandTopLevelDefstructs` -- so
   Pass 1 collects nested defuns in direct compiler invocations.

The interpreter does NOT flatten: it evaluates `eval-when` natively as `progn`. A
malformed `(eval-when)` without a situation list is left unspliced so the
expander's validation error surfaces.

**Known gap:** `LoadInliner` runs BEFORE UserMacroExpander, so a top-level
`(load ...)`/`(require ...)`/`(asdf:load-system ...)` wrapped in `eval-when` is
NOT inlined on the compile path (it falls through to the runtime `load`).
Unwrapped directives are unaffected.

## Wiring points

`LispNames` constants; `LispEvaluator.evalCons` cases; `Jvm/WasmExprCompiler`
compileCons cases; `NoGcWasmCompiler.expandMacro`; `FreeVarAnalyzer` BOTH methods
(explicit cases that expand first -- the default walk would misread the type
symbol in `(the integer x)` as a free variable reference and collect declaration
specifiers); `expandBuiltinMacro`.

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
