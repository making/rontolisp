# Declarations: no-op semantics, declaration-driven array emission, eval-when, check-type/assert

Declarations are NOT pure no-ops: on wasm-GC a `(declare (type ...))` (or a `defstruct` slot
`:type`) drives single-arm array-access EMISSION; on the JVM a `(declare (type double-float ...))`
routes arithmetic onto the unboxed IEEE path
([jvm-double-arithmetic.md](jvm-double-arithmetic.md)). A `special` declaration is semantics on
every backend but `--no-gc`, scoped as CLHS 3.3.4 scopes it
([dynamic-special-variables.md](dynamic-special-variables.md)); the interpreter and `--no-gc`
ignore every other declaration.

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
  deviates from CL); `the` -> its value form; `eval-when` -> `progn` of the body for `:execute`,
  `nil` otherwise ("An eval-when's position" below).
- `check-type` -> `(let ((__check-type place)) (if <type-test> nil (error 'simple-type-error :datum
  __check-type :expected-type 'spec :format-control "...~s..." :format-arguments (list
  __check-type))))` (2026-10-30; was a plain `simple-error`, so `(type-error (c) ...)` missed it),
  place and spec printed at EXPANSION time, only the value a runtime `~s` argument; an optional
  third string replaces the "of type ..." part. The expansion runs inside the compilers, AFTER the
  whole-program gates, so the gates name `check-type` themselves: `constructsInstance` (instance
  + condition-report gates), `ConditionTagScan` walks the expansion (report-arm narrowing + the
  runtime format renderer -- without it a JVM uncaught message printed the raw `~s`), and wasm's
  `usedLayoutTags` keeps the `SIMPLE-TYPE-ERROR` layout. **Its test is the literal `typep`'s**
  (`expandCheckType(cons, registry)`, 2026-11-05; it used to take no registry and pass any class,
  condition, struct or deftype): the compilers hand `ctx.closRegistry`, the interpreter's live one,
  so that arm re-expands ([interpreter-expansion-memo.md](interpreter-expansion-memo.md)). The
  no-registry overload is for `FreeVarAnalyzer`, `ConditionTagScan`, `macroexpand-1` and `--no-gc`
  (no classes there), none of which runs a class test. A false refusal is the risk, so:
  `checkableTypeSpec` widens, in a positive position, what `typep` answers empty here on purpose
  (`generic-function` -> `(or function ...)`, `structure-class` -> `class`,
  `logical-pathname` -> `pathname`, `extended-char` -> `character`); a specifier nothing defines
  still passes, as does `restart` (a restart is a cons here; `typep` answers nil), and SBCL's
  run-time error is not copied because a class can be defined at run time on a compile path. The
  deciding measurement is a matrix of 140 specifiers against 55 values, literal `typep`, computed
  `typep` and `check-type`, on all four against SBCL 2.2.9 (fixes below). What differs from SBCL
  afterwards is deliberate: the binary32 float subset, the string name collapse, `standard-class` on a struct's
  class, `standard-object` on a condition, the `t` stream designator, a package being a keyword.
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
array of the same element type), `FLOAT`, `GENERAL`. A string specifier (`simple-string`
included) and a character element type map to NOTHING: a string is the immutable `TYPE_STRING` or
the marked general character array `copy-seq`/`subseq`/`make-string` answer, so a `STRING` kind
casting to `TYPE_STRING` trapped on a correct declaration (plump's `decode-entities`, removed
2026-10-31). A bare `vector`/`array` proves nothing. Specifier symbols resolve through the
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
arm keeping the raw-value fast path (`tryCompileRaw`); `length` of a packed kind is `ref.cast` + `array.len` + `ref.i31`, other kinds
keeping the generic chain because a GENERAL vector's length must honour a fill pointer.

- `(setf (aref var i) v)` on a VARIABLE place of a pinned kind skips the expansion's
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
  `generic-function`/`standard-generic-function` (a defgeneric dispatcher is a plain function value
  with no marker), `structure-class` (a defstruct class metaobject IS a standard-class) --
  routing trivia level2 onto its portable fallback; `built-in-class` is a seeded class
  ([clos.md](clos.md), "MOP boundary").
  `bit-vector`/`simple-bit-vector` used to be empty (no bit-vector value existed); since
  `.todo/043` they test the remembered element type `bit` behind the `%arrayp` guard, rank 1
  pinned, the `simple-` spelling ANDing `%simple-array-p` like every other `simple-` spelling.
  `CLASS`/`STRUCTURE`/`TYPE` joined CL_TYPES for a different reason: trivia NAMES its patterns with
  them, and the defpattern site and a user's pattern site must resolve to the same bare spelling.
- **Atomic map** (`atomicTypePredicate`): integer/fixnum/bignum -> `integerp`, float/double-float/
  long-float -> `floatp`, single-float/short-float -> `%single-float-p` (the next section),
  number/real -> `numberp`, rational -> `rationalp`, ratio -> `(and (rationalp v) (not (integerp v)))`
  (it was `rationalp` until 2026-10-31: jonathan's `(number)`/`(ratio)` methods sent `3` to the
  ratio one and printed `3.0`; ci-spec `library-shapes-own-while-...`), plus string, symbol, keyword, cons, list,
  null, atom, character, hash-table, function -> `functionp`; `boolean` -> `(or (null v) (eq v t))`;
  `unsigned-byte` -> `(and (integerp v) (>= v 0))`; `array` -> `(or (stringp v) (%arrayp v))` (rank
  NOT checked -- that separates it from `vector`, whose atomic arm goes through
  `makeArrayTypeTest`); `sequence` adds `listp`; `t`/`otherwise` -> t; literal `nil` -> nil. The
  three `simple-` spellings add `%simple-array-p`. `compiled-function` -> `functionp` (`compiled-function-p`
  too; SBCL agrees), `standard-char` spells `standard-char-p`'s set with `char-code`, because a type
  test splices no prelude defun, and `sequence` is a list or a rank-1 vector, no longer a rank-2 array.
  **`fixnum`/`bignum` stay `integerp` on purpose:** `most-positive-fixnum` is 2^30-1 on wasm, so a real
  test would refuse `(check-type x fixnum)` over 2^30 there and take every library's `fixnum` arm off the
  values SBCL keeps on it. `functionp` is a real public builtin (JVM =
  `Object[]` with an Integer funcId in slot 0, WASM = `ref.test TYPE_CLOSURE`); `%arrayp` is
  CL_INTERNALS-only.
- `logical-pathname`/`extended-char` are empty too (no such value), and `random-state` is the
  `%RANDOM-STATE` tag ([random.md](random.md)).
- **Compound**: `or`/`and`/`not` recurse; `(mod n)` -> `(integer 0 (n))`; `(member items...)` -> `(member v '(items))`;
  `(eql obj)` -> `(eql v 'obj)`; `(satisfies fn)` -> `(fn v)`;
  `(integer|float|rational|real|number [low [high]])` -> base predicate + bound checks, `*`
  unbounded, `(n)` exclusive.
- **Type-specifier symbols match by package-STRIPPED name** (`plainTypeName`): standard type names
  are not all registered CL symbols, so in a user package the resolver would qualify e.g.
  `unsigned-byte`. `CL_TYPES` registers the common type-only names so they resolve BARE -- required
  where the name reaches RUNTIME data (symbols compare by name).
- **The float names are two types**: `float`/`double-float`/`long-float` one, `single-float`/
  `short-float` the binary32 subset below it (next section). A collapse claims two names denote the
  SAME SET, so it answers `T` in both directions; an edge answers it one way. `subtypep` returns ONE
  value here; `deftype` is a parsed no-op in this path.
- **`bfloat16` is an EDGE below `SINGLE-FLOAT` (`SUBTYPEP_PARENTS`), never an alias**, because its
  set is EMPTY -- no scalar has the type, the width lives in array storage (`.kb/bfloat16.md`) --
  so the symmetric claim is false. It was an alias from 2026-09-03 to 2026-09-08 and that cost
  twice: `(subtypep 'single-float 'bfloat16)` answered `T` against `(typep 1.0 'bfloat16)`'s `NIL`,
  and a COMPUTED pair on the compile paths answered `NIL` against everything, itself included,
  because `subtypepUniverse` derives the edge names from the table and lists the alias names BY
  HAND. **An edge is in the runtime universe by construction; an alias is only as present as that
  list.** Pins: `LispFloatArrayTest`, `JvmBFloat16ArrayTest`, ci-spec `bfloat16-type-lattice`.

## single-float is the floats a binary32 holds
**Every float is one binary64. `float`/`double-float`/`long-float` are that type; `single-float`/
`short-float` are the SUBSET a binary32 holds exactly (a NaN included), on all four backends**
(2026-11-07). One test, `%single-float-p` (`Binary32.holds`; JVM `Double.compare` over `d2f`/`f2d`;
wasm `f32.demote_f64`/`f64.promote_f32` plus `f64.ne` for a NaN, since a demotion may invent one),
behind `atomicTypePredicate`, so literal and computed `typep`, `typecase`, `check-type` and a method's
specializer all take it. Lattice: `SINGLE-FLOAT` an edge below `FLOAT`, `BFLOAT16` below it,
`SHORT-FLOAT` collapsed onto it, `DOUBLE-FLOAT`/`LONG-FLOAT` onto `FLOAT`; a single-float method ranks
217, ahead of double-float's 218. `coerce` to it (literal, computed, a complex part) and an `f`/`s`
exponent marker (the lexer and both runtime readers) answer `%single-float`, the binary32 nearest the
double the value converts to. Unchanged: arithmetic (binary64), printing (`0.1f0` prints
`0.10000000149011612`), `float-digits` 53, `type-of`/`class-of` `FLOAT`, `float`'s prototype (`1.0f0`
and `1.0d0` are one value), array storage, `*read-default-float-format*` (still read by nothing).

- **Why.** Under the collapse every float met a library's single arm first: cl-conspack's
  `number-type` wrote each double as float32 (`(conspack:decode (conspack:encode 0.1d0))` answered
  `0.10000000149011612`), cl-postgres's `param-to-oid` and `serialize-for-postgres` sent float4. Now
  `0.1d0` round-trips (interpreter, JVM, P1, measured), and typo's primitive list keeps SBCL's two
  float entries (its other assumption, the fixnum width, is "fixnum is SBCL's (signed-byte 63)"
  below).
- **Consumers in the Quicklisp cache** (279 releases, 54 naming `single-float` outside tests). A
  single arm ahead of the double one: cl-conspack, cl-store (lossless: its restore reads the 53-bit
  significand), cl-postgres, coalton (its float literal is typed by spelling, `CoaltonForms`: a `d`
  marker F64, any other float F32), petalisp's C++ literal type, lisp-unit's default epsilon,
  mt19937's `random` (23 random bits for a float a binary32 holds), mcclim, float-features (shimmed).
  Probes: cl-json and postmodern's json-encoder (short = single, long = double now; output unchanged,
  `~F` reads no format). Unaffected: jzon (double arm first), numpy-file-format (packed array types),
  ieee-floats (encodes through `decode-float`, decodes over a `1.0f0` prototype, exact). Not in the
  cache: cl-messagepack, numcl, magicl; static-vectors names no float type.
- **Why not a real binary32 value (counted 2026-11-06).** Every numeric operation on four backends
  would carry a second float: the interpreter's `LispDouble` has 577 references in 101 files, the JVM
  boxes `Double` (169 `doubleClass` uses in 16 emitters, 46 `instanceof` and 8 `checkcast` sites, 75
  template sites), wasm's `TYPE_FLOAT` 158 references in 35 files -- arithmetic with contagion,
  comparison, `eql` and hashing, printing with a marker, the readers, `format`'s float directives, the
  float functions, `coerce`, complex parts, `aref` of `#f`. And it would buy a library little here:
  with `*read-default-float-format*` `double-float`, which every program and page assumes, `1.0` stays
  a double, so SBCL code reaches the arms it reaches now, and `(float 1/3)`, `(sqrt 2)` would have to
  stay double against CLHS 12.1.3.3; CLHS's initial `single-float` changes the precision and the
  printed text of every float program (`(+ 0.1 0.2)` prints `0.3`).
- **Deviation.** CLHS's float arrangements are one format or disjoint ones; a subset is neither.
  Arithmetic stays binary64, so `(typep (* 0.1f0 3) 'single-float)` is nil where SBCL answers t, and
  `(typep 0.1 'single-float)` nil where SBCL reads `0.1` as a single. A rational converts through the
  double, so a token of 17+ digits or a ratio within half a binary64 ulp of a binary32 midpoint
  rounds twice. **Trigger:** a consumer needing binary32 ARITHMETIC (a result rounded per operation),
  or a format that a value's history decides, is the item a real binary32 value needs.
- Pins: ci-spec `single-float-is-the-floats-a-binary32-holds` (all four), `postmodern-language-incidentals`,
  `bfloat16-type-lattice`; `LispEvaluatorTest#evalExponentFloatLiteral`, `LispFloatArrayTest`.

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
- **The element type is compared UPGRADED** (`upgradedArrayElementType`): the two float widths,
  the three packed `(unsigned-byte 8|16|32)` widths and `bit` keep their name, the character
  family answers `character`, everything else -- `fixnum`, `integer`, a class -- lands in the
  general boxed array with element type `t`. So `(typep a '(simple-array fixnum (4)))` is a
  `t`-array test: conformant, and the one shape array-operations' suite still fails on. (`bit`
  keeps its name since `.todo/043`: a bit vector IS the general boxed array stamped `bit`, so
  the stamp is what the comparison reads.) A deftype ALIAS resolves first
  (`resolveElementTypeAlias`).
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
  `RUNTIME_TYPEP_BUILTINS`, as are `signed-byte`, `base-char`, `standard-char`, `base-string`,
  `simple-base-string` and `compiled-function`, each of which answered nil when computed until
  2026-11-05 (so did `readtable` on the compile paths, now in the tag table with `random-state`).
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
  answers `nil` on the compile paths; the interpreter resolves it. An alias as the ELEMENT type of a
  computed compound (`(vector my-char)`) is compared unresolved on all four; the literal resolves it.
  The `make-array :element-type` half is [array-literals.md](array-literals.md), a DIFFERENT narrowing.

## A PARAMETERIZED `deftype` as a compound specifier
**`(name args...)` is what the deftype's body returns over those arguments, on all four
backends, literal or computed** (2026-10-30, trivial-types' `(proper-list integer)` /
`(tuple ...)`; before, a literal answered NIL and a `typecase` head was refused).
`makeCompoundTypeTest`'s default arm asks `ClosRegistry.expandDeftypeSpecifier`; a bare
`(name)` of a registered deftype is the name.

- Interpreter: `LispEvaluator.foldDeftype` keeps every parameterized form and installs
  `expandDeftypeSpecifier` (runs `(apply (lambda ll' . body) 'args)`, an unsupplied
  `&optional`/`&key` defaulting to `*`) as the registry's expander. The run-time dispatch
  calls the builtin `%deftype-expand` before the alias cond.
- Compile paths: no evaluator, so `UserMacroExpander.emitDeftypeInstances` walks every
  RESOLVED top-level form (deftypes excluded: a parameterized body is a template) plus every
  registered expansion, expands each compound use, and appends
  `(declaim (%deftype-instance 'spec 'expansion))` -- a declamation because every pass reads
  one as inert data. Appended at the END, so bare symbols are spelled `cl-user::`
  (`contextFreeSpelling`): a bare canonical name means `cl-user`'s view from any package.
  `expandTopLevelDefinitions` registers it (`registerDeftypeInstance`, keyed by `print()`).
- Run time on the compile paths: the instances whose head the program spells join
  `%deftype-alias-table%` as `((spec) expansion)` entries, chains followed at build time
  (`followDeftypeHops`); the scan's `member` takes `:test #'equal` only then. A spec
  assembled at run time from NEW arguments resolves on the interpreter only. Pins:
  `LispEvaluatorTest#evalRuntimeTypepResolvesADeftypeAlias`, ci-spec
  `typep-parameterized-deftype-compound`.

## The COMPOUND half of `subtypep`
**A compound specifier works on either side of `subtypep`, quoted or computed, and answers the same
on all four backends.** Written twice -- `LispMacroExpander.subtypep` over the AST (the
interpreter's builtin AND the literal fold) and `RUNTIME_COMPOUND_SUBTYPEP_SOURCE` over a runtime
specifier VALUE. Twins, not one source: the static side must stay Java because the emitted ancestor
table is GENERATED from it. **Change them together.**

- A type is a subtype of itself, compound included (`equal` on the specifiers). `(or ...)`: any
  branch as the super, EVERY branch as the sub; `(and ...)`: every conjunct as the super, ANY
  conjunct as the sub. The `(or ...)` sub is split FIRST, being exact: tried after an `(or ...)` super
  asked for one branch holding all of it, `(or float rational)` against `(or rational float)` was nil.
- **A numeric name the branches of an `(or ...)` super cover between them** is a subtype
  (`NUMBER_PARTITIONS`: `rational` = `integer` + `ratio`, CLHS 12.1.1; `real` = `rational` + `float`,
  `number` = `real` + `complex`, SBCL's answers too). Both 2026-11-07, both twins: serapeum's
  `dispatch-case` asks `same-type?` of its clause types against `real*` = `(or rational float)`, and
  each compile of `range.lisp` warned `Can't check exhaustiveness` 36 times. Pin: ci-spec
  `computed-compound-subtypep-specifier`.
- **Any other head as the SUB reduces to that head and re-tests** (`(integer 0 10)` <= `integer`);
  the same reduction on the SUPER would be unsound, so `(subtypep 'integer '(integer 0 10))` stays
  nil (SBCL agrees). `(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)` stay unknown
  (`OPAQUE_COMPOUND_TYPE_HEADS`) but for two enumerations, below, which the VALID-P below reports as such.
- **Integer INTERVALS decide by containment** (`.todo/919`): `(unsigned-byte n)`, `(signed-byte n)`,
  `(integer lo hi)` (exclusive `(k)` bounds, `*`), `(mod n)` and the names `bit` / `fixnum` (SBCL's
  `(signed-byte 63)` on all four, "fixnum is SBCL's (signed-byte 63)" below) / `unsigned-byte` /
  `signed-byte` / `integer`, and an `(and ...)` of
  those, each read as one interval (`macro/IntegerTypeRange`), and with a
  COMPOUND on at least one side two intervals answer containment -- exact, so a nil there is still
  `nil nil` only because the valid-p rule below says compound. An `(or ...)` SUB is covered by its
  branches' hull (sound; the super is read exactly). Runtime twin: `%subtypep-interval` /
  `-covering` / `-in` (`RUNTIME_SUBTYPEP_INTERVAL_SOURCE`), an arm ahead of the compound template,
  injected only when the program spells an interval type (`mentionsIntegerIntervalType`), so a
  computed-`subtypep` program without one keeps its bytes. Two plain NAMES stay the lattice's on
  purpose: `bit <= unsigned-byte <= signed-byte = integer` is true and missing there, but adding the
  edges regenerates the ancestor table of every computed-`subtypep` program. ANSI
  `types-and-classes` 2026-09-22: +18 (`SUBTYPEP.INTEGER.*`), 0 regressed. Pins
  `LispEvaluatorTest#subtypepDecidesIntegerIntervals` + the JVM / WASM
  `compileSubtypepIntegerIntervals` twins.
- **A deftype name against a deftype name** tries the super's expansion alone first (`namedSubtypep`): a
  branch of it may name the sub (serapeum's `key-designator` holds `function-name`), which expanding both
  loses. The VALID-P reads through deftypes: a name standing for a compound is not a plain name.
- **Array specifiers decide by their parts** (2026-11-05, serapeum's `with-type-dispatch` and
  `vector-dispatch`): with a compound on one side, two of `array`, `simple-array`, `vector`,
  `simple-vector`, `string`, `simple-string`, `base-string`, `simple-base-string`, `bit-vector`,
  `simple-bit-vector` and their compounds compare upgraded element type, dimensions and simplicity
  (`macro/ArrayTypeShape`), exact, so a nil is a decision; against another name one is the vector or
  array it is. **Deviation**: a string IS `(array character (*))` here, one character type, where SBCL
  has base strings besides. Two `(member ...)`/`(eql x)` compare elements. A deftype name against
  a compound is its expansion against it. Runtime twin: `RUNTIME_SUBTYPEP_ARRAY_SOURCE`
  (`%subtypep-parts-rule`, `%subtypep-deftypes%` without the circular names), ahead of the compound
  template in every computed-`subtypep` program. An element a deftype names reads as `t` there.
  Pin: ci-spec `subtypep-reads-array-specifiers`.
- **A CIRCULAR `deftype` is an unknown type name, never an endless expansion** (2026-09-24,
  `.todo/954`). `subtypep` threads the deftype names being expanded on the current path and does
  not expand one again, so postmodern's `(deftype tsvector () 'tsvector)` answers nil against
  everything but itself, and a loop through a compound (`a` -> `(or b integer)`, `b` ->
  `(or a string)`) still finds the `integer` branch. SBCL hangs on the same query. Load-bearing
  because `subtypepAncestorTableForms` asks about EVERY registered deftype name: one circular
  name overflowed the stack of every JVM/WASM compile of a program with a computed `subtypep`
  (all of postmodern). `typep` over such a name still recurses (undefined in CL; nothing loads
  one into a `typep`). Pins `LispEvaluatorTest#evalSubtypepOverCircularDeftypes`, ci-spec
  `runtime-subtypep-beside-circular-deftypes`.
- **A bare lattice name is that type** (2026-11-05): `SubtypepName.classInfo` takes only an exact class for a
  `SUBTYPEP_PARENTS` key, since `findClass`'s tolerance for a bare spelling answered `coalton:bit`, a class, for
  `cl:bit`, so `(subtypep 'bit 'integer)` was nil and coalton's `Bit` conversions asked for `Bounded Integer`.
  `bit` is below `fixnum` in the lattice, and a deftype name joins the run-time table in its one-colon spelling
  too (`(subtypep x 'p:num)` answered nil compiled). ci-spec `subtypep-fixnum-is-a-63-bit-interval`.
- **fixnum is SBCL's (signed-byte 63)** on all four (2026-11-07; 64 bits from 2026-11-05, when coalton's load
  asked `(subtypep '(signed-byte 32) 'fixnum)`): `IntegerTypeRange`, its run-time twin `%subtypep-interval`,
  and `most-positive-fixnum` `2^62-1` on the interpreter and the JVM (`ClConstants`; wasm keeps its i31
  bound). **The width is ours to change, not typo's to absorb:** `typep` of `fixnum` is `integerp` and an
  integer is a long up to 2^63 whatever the constant says, so the type is a convention, not a
  representation -- on wasm it was never the representation -- and the convention libraries were written
  against is a tagged fixnum narrower than a word. typo's primitive list puts `(unsigned-byte 63)` after
  `fixnum` and asserts no later entry is a subtype of an earlier one, so it needs 33..63 bits (coalton
  needs at least 32); every implementation typo supports has that. `sxhash` masks by the constant, so it
  stays a fixnum. Pin: ci-spec `subtypep-fixnum-is-a-63-bit-interval` (SBCL's answers). typo's load
  then passed `vm/predicates.lisp` once a `shared-initialize` method's initargs reached the slots
  ([clos.md](clos.md), "The instance-initialization protocol") and stops in
  `vm/data-and-control-flow.lisp` on `(fdefinition 'if)`, which loads since 2026-11-07
  ([symbol-runtime-api.md](symbol-runtime-api.md), "`symbol-function` of an operator").
- **The stream subtypes are edges to `STREAM`** (`file-stream` ... `concatenated-stream`), and a
  Gray class (ancestor `rontolisp:fundamental-stream`) is below `STREAM` in `namedSubtypep`. The
  edges join the runtime universe only for the names `runtimeStreamTypeNames` keeps, so a
  computed-`subtypep` program meeting none keeps its table (`.kb/gray-streams.md`).
- **Trap:** a lattice LEAF with no `SUBTYPEP_PARENTS` entry (`hash-table`, `function`, `package`,
  `stream`, `atom`) had no ancestor-table row, so a runtime `(subtypep 'hash-table 'hash-table)`
  answered nil on the compile paths and `T` on the interpreter. `subtypepUniverse` now adds every
  `RUNTIME_TYPEP_BUILTINS` name except `T` (not a symbol at run time; the generated `(eq b t)` edge
  answers it). No COMPUTED `subtypep` -> byte-identical.
- **The ancestor table resolves each name ONCE** (2026-09-25). `subtypepAncestorTableForms` asks
  about every PAIR of the universe (U^2), and each pair used to re-resolve both names: a miss of
  `ClosRegistry.findClass` (most universe spellings) scanned every class with a `splitQualified`
  each. mito's probe: U = 1,218 (649 class spellings, 245 struct, 111 deftype, 293 classes), ~40 s
  per table, built once per `JvmLispCompiler` retry attempt (5 there) -- 90% of a 253 s compile.
  Now the name arm of `subtypep` is `namedSubtypep` over two `SubtypepName`s (per-name lookups,
  kept on first use); the table makes one per universe name, `subtypep` two per call, so both
  answer through the one body. `ClosRegistry` indexes the member-name fallbacks
  (`classesByMember` / `aliasesByMember`, dropped by each registration) and keeps
  `descendantStructTags` answers (cleared by each `registerStruct`; the generic-dispatch builders
  had made it 15% of the remaining compile). Table 40 s -> 0.3-0.45 s; probe JVM compile 253 s ->
  51 s, component (`--component --optimize`) 77 s -> 32 s; outputs byte-identical there and on
  all 231 `examples/` programs (`.class` and `.wasm`). What is left of the 51 s is the five
  attempts themselves. Pins `LispMacroExpanderTest#theRuntimeSubtypepTableAnswersEveryPairAsSubtypepDoes`
  (every pair against `subtypep`) and `ClosRegistryTest` (the kept lookups follow registration).

## `subtypep` answers CL's VALID-P as its second value
**Invariant: a nil primary claims a DECISION only between two plain type NAMES. Every compound rule
above is sound but NOT complete, so a nil over a compound is `nil nil`.** `subtypepValid` is the one
decision (`LispMacroExpander`), `RUNTIME_SUBTYPEP_VALID_SOURCE` its runtime twin for computed
specifiers; a `t` primary is always a decision, since `subtypep` answers `t` only on a proof.

- The value crosses the call boundary through the ordinary multiple-value tier: `SUBTYPEP` is an
  `isMvProducerForm` case whose second value is `(%subtypep-valid sub super)`
  (`LispNames.SUBTYPEP_VALID`, `CL_INTERNALS`), folded to a constant for a literal pair and routed
  to the injected `%subtypep-valid-runtime` defun otherwise -- the same split `expandSubtypep`
  makes, and the same shape `find-symbol`'s `%find-symbol-status` uses ([[multiple-values]]). A
  LITERAL specifier is passed through the lowering rather than bound to a temp, or the fold would
  not see it.
- The injection gate is `needsRuntimeSubtypep(program) && usesMvOperator`: a program with no
  multiple-value operator, or no computed `subtypep`, emits the bytes it emitted before.
- **This rule was MEASURED, not reasoned.** A first cut claimed a decision for everything outside
  the opaque heads: on the ANSI suite (2026-09-12) that was **+486 tests and -82**, the 82 being
  `SUBTYPEP.CONS.*` / `.REAL.*` / `.RATIONAL.*` / `.OR.*`, where `check-equivalence` accepts a nil
  valid-p and FAILS a false claim -- `(and (cons symbol *) (cons * symbol))` really is
  `(cons symbol symbol)`. Narrowing the claim to name-vs-name gave **+443 and 0 regressions**
  (66.3% -> 68.6%). Under-claiming is conforming; over-claiming is a wrong answer.
- A nil between two integer INTERVALS is a decision too (2026-11-05): containment is exact, and coalton's
  `definitely-subtype?` signals on a nil valid-p. A runtime twin of it comes with the interval arm.
- Deviation: an unknown type NAME answers `nil t` (the name universe is closed here) where CL may
  answer `nil nil`.
- Pins: `LispEvaluatorTest.subtypepAnswersCommonLispValidP`,
  `JvmLispCompilerTest.compileSubtypepValidP`,
  `WasmLispCompilerIntegrationTest.compileSubtypepValidP`; the component backend was verified by
  hand ([[running-backends]]).
## The `simple-` names are lattice EDGES, not aliases
**`simple-vector`, `simple-array` and `simple-string` name strictly smaller types than
`vector`/`array`/`string`, so `subtypep` answers `T` one way and `NIL` the other, on all four
backends.** `LispMacroExpander.SUBTYPEP_PARENTS`: `simple-string` -> `simple-array`, `string`;
`simple-vector` -> `simple-array`, `vector`; `simple-array` -> `array`; and since `.todo/043`
`bit-vector` -> `vector` and `simple-bit-vector` -> `bit-vector`, `simple-array` (a bit vector
is a vector of bits, and a simple one is both). So `simple-vector` <=
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
nested defuns in direct compiler invocations). A malformed `eval-when`, without a situation list, is
left unspliced so the expander's validation error surfaces.

**Known gap:** `LoadInliner` runs BEFORE `UserMacroExpander`, so a top-level
`(load ...)`/`(require ...)`/`(asdf:load-system ...)` wrapped in `eval-when` is NOT inlined on the
compile path. Unwrapped directives are unaffected.

## An eval-when's position (CLHS 3.2.3.1)
2026-11-05, all four against SBCL 2.2.9 (ci-spec `eval-when-at-no-top-level-asks-for-execute`,
`eval-when-at-top-level-stays-there`, which fail before this).

- **At no top level** only `:execute` or `eval` runs the body, the form answering nil otherwise:
  `expandEvalWhen` (the expression compilers, `FreeVarAnalyzer`, the interpreter's arm, `--dump-ir`'s
  lowering), `%eval-row-eval-when` under `_eval`. Nothing is on the channel past one that does not run
  (`settleTail`). `SpecialVarCollector.collectBoundForm` walks the body regardless: it does not know
  the position, and a needless binding costs nothing.
- **At top level** any situation runs it, an empty list none (`evalWhenMembers`): a program stands for
  both its compile and its load, so all four print a `(eval-when (:compile-toplevel) (print 1))` a
  source spelled, and macro time runs it as well. What a macro expanded to is read as `compile-file`
  reads it where it IS an `eval-when` ([defmacro-backquote.md](defmacro-backquote.md)).
- **What keeps its body at top level:** `progn`, `locally`, `eval-when`, `macrolet`, `symbol-macrolet`,
  a macro's expansion. Past those, what `flattenTopLevel` opens: `uiop:with-upgradability`,
  `with-deprecation`, and a `handler-bind` binding nothing (SBCL's expands to its body too; its
  `with-compilation-unit`, `handler-case`, `prog1`, `when`, `cond`, `let`, `flet`, `block`, `the` do not,
  measured). `uiop:nest` does not here.
- **Compile paths:** nothing at top level reaches `expandEvalWhen`. `flattenTopLevel` opens what the
  source spelled. `UserMacroExpander` walks a top-level `symbol-macrolet`'s members as top-level forms
  and substitutes there, leaving a `progn` (such a form activates the pass), and splices a `progn` a macro
  or that substitution left around an `eval-when`, compile-time members evaluated (before: `The function
  X is undefined`, for a macro calling what such a member defined). `processExpandedEvalWhen` opens a
  `progn` member, so no program leaves the pass with a top-level `eval-when` or `symbol-macrolet`:
  `--dump-ir` relies on it. The playground and a direct compiler call never enter the pass, so the backends
  flatten with `flattenCompiledTopLevel`, which substitutes a top-level `symbol-macrolet` itself.
- **Interpreter:** the entries running a top-level form end in `evalAtTopLevel` (`evalTopLevel`,
  so `eval(LispVal)`, `loadForms` and `evalSource`; `evalResolved`, so `evalValues` and the macro-time
  evaluator). It opens `progn`, `locally`, `eval-when` and the wrappers and marks any other form
  (`topLevelForm`, per thread, by identity); only the arms that make a top-level form of one ask the mark,
  the macro call, `macrolet` and `symbol-macrolet` (`evalMacroletForm`'s `topLevel`), so `evalCons`'s hot
  `progn` asks nothing. A `#.` datum (`evalReadTimeDatum`), `eval` and `compile` (`evalDatum`) and
  `eval(LispVal, Environment)` are at no top level.
- **Libraries sized** (a walk over `src/`, `examples/` and the 229 releases in the quicklisp cache): of
  1185 `eval-when` forms 95 name no `:execute` at a syntactic top level and 20 inside another form, every
  one of those a macro's body or template that the library uses at top level, or a test. Each of the 383
  non-test systems there loaded on the interpreter and compiled to a class, against the build before, with
  `expandEvalWhen` logging what it dropped: nothing, in 766 legs, of which 625 load before and after (one
  more after, a 200 s timeout before) with the same output.
- Lite: a macro call in a body that does not run still expands on a compile path, since the walk does
  not know the interpreter's position. A compiled run-time `load` goes through `_eval`, so only `:execute`
  runs there (a form the interpreter's `load` runs for every situation).

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
- ci-spec: `declarations-eval-when-check-type-assert`, `check-type-tests-what-typep-tests`,
  `standard-type-names-answer-literal-and-computed`, `declared-array-types-single-arm-access`,
  `declared-float-scalars-answer-what-undeclared-code-answers`, `computed-compound-type-specifier`,
  `array-type-of-and-compound-array-specifier`, `vectorp-and-the-vector-specifier-check-the-rank`,
  `runtime-typep-deftype-alias`, `computed-compound-subtypep-specifier`,
  `simple-type-name-subtypep-lattice`, `simple-type-name-typep-simplicity`,
  `postmodern-language-incidentals`.
