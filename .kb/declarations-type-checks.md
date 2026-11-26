# Declarations: no-op semantics, declaration-driven array emission, eval-when, check-type/assert

Declarations are NOT pure no-ops: on wasm-GC a `(declare (type ...))` (or a `defstruct` slot
`:type`) drives single-arm array-access EMISSION, and an integer range (or a `the` of one) a raw
value with no fallback ("Declared integer ranges" below); on the JVM a `(declare (type double-float ...))`
routes arithmetic onto the unboxed IEEE path
([jvm-double-arithmetic.md](jvm-double-arithmetic.md)) and an integer range makes a fused leaf a
cast with no bail ("Declared integer ranges", "On the JVM"). A `special` declaration is semantics on
every backend but `--no-gc`, scoped as CLHS 3.3.4 scopes it
([dynamic-special-variables.md](dynamic-special-variables.md)); the interpreter and `--no-gc`
ignore every other declaration.

## The false-declaration policy (all backends)
**A declaration may change EMISSION, never the RESULT of a correctly-declared program -- and where
a backend TRUSTS a declaration, a FALSE one becomes a DETERMINISTIC error at the site the trusted
representation meets the contradicting value, never a silently coerced or wrong value.**

- wasm-GC: an uncatchable `ref.cast` TRAP at the access (a checked signal would cost the dispatch
  bytes the feature removes); for an integer range the cast or an `unreachable` at an unbox or a
  store. JVM: a `checkcast java/lang/Double` (`java/lang/Long` for an integer range) failure,
  surfaced as a CATCHABLE Lisp error -- catchability is a side effect, not a promise; one
  softening, an integer LITERAL assigned to a declared float variable widens at compile time.
  Interpreter and `--no-gc` are the oracle for CORRECTLY-declared programs only.
- A falsely-declared program DIVERGES across backends (CL: undefined behavior), documented in
  `doc/*/reference/macros/declare.md`. Coercing through `_dbl` was rejected: silently WRONG data,
  still divergent.

## What ships
Seven `LispMacroExpander` expansions (no per-backend codegen), in `PackageRegistry.CL_MACROS` with
`expandBuiltinMacro` cases so `macroexpand-1` works:

- `declare`/`declaim`/`proclaim` -> `nil`, arguments never evaluated or validated (`proclaim`
  deviates from CL); `the` -> its value form. A wasm classifier reading a form before expansion
  must read through `the` (`WasmIntFusionCompiler.withoutThe`), or a declared integer loop loses
  the bare one's code (5x slower measured); the JVM's need not (same time either way).
  `eval-when` -> "An eval-when's position" below.
- `check-type` -> `(error 'simple-type-error :datum .. :expected-type 'spec ...)`, so `(type-error
  (c) ...)` catches it; place and spec printed at EXPANSION time. **The report is rendered at the
  signal** ([error-handling.md](error-handling.md), "The condition floor"): a run-time `~s` kept
  the format renderer in every program that could reach a `check-type` (115,034 -> 19,257 B for a
  `.class` reaching `integer-decode-float`). Outside EH mode on wasm-GC no report is built. The
  expansion runs AFTER the whole-program gates, so the gates name `check-type` themselves
  (`constructsInstance`, `constructsCondition`, `ConditionTagScan`, wasm's `usedLayoutTags`). Pin:
  `LispMacroExpanderTest#conditionNarrowingCollectsLiteralTagsAndDeclinesTheRenderer`.
- **Its test is the literal `typep`'s** (`expandCheckType(cons, registry)`, the compilers'
  `ctx.closRegistry` or the interpreter's live one, [interpreter-expansion-memo.md](interpreter-expansion-memo.md));
  the no-registry overload serves passes that run no class test. A false refusal is the risk, so
  `checkableTypeSpec` widens, in a positive position, what `typep` answers empty on purpose
  (`generic-function`, `structure-class`, `logical-pathname`, `extended-char`); an undefined
  specifier and `restart` (a cons here) still pass, and SBCL's run-time error is not copied because
  a class can be defined at run time on a compile path. Measured as 140 specifiers x 55 values on
  all four against SBCL 2.2.9. Deliberate differences from SBCL: the binary32 float subset, the
  string name collapse, `standard-class` on a struct's class, `standard-object` on a condition, the
  `t` stream designator, a package being a keyword.
- `assert` -> `(if test nil (error ...))`; the places list is dropped (no `continue`/`store-value`
  restart, though the restart system exists).

## Declaration-driven array emission (wasm-GC only)
A rank-1 `aref`/`(setf aref)`/`length` site whose array representation is pinned emits that ONE
accessor behind a trapping `ref.cast` instead of the inline 4-way dispatch chain (196 B per rank-1
`aref` site, 232-1540 B per `%aset` site; 21.1% of chipz `zlib`'s instruction bytes at
`--optimize=size`). In a closed module the type-test fold (`.kb/wasm-ref-type-fold.md`) already
prunes most arms, so a pin there buys speed and the reads nothing pinned before (13,217 -> 12,765 B
for a fully declared Java zone reader).

`compiler.DeclaredArrayTypes` maps a specifier to a `Kind`: `U8`/`U16`/`U32` (only with an
explicitly rank-1 dims spec: an unknown-rank spec could be a rank-n GENERAL array), `FLOAT`,
`GENERAL`. **A string specifier and a character element type map to NOTHING**: a string is the
immutable `TYPE_STRING` or a marked general character array (`copy-seq`/`subseq`/`make-string`), so
a cast to `TYPE_STRING` trapped on a correct declaration (plump). A bare `vector`/`array` proves
nothing; `(vector t)` and `(simple-array t (*))` prove `GENERAL`. Specifier symbols resolve through
the deftype table including an all-`&optional` deftype's DEFAULTED expansion
(`LispMacroExpander.defaultedDeftypeExpansion`; the unsupplied-optional default is `*`).

Four sources (`WasmArrayCompiler.arrayKindOfExpr`):
1. A declared lexical variable (`Ctx.declaredArrays`, scoped like `Ctx.locals`; body-head and
   `let` declarations, bound AND free -- a lambda list's generated `let*` prologue leaves parameter
   declarations free). Not specials, not at top level, not in async bodies.
2. A binding INITIALIZER this compile chose a representation for (`initExprPin`): a literal packed
   `make-array`, a `make-array` with NO `:element-type` (`GENERAL`, not with `:displaced-to`), a
   slot-typed accessor call, a kinded outer variable -- only while the body never reassigns the
   name. Without it `(setf (aref v i) x)` on a `make-array`-bound `v` asked `stringp` per store
   (11% of a Starlark `list(range(n))`).
3. A `defstruct` slot `:type` read through its accessor (`ClosRegistry.registerStructSlotType`, a
   side table; `LispLayout` stays type-free). Trusted only while the accessor's generated body is
   the ONE definition the call can reach: off under `--dynamic` and for a name defined more than
   once (`Ctx.duplicatedDefunNames`).
4. A `(the spec expr)` wrap at the array position.

- **A `GENERAL` site takes the inline arm of an unpinned vector at the speed levels**
  (`.kb/adjustable-arrays.md`, "What an element access costs"), in and out of EH mode; what the
  arm refuses is checked as an unpinned read's is. A general-array move: 66 -> 9 ns (undeclared
  21-25). The JVM reads no array declaration.
- **The checks before the access.** A packed-kind site tests the ONE type inline and calls
  `_arr_check_rank` only when it fails (`emitKindedCheckRank`; the unconditional call was 9% of an
  ironclad PBKDF2 component). A subscript whose SHAPE answers an integer skips `_idx_chk`
  (`WasmIntFusionCompiler.answersInteger`).
- **A fused read takes the pin too** (`.kb/wasm-int-fusion.md`): a DIRECT read casts like the
  unfused one, so a false declaration traps there as well; a general array whose elements are
  integers by the pin is read with no bail ("Ranges, and sites with nothing to bail on" there). A
  constant's packed vector (`WasmArrayCompiler.constantArrays`) is a fused read's kind only, never
  `arrayKindOfExpr`'s: its global is nothing before the definition ran, which the unfused read must
  keep reporting as the generic read does.
- **A pinned packed store takes a raw subscript** (`WasmArrayCompiler.emitRawSubscriptAset1`,
  `WasmIntFusionCompiler.tryCompileRawSubscript`): subscript as i64, then the value, then the
  direct read's checks. A 50,000,000 sieve 8.9 -> 1.8 s on wasm (JVM 2.9 s). **The `GENERAL` kind
  too** (`emitSimpleVectorAccess` with a raw slot); its value stays boxed. Pins:
  `ArefRankAndSubscriptChecksFixture.ORDER_SOURCE`'s `ao-d8put` and `ao-dtput` rows.
- **nil and a general array's elements** (what the Java front end declares, `.kb/java-frontend.md`,
  "Numbers"). A declaration pins a `DeclaredArrayTypes.Pin`: the kind, `orNull` (`(or null X)`;
  an `(and ..)` admits nil only where every pin does) and, for `GENERAL`, the element integers
  (`elements`): CLHS's `type` declaration of an array type makes every element read of the
  EXPRESSED element type, so `(simple-array (signed-byte 64) (*))` is a general array of integers.
  An element access checks nil before the cast, after the subscript and a store's value, so it
  takes the kind either way; what reads the kind alone takes it only of a surely-array value
  (`Pin.arrayKind`: a packed `length`, `provesArrayValue`). Pins:
  `ArefRankAndSubscriptChecksFixture.ORDER_SOURCE`'s last rows (every backend and level); java-spec
  `the-run-time-raises-the-language-s-exceptions`.
- A `replace`/`fill` whose DESTINATION is pinned non-string calls the array-arm-only shared runtime
  (`provesArrayValue`, [sequence-op-runtimes.md](sequence-op-runtimes.md)). That predicate also
  answers on the WEAKER fact "an array of some representation" (`Ctx.arrayLocals`) --
  **`initExprPin` must keep refusing those**: it picks an accessor and a wrong rank is a wrong
  accessor.
- Emission at EVERY optimize level, in the generic (array, index, value) order; a false declaration
  may observe index/value side effects before the cast traps -- inside UB.

## Declared integer ranges (wasm-GC; the JVM)
**A variable or a `the` value a declaration gives an integer range within i64 is taken raw: a
fused site unboxes it with no bail, so a site with nothing else to bail on has no fallback.**
What the Java front end lowers every `int` and `long` operation to (`.kb/java-frontend.md`,
"Numbers").

- **Which specifiers** (`compiler/DeclaredIntegerTypes`, over `macro/IntegerTypeRange`):
  `(signed-byte n)`, `(unsigned-byte n)`, `(integer lo hi)`, `(mod n)`, `bit`, an `(and ...)` of
  them and a `deftype` alias, bounded within i64 at both ends. **`fixnum` proves no bound**: its
  `typep` is `integerp` here, so `(the fixnum (expt 2 70))` is a true declaration and must answer.
  Only the long form `(declare (type spec var...))` at a body head is read. Every binder but `let`
  shadows the name (`WasmShadowedNames`), or an outer declared array of the same name traps on the
  cast. `let*` puts every declaration in its innermost `let`, so an outer binding's is free there,
  its reads trusted and its slot not kept raw.
- **A trusted leaf** (`ExprLeaf.trusted`): a declared variable no raw local holds, or a `(the spec
  X)` of a leaf. `emitTrustedUnbox` checks only what the representation does not prove, then
  `unreachable`. It is not a guard of an order boundary (`pendingGuard`).
- **An exact local over a declared range** (`RawLocal.declared`): a declared `let` binding or
  required parameter, only where nothing captures it, only `setq`/`setf` assign it and it is **read
  mostly raw** (`DeclaredIntegerTypes.readMostlyRaw`, the JVM's rule too): every other read boxes it
  again, past the fixnum range an allocation each. Measured: every declared variable raw beat none
  (Booking 1,143,837 -> 1,136,369 B), the rule beat that (1,133,686), every declared parameter raw
  was worse (1,143,772). A store checks the range unless the value's range is within it
  (`emitStoreCheck`).
- **A false declaration traps where the trusted value is taken** -- the unbox, the store -- and
  never yields a wrong value: no range is used before its check. The interpreter and the size level
  read none of these.
- Pins: `WasmLispCompilerIntegrationTest.fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`
  (`testsupport/DeclaredIntegerProgram` on P1 and the component, fixnum included; two false
  declarations that trap), `.declaredArrayTypesEmitSingleArmAccessorsWithoutChangingResults` (the
  lambda that shadows); `JvmLispCompilerTest.aDeclaredIntegerLeafIsCastAndAMethodWithNothingElseToBailOnHasNoFallback`
  (both levels, a handler-case variable, a false declaration caught); ci-spec
  `declared-integer-ranges-answer-what-undeclared-code-answers` (the four backends).

### On the JVM
**A leaf the declarations prove an integer within `long` is a `Long`
(`.kb/core-representation.md`): the fused method takes it with a cast, and a method with nothing
else to bail on has no fallback, no `ArithmeticException` handler and no all-Double path.** The
JVM uses no range, so it checks none: an integer outside its declared range is computed as
undeclared code computes it (diverging from wasm's trap); a non-integer fails the cast, a
catchable `type-error`, and so does an integer past `long` an exact local stores
(`.kb/jvm-int-fusion.md`, "Exact locals").

- **What is trusted** (`JvmIntFusionCompiler.declaredLong`): a variable whose declaration is its
  binding's own -- `Ctx.declaredIntegers` maps the name to the binding it resolved to (`bindingOf`),
  so a binder that shadows it without knowing the map (a handler-case variable) is untrusted. A
  `(the spec (aref a i))` is an opaque leaf, not the aref leaf: measured and not made
  (`.kb/jvm-int-fusion.md`, "Packed aref leaves"; `.todo/m28`).
- **The cast stands behind its own `instanceof`** (`emitTrustedCheck`): a bare `checkcast` made
  `Sieve` 0.8 -> 1.5 s under the Graal JIT (Oracle GraalVM 25's default); C2 runs either alike.
- Measured: `Zone.class` 271,493 -> 260,065 B, handlers 196 -> 75; times within noise -- C2 and
  Graal already prune the cold fallback and scalar-replace the boxes.
- **Not taken: range proofs that drop `Math.addExact` and the other checks.** The checked
  operations left in a declared Java program's classes are mostly library Lisp, and a range used
  would need the range checked.

## makeTypeTest (shared type-specifier tests)
`LispMacroExpander.makeTypeTest(value, spec)` builds a truthy test form; `makeTypecaseTest`
delegates to it, so `typecase`/`etypecase` heads accept the same specs as `check-type`. The value
form may be evaluated several times, so callers bind a temp.

- **EMPTY types** (constant-nil, agreeing with runtime `typep`, all in `PackageRegistry.CL_TYPES`):
  `generic-function`/`standard-generic-function` (a defgeneric dispatcher is a plain function value
  with no marker), `structure-class` (a defstruct class metaobject IS a standard-class) -- routing
  trivia level2 onto its portable fallback; `logical-pathname`/`extended-char` (no such value).
  `CLASS`/`STRUCTURE`/`TYPE` are in CL_TYPES because trivia NAMES its patterns with them, and the
  defpattern site and a user's pattern site must resolve to the same bare spelling.
- **Atomic map** (`atomicTypePredicate`): `ratio` is `(and (rationalp v) (not (integerp v)))` (as
  `rationalp`, jonathan's `(ratio)` method took `3`); `array` does NOT check the rank (`vector` goes
  through `makeArrayTypeTest`); `standard-char` is spelled with `char-code` because a type test
  splices no prelude defun. **`fixnum`/`bignum` stay `integerp` on purpose:**
  `most-positive-fixnum` is 2^30-1 on wasm, so a real test would refuse `(check-type x fixnum)`
  over 2^30 there and take every library's `fixnum` arm off the values SBCL keeps on it.
- **Type-specifier symbols match by package-STRIPPED name** (`plainTypeName`): standard type names
  are not all registered CL symbols, so in a user package the resolver would qualify e.g.
  `unsigned-byte`. `CL_TYPES` registers the common type-only names so they resolve BARE -- required
  where the name reaches RUNTIME data (symbols compare by name).
- **A collapse claims two names denote the SAME SET, so it answers `T` in both directions; an edge
  answers it one way.** `bfloat16` is an EDGE below `SINGLE-FLOAT` (`SUBTYPEP_PARENTS`), never an
  alias, because its set is EMPTY (the width lives in array storage, `.kb/bfloat16.md`). As an alias
  `(subtypep 'single-float 'bfloat16)` answered `T` against `(typep 1.0 'bfloat16)`'s `NIL`, and a
  COMPUTED pair answered `NIL`, because `subtypepUniverse` derives edge names from the table and
  lists alias names BY HAND. **An edge is in the runtime universe by construction; an alias is only
  as present as that list.** Pins: `LispFloatArrayTest`, `JvmBFloat16ArrayTest`, ci-spec
  `bfloat16-type-lattice`.

## single-float is the floats a binary32 holds
**Every float is one binary64. `float`/`double-float`/`long-float` are that type; `single-float`/
`short-float` are the SUBSET a binary32 holds exactly (a NaN included), on all four backends.**
One test, `%single-float-p` (`Binary32.holds`; wasm adds `f64.ne` for a NaN, since a demotion may
invent one), so literal and computed `typep`, `typecase`, `check-type` and a method's specializer
all take it; a single-float method ranks ahead of a double-float one. `coerce` to it and an
`f`/`s` exponent marker answer the binary32 nearest the double. Unchanged: arithmetic (binary64),
printing (`0.1f0` prints `0.10000000149011612`), `float-digits` 53, `type-of` `FLOAT`, array storage.

- **Why.** Under the collapse every float met a library's single arm first: cl-conspack wrote each
  double as float32, cl-postgres sent float4. Quicklisp has a single arm ahead of the double one in
  cl-conspack, cl-store, cl-postgres, coalton (`CoaltonForms`: a `d` marker F64, else F32),
  petalisp, lisp-unit, mt19937, mcclim, float-features.
- **Why not a real binary32 value.** Every numeric operation on four backends would carry a second
  float (`LispDouble` alone 577 references in 101 files), and it would buy a library little: with
  `*read-default-float-format*` `double-float`, which every program and page assumes, `1.0` stays a
  double, and CLHS's initial `single-float` changes the printed text of every float program.
- **Deviation.** CLHS's float arrangements are one format or disjoint ones; a subset is neither.
  `(typep (* 0.1f0 3) 'single-float)` and `(typep 0.1 'single-float)` are nil where SBCL answers t.
  A rational converts through the double, so a 17+ digit token or a ratio near a binary32 midpoint
  rounds twice. **Trigger:** a consumer needing binary32 ARITHMETIC (rounded per operation), or a
  format a value's history decides.
- Pins: ci-spec `single-float-is-the-floats-a-binary32-holds` (all four), `postmodern-language-incidentals`,
  `bfloat16-type-lattice`; `LispEvaluatorTest#evalExponentFloatLiteral`, `LispFloatArrayTest`.

## The array type lattice: type-of BUILDS the compound specifier
**`type-of` answers an array's COMPOUND specifier and `makeTypeTest` takes the same one back -- one
contract, they must move together.** Shapes are SBCL 2.2.9's (`(SIMPLE-VECTOR 4)`, `(VECTOR T 4)`
for fill-pointer/adjustable/displaced, ...), except `STRING` for `"abc"` (SBCL:
`(SIMPLE-ARRAY CHARACTER (3))`).

- **`type-of` is the PRELUDE defun** (`LispPreludeLibrary`), one source for four backends. Its
  array arm is guarded by `%arrayp` to keep CHARACTER arrays out (a rank-1 character array is a
  string VALUE on the interpreter, a marked general array compiled; both DESIGNATE `STRING`). **The
  SIMPLICITY arm is tested FIRST** (a remembered element type and a fill pointer coexist), with
  `%simple-array-p`: the public predicates cannot see a displacement.
- **The element type is compared UPGRADED** (`upgradedArrayElementType`): the float widths,
  `(unsigned-byte 8|16|32)` and `bit` keep their name, the character family is `character`,
  everything else `t`. So `(typep a '(simple-array fixnum (4)))` is a `t`-array test: conformant,
  and the one shape array-operations' suite still fails on.
- Both VECTOR spellings, `vectorp` and a bare `vector`/`simple-vector` clause pin rank 1; only
  atomic `array`/`simple-array` stay rank-blind.
- **Trap:** `expandVectorp` takes the `arraysExist` flag and drops the whole array arm when the gate
  is off -- `vectorp` has an injected first-class wrapper every program carries until the shaker
  runs, and an ungated rank read there put `_arrayDims` in `(print 1)` and forced the entire array
  runtime back on ([adjustable-arrays.md](adjustable-arrays.md)).
- A rank-n (n>1) CHARACTER array is the plain general array on ALL backends and REMEMBERS its
  element type, so `type-of` answers `(SIMPLE-ARRAY CHARACTER dims)` everywhere.

## The COMPOUND half of the runtime typep dispatch
**A computed specifier takes exactly the set a literal one does, because the compound families are
interpreted out of the specifier VALUE by one Lisp source both dispatch shapes carry** --
`LispMacroExpander.RUNTIME_COMPOUND_TYPEP_SOURCE`. The interpreter inlines it; the compile paths put
it in a `%typep-compound-runtime` defun -- separate from `%typep-runtime` because of the JVM's
16-bit branch offsets.

- **`%typep-runtime`'s FIRST cond arm is the `consp` route into it**, before the instance branch,
  since an instance is an ordinary member of an `(or foo bar)` and the tag table only knows NAMES.
- A name missing from `RUNTIME_TYPEP_BUILTINS` answers nil when computed; check it when adding a
  type name.
- Size: it reaches the generic array accessors and `funcall`: `hello-clack` Worker
  (`--no-wasi --optimize=size`) +5.2% raw / +5.9% gzipped, ~half the ARRAY arm; no computed
  `typep` -> byte-identical. **Trigger: if a Worker row needs the bytes back, move the array arm
  into a defun of its own on a narrower gate.**

## A `deftype` ALIAS resolves at RUN TIME too
**A type designator held in a VALUE resolves a user `deftype` exactly as its literal spelling does,
on all four backends** (and `coerce` with a computed result type). The compile paths put the alias
set in a quoted DATA table (`%deftype-alias-table%`) scanned once from `%typep-runtime`, AFTER the
metaobject normalization that turns a class object into a name.

- **A name the dispatch already decides -- a built-in spelling, a registered class, a struct -- is
  left OUT of the table**: the literal path resolves a `deftype` only after those three, so
  normalizing first would reorder the reading.
- **The table carries only the aliases the program SPELLS** (`narrowedDeftypeAliases`): unnarrowed
  it cost array-operations +10.7% (the alias NAMES), narrowed +1.9%.
- Not covered: a designator built at run time out of characters answers `nil` on the compile paths
  (the interpreter resolves it). An alias as the ELEMENT type of a computed compound (`(vector
  my-char)`) is compared unresolved on all four. The `make-array :element-type` half is
  [array-literals.md](array-literals.md), a DIFFERENT narrowing.

## A PARAMETERIZED `deftype` as a compound specifier
**`(name args...)` is what the deftype's body returns over those arguments, on all four
backends, literal or computed** (trivial-types' `(proper-list integer)`).

- Compile paths have no evaluator, so `UserMacroExpander.emitDeftypeInstances` expands each
  compound use in the RESOLVED program and appends `(declaim (%deftype-instance 'spec
  'expansion))` -- a declamation because every pass reads one as inert data. Appended at the END,
  so bare symbols are spelled `cl-user::` (`contextFreeSpelling`). The instances join
  `%deftype-alias-table%`. A spec assembled at run time from NEW arguments resolves on the
  interpreter only. Pins: `LispEvaluatorTest#evalRuntimeTypepResolvesADeftypeAlias`, ci-spec
  `typep-parameterized-deftype-compound`.

## The COMPOUND half of `subtypep`
**A compound specifier works on either side of `subtypep`, quoted or computed, and answers the same
on all four backends.** Written twice -- `LispMacroExpander.subtypep` over the AST (the
interpreter's builtin AND the literal fold) and `RUNTIME_COMPOUND_SUBTYPEP_SOURCE` over a runtime
specifier VALUE. Twins, not one source: the static side must stay Java because the emitted ancestor
table is GENERATED from it. **Change them together.**

- The `(or ...)` SUB is split FIRST, being exact (else `(or float rational)` against `(or rational
  float)` was nil). A numeric name the branches of an `(or ...)` super cover is a subtype
  (`NUMBER_PARTITIONS`, CLHS 12.1.1; serapeum's `dispatch-case` exhaustiveness). Pin: ci-spec
  `computed-compound-subtypep-specifier`.
- **Any other head as the SUB reduces to that head and re-tests**; the same reduction on the SUPER
  would be unsound, so `(subtypep 'integer '(integer 0 10))` stays nil (SBCL agrees).
  `(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)` stay unknown
  (`OPAQUE_COMPOUND_TYPE_HEADS`).
- **Integer INTERVALS decide by containment** (`macro/IntegerTypeRange`), exact; runtime twin
  `RUNTIME_SUBTYPEP_INTERVAL_SOURCE`, injected only when the program spells an interval type. Two
  plain NAMES stay the lattice's on purpose: `bit <= unsigned-byte <= signed-byte = integer` is true
  and missing there, but adding the edges regenerates the ancestor table of every
  computed-`subtypep` program. Pins `LispEvaluatorTest#subtypepDecidesIntegerIntervals` + the JVM /
  WASM `compileSubtypepIntegerIntervals` twins.
- **A deftype name against a deftype name** tries the super's expansion alone first
  (`namedSubtypep`): a branch of it may name the sub (serapeum's `key-designator` holds
  `function-name`), which expanding both loses.
- **Array specifiers decide by their parts** (`macro/ArrayTypeShape`: upgraded element type,
  dimensions, simplicity), exact. **Deviation**: a string IS `(array character (*))` here, one
  character type, where SBCL has base strings besides. Runtime twin `RUNTIME_SUBTYPEP_ARRAY_SOURCE`.
  Pin: ci-spec `subtypep-reads-array-specifiers`.
- **A CIRCULAR `deftype` is an unknown type name, never an endless expansion.** `subtypep` threads
  the deftype names being expanded on the current path, so postmodern's `(deftype tsvector ()
  'tsvector)` answers nil against everything but itself. SBCL hangs on the same query. Load-bearing
  because `subtypepAncestorTableForms` asks about EVERY registered deftype name: one circular name
  overflowed the stack of every compile with a computed `subtypep`. `typep` over such a name still
  recurses (undefined in CL). Pins `LispEvaluatorTest#evalSubtypepOverCircularDeftypes`, ci-spec
  `runtime-subtypep-beside-circular-deftypes`.
- **A bare lattice name is that type**: `SubtypepName.classInfo` takes only an exact class for a
  `SUBTYPEP_PARENTS` key, since `findClass`'s tolerance for a bare spelling answered `coalton:bit`
  for `cl:bit`. ci-spec `subtypep-fixnum-is-a-63-bit-interval`.
- **fixnum is SBCL's (signed-byte 63)** on all four: `IntegerTypeRange`, `%subtypep-interval`, and
  `most-positive-fixnum` `2^62-1` on the interpreter and the JVM (`ClConstants`; wasm keeps its i31
  bound). **The width is ours to change, not typo's to absorb:** `typep` of `fixnum` is `integerp`,
  so the type is a convention, not a representation, and libraries were written against a tagged
  fixnum narrower than a word. typo's primitive list puts `(unsigned-byte 63)` after `fixnum` and
  asserts no later entry is a subtype of an earlier one, so it needs 33..63 bits (coalton at least
  32). `sxhash` masks by the constant. Pin: ci-spec `subtypep-fixnum-is-a-63-bit-interval`.
- **The stream subtypes are edges to `STREAM`**; they join the runtime universe only for the names
  `runtimeStreamTypeNames` keeps (`.kb/gray-streams.md`).
- **Trap:** a lattice LEAF with no `SUBTYPEP_PARENTS` entry (`hash-table`, `function`, ...) has no
  ancestor-table row, so `subtypepUniverse` adds every `RUNTIME_TYPEP_BUILTINS` name except `T`;
  without it a runtime `(subtypep 'hash-table 'hash-table)` answered nil compiled.
- **The ancestor table resolves each name ONCE.** `subtypepAncestorTableForms` asks about every
  PAIR of the universe (U^2); re-resolving both names per pair made mito's table (U = 1,218) ~40 s
  per `JvmLispCompiler` retry attempt -- 90% of a 253 s compile. Per-name lookups (`SubtypepName`,
  kept on first use) and `ClosRegistry`'s member-name indexes (dropped by each registration) took
  it to 0.3-0.45 s. Pins `LispMacroExpanderTest#theRuntimeSubtypepTableAnswersEveryPairAsSubtypepDoes`
  and `ClosRegistryTest` (the kept lookups follow registration).

## `subtypep` answers CL's VALID-P as its second value
**Invariant: a nil primary claims a DECISION only between two plain type NAMES. Every compound rule
above is sound but NOT complete, so a nil over a compound is `nil nil`.** `subtypepValid` is the one
decision (`LispMacroExpander`), `RUNTIME_SUBTYPEP_VALID_SOURCE` its runtime twin; a `t` primary is
always a decision. Exception: a nil between two integer INTERVALS is a decision (containment is
exact; coalton's `definitely-subtype?` signals on a nil valid-p). A deftype standing for a compound
is not a plain name.

- The value crosses the call boundary through the ordinary multiple-value tier
  (`%subtypep-valid`, the shape of `find-symbol`'s `%find-symbol-status`, [[multiple-values]]). A
  LITERAL specifier is passed through the lowering rather than bound to a temp, or the fold would
  not see it.
- **This rule was MEASURED, not reasoned.** Claiming a decision for everything outside the opaque
  heads was **+486 tests and -82** on the ANSI suite (`check-equivalence` FAILS a false claim --
  `(and (cons symbol *) (cons * symbol))` really is `(cons symbol symbol)`); name-vs-name gave
  **+443 and 0 regressions**. Under-claiming is conforming; over-claiming is a wrong answer.
- Deviation: an unknown type NAME answers `nil t` (the name universe is closed here) where CL may
  answer `nil nil`.
- Pins: `LispEvaluatorTest.subtypepAnswersCommonLispValidP`,
  `JvmLispCompilerTest.compileSubtypepValidP`,
  `WasmLispCompilerIntegrationTest.compileSubtypepValidP`; the component backend was verified by
  hand ([[running-backends]]).

## The `simple-` names are lattice EDGES, not aliases
**`simple-vector`, `simple-array` and `simple-string` name strictly smaller types than
`vector`/`array`/`string`, so `subtypep` answers `T` one way and `NIL` the other, on all four
backends** (`LispMacroExpander.SUBTYPEP_PARENTS`). So `simple-vector` <= `sequence` while
`simple-array` is NOT a sequence, and `simple-string` is NOT a `simple-vector`.

- **`string` was a decision, not a fix**: "every string here is immutable" is true of a LITERAL only
  -- a fill-pointered character vector, an `:adjustable t` string and a displaced string VIEW are
  all `stringp` and none is simple. So `(subtypep 'string 'simple-string)` is `NIL`.
- **`base-string`/`simple-base-string` stay collapsed** onto `string`/`simple-string`: ONE character
  type here. **Trigger: if a narrow character type lands, these two must become edges in the same
  pass.** It is the only place the pinning program deviates from SBCL.

## `typep` checks SIMPLICITY, through `%simple-array-p`
**`(typep x 'simple-vector)` and its two siblings answer `NIL` for a value that is not simple -- a
fill pointer, `:adjustable t` or a displacement -- on all four backends, and the one predicate that
decides it is `%simple-array-p`**, which is TOTAL (`nil` for every non-array value), so a call site
needs no guard.

Why not the public probes: `%array-disp-target` throws on a plain string and a packed vector, and
displacement is the one condition the public surface cannot be asked about; a value can be BOTH
`stringp` and `%arrayp` or neither, so no ordering of guards covers every backend.
`simple-string-p` takes it (CL requires it to agree with `(typep x 'simple-string)`), and so does
`coerce`'s "already of the result type" guard: `(coerce x 'simple-string)` on a NON-simple string
copies it.

**JVM trap:** `make-array :element-type 'character` with no `:fill-pointer` (`make-string`
included) must leave the header fill-pointer slot nil, not the capacity: mutability is the
character-vector MARKER's, not the fill pointer's.

## A sized string specifier measures the DIMENSION (`%string-dimension`)
**`(typep x '(string n))` and its siblings compare `n` against the array DIMENSION of the string,
never against `length`, on all four backends** (`length` of a fill-pointered character vector is
the FILL POINTER). The internal accessor is also SMALLER than `length` on both compile backends,
and the public-surface alternative would drag `ctx.usesArrays` and the whole array runtime into a
program with a string test and no arrays. `%string-dimension` is NOT total and need not be: every
call site is inside a string arm `stringp` already gated.

- **Unresolved divergence:** `vector-push-extend`'s DEFAULT extension. Growing a capacity-2 vector
  to five elements leaves dimension 8 in SBCL, 8 on the interpreter for a character vector but 5
  for a general one, and 5 on the JVM for both. `.todo/614`.

## Top-level flattening (flattenTopLevel)
`LispMacroExpander.flattenTopLevel` splices top-level `progn` and `eval-when`. Called at
`UserMacroExpander.expand` entry (so an `eval-when`-wrapped `defmacro` registers at compile time)
and in the compilers before `expandTopLevelDefstructs` (direct compiler invocations). A malformed
`eval-when` is left unspliced so the expander's validation error surfaces.

**Known gap:** `LoadInliner` runs BEFORE `UserMacroExpander`, so a top-level
`(load ...)`/`(require ...)`/`(asdf:load-system ...)` wrapped in `eval-when` is NOT inlined on the
compile path. Unwrapped directives are unaffected.

## An eval-when's position (CLHS 3.2.3.1)
All four against SBCL 2.2.9 (ci-spec `eval-when-at-no-top-level-asks-for-execute`,
`eval-when-at-top-level-stays-there`).

- **At no top level** only `:execute` or `eval` runs the body, the form answering nil otherwise
  (`expandEvalWhen`). `SpecialVarCollector.collectBoundForm` walks the body regardless: it does not
  know the position, and a needless binding costs nothing.
- **At top level** any situation runs it, an empty list none: a program stands for both its compile
  and its load, so all four print a `(eval-when (:compile-toplevel) (print 1))` a source spelled.
  What a macro expanded to is read as `compile-file` reads it
  ([defmacro-backquote.md](defmacro-backquote.md)).
- **What keeps its body at top level:** `progn`, `locally`, `eval-when`, `macrolet`,
  `symbol-macrolet`, a macro's expansion, and what `flattenTopLevel` opens:
  `uiop:with-upgradability`, `with-deprecation`, a `handler-bind` binding nothing (as SBCL; its
  `with-compilation-unit`, `handler-case`, `prog1`, `when`, `cond`, `let`, `flet`, `block`, `the`
  do not, measured). `uiop:nest` does not here.
- **Compile paths:** no program leaves `UserMacroExpander` with a top-level `eval-when` or
  `symbol-macrolet`: `--dump-ir` relies on it. The playground and a direct compiler call never
  enter the pass, so the backends flatten with `flattenCompiledTopLevel`.
- **Interpreter:** top-level entries end in `evalAtTopLevel`, which marks the form (per thread, by
  identity); only the macro call, `macrolet` and `symbol-macrolet` ask the mark, so `evalCons`'s
  hot `progn` asks nothing.
- **Libraries sized**: every `eval-when` naming no `:execute` below a syntactic top level in
  `src/`, `examples/` and the quicklisp cache is a macro's body or template used at top level, or a
  test; 383 non-test systems loaded with `expandEvalWhen` dropping nothing.
- Lite: a macro call in a body that does not run still expands on a compile path. A compiled
  run-time `load` goes through `_eval`, so only `:execute` runs there (the interpreter's `load`
  runs a form for every situation).

## Wiring points
`FreeVarAnalyzer` needs explicit cases (BOTH methods) that expand first: the default walk would
misread the type symbol in `(the integer x)` as a free variable reference and collect declaration
specifiers.

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
  `declared-float-scalars-answer-what-undeclared-code-answers`,
  `declared-integer-ranges-answer-what-undeclared-code-answers`, `computed-compound-type-specifier`,
  `array-type-of-and-compound-array-specifier`, `vectorp-and-the-vector-specifier-check-the-rank`,
  `runtime-typep-deftype-alias`, `computed-compound-subtypep-specifier`,
  `simple-type-name-subtypep-lattice`, `simple-type-name-typep-simplicity`,
  `postmodern-language-incidentals`.
