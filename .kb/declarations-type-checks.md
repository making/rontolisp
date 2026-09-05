# Declarations: no-op semantics, declaration-driven array emission, eval-when, check-type/assert

Declarations are NOT pure no-ops: on wasm-GC a `(declare (type ...))` (or a `defstruct` slot
`:type`) drives single-arm array-access EMISSION; on the JVM a `(declare (type double-float ...))`
routes arithmetic onto the unboxed IEEE path
([jvm-double-arithmetic.md](jvm-double-arithmetic.md)). Interpreter and `--no-gc` ignore every
declaration.

## The false-declaration policy (all backends)
**A declaration may change EMISSION, never the RESULT of a correctly-declared program -- and where
a backend TRUSTS a declaration, a FALSE one becomes a DETERMINISTIC error at the site the trusted
representation meets the contradicting value, never a silently coerced or wrong value.**

- wasm-GC: an uncatchable `ref.cast` TRAP at the access (a checked signal would cost the dispatch
  bytes the feature removes). JVM: a `checkcast java/lang/Double` failure, surfaced by the
  condition bridge as a CATCHABLE Lisp error -- catchability is a side effect, not a promise; one
  softening, an integer LITERAL assigned to a declared float variable widens at compile time.
  Interpreter and `--no-gc` are the oracle for CORRECTLY-declared programs only.
- A falsely-declared program DIVERGES across backends (CL: undefined behavior), documented in
  `doc/*/reference/macros/declare.md`. Coercing through `_dbl` was rejected: silently WRONG data,
  still divergent.

## What ships
Seven `LispMacroExpander` expansions (no per-backend codegen), in `PackageRegistry.CL_MACROS` with
`expandBuiltinMacro` cases so `macroexpand-1` works:

- `declare`/`declaim`/`proclaim` -> `nil`, arguments never evaluated or validated (`proclaim`
  deviates from CL); `the` -> its value form; `eval-when` -> `progn` of the body.
- `check-type` -> `(let ((__check-type place)) (if <type-test> nil (error "...~s...")))`, place and
  spec printed at EXPANSION time, only the value a runtime `~s` argument; an optional third string
  replaces the "of type ..." part.
- `assert` -> `(if test nil (error ...))`; the places list is dropped (no `continue`/`store-value`
  restart, though the restart system exists, [error-handling.md](error-handling.md)).

## Declaration-driven array emission (wasm-GC only)
A rank-1 `aref`/`(setf aref)`/`length` site whose array representation is pinned emits that ONE
accessor behind a trapping `ref.cast` instead of the inline 4-way dispatch chain (196 B per rank-1
`aref` site, 232-1540 B per `%aset` site; 21.1% of chipz `zlib`'s instruction bytes at
`--optimize=size`). **Arithmetic is NOT where the size is** -- with fusion off a generic
`+`/`logand`/`ash` is one 2-byte helper call -- so declaration-driven arithmetic emission was
deliberately NOT built (a possible SPEED lever; re-measure).

`compiler.DeclaredArrayTypes` (backend-free) maps a specifier to a `Kind`: `U8`/`U16`/`U32`
(requires an explicitly rank-1 dims spec, since an unknown-rank spec could be a rank-n GENERAL
array of the same element type), `FLOAT`, `GENERAL`, `STRING`. A character element type maps to
NOTHING; a bare `vector`/`array` proves nothing. Specifier symbols resolve through the
`ClosRegistry` deftype table including the DEFAULTED registration -- an all-`&optional` deftype
registers its bare-name default expansion, folded by a closed pure evaluator over
`quote`/`list`/`cons`/`append`/`or`/`let`/`let*` (`LispMacroExpander.defaultedDeftypeExpansion`;
the CLHS unsupplied-optional default is the symbol `*`); the CLI path folds by evaluation instead
(`LispEvaluator.foldDeftype`).

Four sources (`WasmArrayCompiler.arrayKindOfExpr`):
1. A declared lexical variable -- `Ctx.declaredArrays`, scoped exactly like `Ctx.locals`, from
   body-head declarations (following the sole trailing `%fn-block`/`(block name ...)` wrapper) and
   from `WasmLetCompiler` (bound AND free declarations; a free declaration covers the body, where a
   lambda list's generated `let*` prologue leaves parameter declarations). Specials never
   registered; skipped at top level and in async bodies.
2. A binding INITIALIZER this compile chose a representation for (`initExprKind`): a literal
   packed/general `make-array` (rank-1 literal size, no fill-pointer/adjustable/displacement), a
   slot-typed accessor call, or a kinded outer variable -- only while the body never reassigns the
   name. Types `do`-bound table variables and `loop with` bindings.
3. A `defstruct` slot `:type` read through its accessor, captured by `expandDefstruct` into
   `ClosRegistry.registerStructSlotType` (a side table; `LispLayout` stays type-free), `:include`
   children inheriting parent slot types. Trusted only while the accessor's generated body is the
   ONE definition the call can reach: off under `--dynamic`, off for any name defined more than
   once (`Ctx.duplicatedDefunNames`).
4. A `(the spec expr)` wrap at the array position.

Emission: `emitKindedAref1` is `ref.cast` + direct read (~19-30 B against 196; u8/u16 box
`ref.i31` inline, u32 through `_int_new`); `emitKindedAset1` is the same single arm, its packed-int
arm keeping the raw-value fast path (`tryCompileRaw`), and a STRING kind never stores so kinded
emission declines; `length` of a packed kind is `ref.cast` + `array.len` + `ref.i31`, other kinds
keeping the generic chain because a GENERAL vector's length must honour a fill pointer.

- `(setf (aref var i) v)` on a VARIABLE place of non-string kind skips the expansion's
  `stringp`/`schar-set` branch (`nonStringArefStore`, intercepted at both `WasmExprCompiler` setf
  sites).
- A `replace`/`fill` whose DESTINATION is pinned non-string calls the array-arm-only shared runtime
  (`provesArrayValue`, [sequence-op-runtimes.md](sequence-op-runtimes.md)); a false declaration
  traps in the arm's `%row-major-aset`. That predicate also answers on the WEAKER fact "an array of
  some representation" (`Ctx.arrayLocals`) -- **`initExprKind` must keep refusing those**: it picks
  an accessor and a wrong rank is a wrong accessor.
- Independent of declarations: at `--optimize=size` the GENERIC rank-1 `%aset` hoists array/index/
  value into temps once (`emitHoistedAset1`); speed levels keep the legacy shape, whose packed-int
  arm compiles the value RAW through fusion.
- Emission at EVERY optimize level, in the generic (array, index, value) evaluation order; a false
  declaration may observe index/value side effects before the cast traps -- inside UB.

## makeTypeTest (shared type-specifier tests)
`LispMacroExpander.makeTypeTest(value, spec)` builds a truthy test form; `makeTypecaseTest`
delegates to it, so `typecase`/`etypecase` heads accept the same specs as `check-type`. The value
form may be evaluated several times, so callers bind a temp (`__check-type` / `__typecase`).

- `pathname` -> the `%PATHNAME` instance-tag test ([pathnames.md](pathnames.md)); with the instance
  gate off it compiles to constant nil, also correct.
- **EMPTY types** (constant-nil, agreeing with runtime `typep`, all in `PackageRegistry.CL_TYPES`):
  `bit-vector`/`simple-bit-vector` (no bit-vector value exists),
  `generic-function`/`standard-generic-function` (a defgeneric dispatcher is a plain function value
  with no marker), `structure-class`/`built-in-class` (a defstruct class metaobject IS a
  standard-class) -- the last two routing trivia level2 onto its portable fallbacks.
  `CLASS`/`STRUCTURE`/`TYPE` joined CL_TYPES for a different reason: trivia NAMES its patterns with
  them, and the defpattern site and a user's pattern site must resolve to the same bare spelling.
- **Atomic map** (`atomicTypePredicate`): integer/fixnum/bignum -> `integerp`, float* -> `floatp`,
  number/real -> `numberp`, rational/ratio -> `rationalp`, plus string, symbol, keyword, cons, list,
  null, atom, character, hash-table, function -> `functionp`; `boolean` -> `(or (null v) (eq v t))`;
  `unsigned-byte` -> `(and (integerp v) (>= v 0))`; `array` -> `(or (stringp v) (%arrayp v))` (rank
  NOT checked -- that separates it from `vector`, whose atomic arm goes through
  `makeArrayTypeTest`); `sequence` adds `listp`; `t`/`otherwise` -> t; literal `nil` -> nil. The
  three `simple-` spellings add `%simple-array-p`. `functionp` is a real public builtin (JVM =
  `Object[]` with an Integer funcId in slot 0, WASM = `ref.test TYPE_CLOSURE`); `%arrayp` is
  CL_INTERNALS-only.
- **Compound**: `or`/`and`/`not` recurse; `(member items...)` -> `(member v '(items))`;
  `(eql obj)` -> `(eql v 'obj)`; `(satisfies fn)` -> `(fn v)`;
  `(integer|float|rational|real|number [low [high]])` -> base predicate + bound checks, `*`
  unbounded, `(n)` exclusive.
- **Type-specifier symbols match by package-STRIPPED name** (`plainTypeName`): standard type names
  are not all registered CL symbols, so in a user package the resolver would qualify e.g.
  `unsigned-byte`. `CL_TYPES` registers the common type-only names so they resolve BARE -- required
  where the name reaches RUNTIME data (symbols compare by name).
- **The four float type names are ONE type, and `subtypep` says so on purpose**
  (`canonicalSubtypeName` collapses `single-`/`double-`/`short-`/`long-float` to `FLOAT`; CLHS
  permits as few as one format, and postmodern's `json-encoder.lisp` probes it). A collapse claims
  two names denote the SAME SET, so it answers `T` in both directions. **Trigger: if a distinct
  single-float lands, `canonicalSubtypeName` must stop collapsing AND the lattice must gain the
  real edges in the SAME pass**, or every library probing the float lattice silently takes the
  wrong branch. `subtypep` returns ONE value here; `deftype` is a parsed no-op in this path.

## The array type lattice: type-of BUILDS the compound specifier
**`type-of` answers an array's COMPOUND specifier and `makeTypeTest` takes the same one back -- one
contract, they must move together.** Shapes are SBCL 2.2.9's: `(SIMPLE-VECTOR 4)`,
`(SIMPLE-ARRAY T (2 3))`, `(SIMPLE-ARRAY T NIL)` for rank-0, `(SIMPLE-ARRAY SINGLE-FLOAT (4))`,
`(VECTOR T 4)` for fill-pointer/adjustable/displaced, `(ARRAY T (2 3))` above rank 1, and `STRING`
for `"abc"` (SBCL: `(SIMPLE-ARRAY CHARACTER (3))`).

- **`type-of` is the PRELUDE defun** (`LispPreludeLibrary`), one source for four backends. Its
  array arm fires only where `%class-designator` answers the uninformative `T` AND `%arrayp` is
  true -- the guard keeping CHARACTER arrays out (a rank-1 character array is a string VALUE on the
  interpreter, a marked general array on the compile paths; both DESIGNATE `STRING`). **The
  SIMPLICITY arm is tested FIRST**: a remembered element type and a fill pointer can coexist. It
  asks `%simple-array-p`, not `array-has-fill-pointer-p`/`adjustable-array-p`, neither of which can
  see a displacement -- which is why a displaced array used to answer `(SIMPLE-VECTOR 2)`.
- **`array-element-type` answers the BOOLEAN `t`** for a general array asked for nothing narrower,
  on the interpreter too; asked for something narrower it answers that type
  ([array-literals.md](array-literals.md)).
- **`makeArrayTypeTest`** builds the four spellings as the union of a STRING arm and an ARRAY arm.
  The string arm survives only while the specifier can still describe one (element type character
  or unstated, rank 1 or unstated) and sizes itself with `%string-dimension`; the array arm reads
  `array-element-type` and the dimensions behind the `%arrayp` guard. A `simple-` spelling ANDs ONE
  `%simple-array-p` in front of the whole union.
- **The element type is compared UPGRADED** (`upgradedArrayElementType`): the two float widths and
  the three packed `(unsigned-byte 8|16|32)` widths keep their name, the character family answers
  `character`, everything else -- `fixnum`, `integer`, `bit`, a class -- lands in the general boxed
  array with element type `t`. So `(typep a '(simple-array fixnum (4)))` is a `t`-array test:
  conformant, and the one shape array-operations' suite still fails on. A deftype ALIAS resolves
  first (`resolveElementTypeAlias`).
- **Dimensions** compare as a whole when every one is literal (one `array-dimensions` read against
  the quoted list, `nil` for rank-0), else a rank check plus one `array-dimension` read per pinned
  dimension. Both VECTOR spellings pin the rank to 1, as do `vectorp` and a bare
  `vector`/`simple-vector` clause; only atomic `array`/`simple-array` stay rank-blind.
- **Trap:** `expandVectorp` takes the `arraysExist` flag and drops the whole array arm when the gate
  is off -- `vectorp` has an injected first-class wrapper every program carries until the shaker
  runs, and an ungated rank read there put `_arrayDims` in `(print 1)` and forced the entire array
  runtime back on ([adjustable-arrays.md](adjustable-arrays.md)).
- A rank-n (n>1) CHARACTER array is the plain general array on ALL backends and still REMEMBERS its
  element type, so `type-of` answers `(SIMPLE-ARRAY CHARACTER dims)` everywhere.

## The COMPOUND half of the runtime typep dispatch
**A computed specifier takes exactly the set a literal one does, because the compound families are
interpreted out of the specifier VALUE by one Lisp source both dispatch shapes carry** --
`LispMacroExpander.RUNTIME_COMPOUND_TYPEP_SOURCE`, instantiated over a value form, a specifier form
and the recursion operator (`substituteSymbols` on three placeholder symbols). The interpreter's
`expandRuntimeTypep` inlines it with the recursion spelled `typep`; the compile paths put it in a
`%typep-compound-runtime` defun -- separate from `%typep-runtime` because of the JVM's 16-bit
branch offsets -- whose recursion is `%typep-runtime`.

- **`%typep-runtime`'s FIRST cond arm is the `consp` route into it**, before the instance branch,
  since an instance is an ordinary member of an `(or foo bar)` and the tag table only knows NAMES.
- Every arm is the runtime twin of a `makeCompoundTypeTest` arm, reading arguments out of the value
  instead of the AST: the array family (element type compared UPGRADED, the same union
  `makeArrayTypeTest` builds), `or`/`and`/`not`, `member`/`eql`/`satisfies`, `(cons car cdr)`, the
  sized `string` spellings, `(unsigned-byte n)`/`(signed-byte n)`, and a default arm recursing on
  the head symbol ALONE then applying the range bounds, which makes a compound spelling of a
  NON-numeric atomic type answer through its base predicate. The head is matched by `symbol-name`,
  the runtime spelling of `plainTypeName`; the three narrower float names are in
  `RUNTIME_TYPEP_BUILTINS`.
- Size: emitted once per program under the computed-`typep` gate, but it reaches the generic array
  accessors and `funcall`. `hello-clack` Worker (`--no-wasi --optimize=size`) +5.2% raw / +5.9%
  gzipped, ~half the ARRAY arm alone; no computed `typep` -> byte-identical. **Trigger: if a Worker
  row needs the bytes back, move the array arm into a defun of its own on a narrower gate.**

## A `deftype` ALIAS resolves at RUN TIME too
**A type designator held in a VALUE resolves a user `deftype` exactly as its literal spelling does,
on all four backends.** `coerce` with a computed result type is the same hole twice, so both close
together. The interpreter's `expandRuntimeTypep` re-expands per call against the live registry
(`LispMacroExpander.deftypeAliasResolution`, which nothing emits); the compile paths put the alias
set in a quoted DATA table (`%deftype-alias-table%`, through `chunkedTableForms`) scanned by one
shared `%deftype-alias` defun called once from `%typep-runtime`, AFTER the metaobject normalization
that turns a class object into the name this reads.

- `%typep-compound-runtime` recurses back into `%typep-runtime`, so an alias INSIDE a compound
  specifier resolves through the same normalization. Alias CHAINS are followed when the table is
  built (one hop at run time); `(deftype a () 'a)` terminates on the same 16-hop bound
  `resolveElementTypeAlias` uses.
- **A name the dispatch already decides -- a built-in spelling, a registered class, a struct -- is
  left OUT of the table**: the literal path resolves a `deftype` only after those three, so
  normalizing first would reorder the reading.
- **The narrowing is what makes it affordable.** The unnarrowed table cost array-operations about
  +10,000 B (+10.7%) -- the cost is the alias NAMES, not the arms (alexandria's 43 aliases at three
  spellings each = 129 long symbols at ~56 B). **The table carries only the aliases the program
  SPELLS** (`narrowedDeftypeAliases`, closed afterwards under the alias references its own entries
  make): array-operations then carries 2 entries and pays +1,765 B (+1.9%).
- Not covered: a designator built at run time out of characters (`(typep x (intern (read-line)))`)
  answers `nil` on the compile paths; the interpreter resolves it. The `make-array :element-type`
  half is [array-literals.md](array-literals.md), a DIFFERENT narrowing.

## The COMPOUND half of `subtypep`
**A compound specifier works on either side of `subtypep`, quoted or computed, and answers the same
on all four backends.** Written twice -- `LispMacroExpander.subtypep` over the AST (the
interpreter's builtin AND the literal fold) and `RUNTIME_COMPOUND_SUBTYPEP_SOURCE` over a runtime
specifier VALUE. Twins, not one source: the static side must stay Java because the emitted ancestor
table is GENERATED from it. **Change them together.**

- A type is a subtype of itself, compound included (`equal` on the specifiers). `(or ...)`: any
  branch as the super, EVERY branch as the sub; `(and ...)`: every conjunct as the super, ANY
  conjunct as the sub.
- **Any other head as the SUB reduces to that head and re-tests** (`(integer 0 10)` <= `integer`);
  the same reduction on the SUPER would be unsound, so `(subtypep 'integer '(integer 0 10))` stays
  nil (SBCL agrees). `(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)` stay unknown
  (`OPAQUE_COMPOUND_TYPE_HEADS`), the lite single-value `subtypep` allowed its nil.
- **Trap:** a lattice LEAF with no `SUBTYPEP_PARENTS` entry (`hash-table`, `function`, `package`,
  `stream`, `atom`) had no ancestor-table row, so a runtime `(subtypep 'hash-table 'hash-table)`
  answered nil on the compile paths and `T` on the interpreter. `subtypepUniverse` now adds every
  `RUNTIME_TYPEP_BUILTINS` name except `T` (not a symbol at run time; the generated `(eq b t)` edge
  answers it). No COMPUTED `subtypep` -> byte-identical.

## The `simple-` names are lattice EDGES, not aliases
**`simple-vector`, `simple-array` and `simple-string` name strictly smaller types than
`vector`/`array`/`string`, so `subtypep` answers `T` one way and `NIL` the other, on all four
backends.** `LispMacroExpander.SUBTYPEP_PARENTS`: `simple-string` -> `simple-array`, `string`;
`simple-vector` -> `simple-array`, `vector`; `simple-array` -> `array`. So `simple-vector` <=
`sequence` (through `vector`) while `simple-array` is NOT a sequence, and `simple-string` is NOT a
`simple-vector`.

- **`string` was a decision, not a fix**: "every string here is immutable" is true of a LITERAL only
  -- a fill-pointered character vector, an `:adjustable t` string and a displaced string VIEW are
  all `stringp` and none is simple. So `(subtypep 'string 'simple-string)` is `NIL`.
- **`base-string`/`simple-base-string` stay collapsed** onto `string`/`simple-string`: ONE character
  type here. **Same trigger: if a narrow character type lands, these two must become edges in the
  same pass.** It is the only place the pinning program deviates from SBCL.

## `typep` checks SIMPLICITY, through `%simple-array-p`
**`(typep x 'simple-vector)` and its two siblings answer `NIL` for a value that is not simple -- a
fill pointer, `:adjustable t` or a displacement -- on all four backends, and the one predicate that
decides it is `%simple-array-p`** (`LispNames.SIMPLE_ARRAY_P_INTERNAL`, `CL_INTERNALS`), which is
TOTAL: `t` for a simple array or string, `nil` for a non-simple one AND every non-array value, so a
call site needs no guard.

Why not the public probes: `%array-disp-target` casts to the general array shape, so it throws on a
plain string and on a packed vector -- and displacement is the one condition the public surface
cannot be asked about at all; a value can be BOTH `stringp` and `%arrayp` or neither, so no ordering
of guards covers every backend; and three calls at four call sites must not drift. Implementations:
`Environment` (the three fields off `LispString`/`LispArray`; `LispFloatArray`/`LispIntVector` ->
`t`), `JvmSimpleArrayPCompiler` (a QUOTE-FRAMED `String`, the packed arrays behind their gates, else
an `ArrayList`'s slot-0 header -- nil on a non-null fill-pointer or `:adjustable` slot, or a
length-5+ header with a non-null slot 3, the packed general array's length-6 header having a null
slot 3 and staying simple), `WasmArrayCompiler.compileSimpleArrayP` (`meta.car` i31,
`meta.cdr.car`, `emitDataSlotIsTarget`).

Wired at: the `simple-` arm of `makeArrayTypeTest`, the atomic
`simple-array`/`simple-string`/`simple-base-string` arms, `RUNTIME_COMPOUND_TYPEP_SOURCE` (both
families), `simple-string-p` (CL requires it to agree with `(typep x 'simple-string)`; it used to
answer `stringp`), and `coerce`'s "already of the result type" guard -- `(coerce x 'simple-string)`
on a NON-simple string now `copy-seq`s it, and every string BUILDER already answers a simple string
on all four backends, so the copy converges. `simple-vector` also stopped being "vector, spelled
differently": atomic and compound both build `(simple-array t (*))`.

**One JVM representation bug this forced out**: `make-array :element-type 'character` with no
`:fill-pointer` -- `make-string` included -- defaulted its header fill-pointer slot to the CAPACITY,
so `(array-has-fill-pointer-p (make-string 3))` answered `T` on the JVM only. Mutability is the
character-vector MARKER's, not the fill pointer's, so the slot stays nil. Size: the computed-`typep`
FLOOR pays for three new `RUNTIME_TYPEP_BUILTINS` rows plus the `simple-` conjuncts; array-free and
no-`simple-` programs are byte-identical.

## A sized string specifier measures the DIMENSION (`%string-dimension`)
**`(typep x '(string n))` -- and `(simple-string n)`, `(base-string n)`, `(vector character n)`,
`(simple-array character (n))` -- compares `n` against the array DIMENSION of the string, never
against `length`, on all four backends** (`length` of a fill-pointered character vector is the FILL
POINTER). The internal accessor is also SMALLER than the `length` it replaced on both compile
backends, and the public-surface alternative would drag `ctx.usesArrays` and the whole array runtime
into a program with a string test and no arrays.

`%string-dimension` (`LispNames.STRING_DIMENSION_INTERNAL`, `CL_INTERNALS`) is NOT total and need
not be: every call site is inside a string arm `stringp` has already gated. Implementations:
`LispString.capacity()` (which reports a displaced view's own span), `JvmStringDimensionCompiler`
(a per-class `_strDim` helper so a site is one `invokestatic`; `_scount` for a quote-framed String,
`header[0][0]` for an `ArrayList`, emitted only under `ctx.usesArrays`) and
`WasmArrayCompiler.compileStringDimension` (`_str_char_count`, else the header's `dims[0]`).

- Wired at four sizing sites: the string arm of `makeArrayTypeTest`, the
  `STRING`/`SIMPLE-STRING`/`BASE-STRING`/`SIMPLE-BASE-STRING` arm of `makeCompoundTypeTest`, and
  BOTH string arms of `RUNTIME_COMPOUND_TYPEP_SOURCE`.
- `array-dimensions`/`array-rank`/`array-total-size`/`adjustable-array-p`/
  `array-has-fill-pointer-p` now answer for a string on the compile paths
  ([adjustable-arrays.md](adjustable-arrays.md)); `%string-dimension` still stands for the size
  reason. `.todo/464` (the PUBLIC `(array-dimensions "abc")` surface) is not closed by this.
- **Unresolved divergence, different mechanism:** `vector-push-extend`'s DEFAULT extension. Growing
  a capacity-2 vector to five elements leaves dimension 8 in SBCL, 8 on the interpreter for a
  character vector but 5 for a general one, and 5 on the JVM for both. `.todo/614`.

## Top-level flattening (flattenTopLevel)
`LispMacroExpander.flattenTopLevel(program)` recursively splices top-level `(progn ...)` and
`(eval-when (sits) ...)` into top-level forms. Called at `UserMacroExpander.expand` entry (so an
`eval-when`-wrapped `defmacro` registers at compile time) and at
`Jvm`/`Wasm`/`NoGcWasmCompiler.compile` right before `expandTopLevelDefstructs` (so Pass 1 collects
nested defuns in direct compiler invocations). The interpreter does NOT flatten: it evaluates
`eval-when` natively as `progn`. A malformed `(eval-when)` without a situation list is left
unspliced so the expander's validation error surfaces.

**Known gap:** `LoadInliner` runs BEFORE `UserMacroExpander`, so a top-level
`(load ...)`/`(require ...)`/`(asdf:load-system ...)` wrapped in `eval-when` is NOT inlined on the
compile path. Unwrapped directives are unaffected.

## Wiring points
`LispNames` constants; `LispEvaluator.evalCons` cases; `Jvm/WasmExprCompiler` compileCons cases;
`NoGcWasmCompiler.expandMacro`; `FreeVarAnalyzer` BOTH methods (explicit cases that expand first --
the default walk would misread the type symbol in `(the integer x)` as a free variable reference and
collect declaration specifiers); `expandBuiltinMacro`.

## Tests
- `LispEvaluatorTest`: `evalDeclareIsANoOp`, `evalDeclaimAndProclaimAreNoOps`,
  `evalTheReturnsTheValue`, `evalEvalWhen{ActsAsProgn,WrappedDefmacro}`, `evalCheckType`,
  `evalCheckTypeSatisfiesAndMember`, `evalAssert`, `evalTypecaseCompoundSpecifiers`,
  `evalComputedCompound{Type,Subtypep}Specifiers`,
  `evalTypeOfAndTypepAnswerTheCompoundArraySpecifier`, `evalVectorpChecksTheRank`,
  `evalRuntimeTypepResolvesADeftypeAlias`, `evalSimpleTypeName{SubtypepLattice,TypepChecksSimplicity}`.
- `JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` carry a twin of each, plus
  `compileAndRunDeclarations`/`The`/`EvalWhen`/`CheckType`/`Assert`,
  `compileAndRunEvalWhenWrappedDefmacroThroughUserMacroExpander`,
  `declaredArrayTypesEmitSingleArmAccessorsWithoutChangingResults`; `ChipzE2eTest`.
- ci-spec: `declarations-eval-when-check-type-assert`, `declared-array-types-single-arm-access`,
  `declared-float-scalars-answer-what-undeclared-code-answers`, `computed-compound-type-specifier`,
  `array-type-of-and-compound-array-specifier`, `vectorp-and-the-vector-specifier-check-the-rank`,
  `runtime-typep-deftype-alias`, `computed-compound-subtypep-specifier`,
  `simple-type-name-subtypep-lattice`, `simple-type-name-typep-simplicity`,
  `postmodern-language-incidentals`.
