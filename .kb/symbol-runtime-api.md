# Runtime symbol API (`symbol-name`/`intern`/`find-symbol`/`make-symbol`/`boundp`/`fboundp`/`symbol-value`/`macro-function`/`special-operator-p`)

## `symbol-name` drops the package qualifier
**`symbol-name` (and the string-designator coercions) return the MEMBER name**:
`(symbol-name 'foo::bar)` is `"BAR"` (`LispSymbol.memberName`). `princ`/`~A`/`display` keep the
qualifier (`LispSymbol.displayName` strips only the `:`/`#:` markers); `prin1` keeps everything.
This is what makes name surgery work: `(intern (concatenate 'string (symbol-name x) "-SUFFIX"))`
under `(in-package p)` would otherwise re-qualify into `P::P:X-SUFFIX`. The CASE-folding
designators are in that set too (dropping only a LEADING keyword colon made
`(string-downcase 'foo::test)` answer `"foo::test"` on the compiled backends). Pinned by the
`symbol-runtime-api` ci-spec case (all four backends, SBCL-checked).

## String designators: `(string ...)` is the ONE coercion, and it type-checks
**A CL string designator is a string, a symbol, or a character — and NOTHING else.** Every
position specified that way reaches it through the shared `(string ...)` coercion, injected by
`LispMacroExpander.normalizeStringDesignatorArg` on the compile paths and applied by
`Environment.stringDesignator` in the interpreter. **Do not teach an intrinsic the designator
rules; widen the position to route through `string`.**

Designator positions, and nothing outside this list: the `string`/`symbol-name` argument; the
`string-upcase`/`-downcase`/`-capitalize` argument (`normalizeStringDesignatorArg(cons, 1)`);
the `string-trim` family's **trimmed value** (`normalizeStringTrimArgs`, position 2) but **not**
its character bag (a SEQUENCE); `string=`/`string-equal` operands
(`normalizeStringComparisonDesignators`); `string<` and the nine other ordering predicates (via
`(string a)` inside the shared `%string-compare` prelude walk). `concatenate` arguments are
**not** designators; `format` `~A` is `princ`, total by definition.

**The coercion SIGNALS on a non-designator, on all four backends** — the bare princ coercion is
total (`(string 42)` -> `"42"`), which silently turned a type error into a plausible string in
every widened position. A computed argument compiles to
`LispMacroExpander.strictStringDesignatorForm`
(`(if (or (stringp g) (symbolp g) (characterp g)) (%princ-piece g) (error ...))`); a
compile-time-known one costs nothing (`literalStringDesignator`). Pinned by the
`string-designators` ci-spec case and the `stringDesignators` tests on all three engines.
**Re-evaluate when** a new operator names a string designator in its CLHS entry: the change is
one `normalizeStringDesignatorArg` call at its dispatch site, not a new coercion.

## Packages at runtime
**A "package" at runtime is the UPCASED canonical package name as a keyword** — there are no
package objects, and `eq` compares symbols by content, so `find-package` and `symbol-package`
agree by construction. Upcased because the compile paths' spelling comes from reader-upcased
literals.

- `find-package`: nil for an unknown package; a LITERAL designator is folded by
  `PackageResolver.resolveCons`, a computed one stays a runtime call.
- **`package` is BOTH a type specifier and a `defmethod` specializer, from ONE definition**
  (`LispMacroExpander.isSupportedTypeSpecializer` + `makeTypeTest`), so `(typep x 'package)`, a
  `typecase` clause and a `((p package))` parameter are the same predicate on all four backends
  (`.kb/clos.md`). **Re-evaluate when** a consumer needs package objects DISTINCT from keywords.
- `symbol-package`: registry-backed on the interpreter (`PackageResolver.symbolPackageName`: the
  home off the spelling -- `cl` for every standard name, `t`/`nil`/the exported-only ones
  included -- minus the `unintern` tombstones, `.kb/packages.md` "The member table");
  elsewhere a `LispPreludeLibrary` defun: `%symbol-home` reads the qualifier off
  `prin1-to-string` and a bare name is `cl`'s when the table of the standard names holds
  it (`.kb/packages.md`, "The standard names at run time"); an uninterned symbol keeps its
  old home.
- `type-of`: a prelude defun over the internal `%class-designator` (NOT `class-of`, which
  answers a metaobject), stripping the `%struct-`/`%class-` tag prefix. It `intern`s the
  remainder, so it is right only because `PackageResolver.internSpelling` routes a qualifier the
  REGISTRY knows through `resolveQualified` instead of homing the whole string into the current
  package (which produced `APP::LIB:WIDGET`). Pinned by
  `LispEvaluatorTest#evalTypeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage`
  (+ twins) and ci-spec `type-of-foreign-package-class-name`.
- **2-argument `find-symbol`**: interpreter = registry-backed
  (`PackageResolver.accessible`: present member, inherited export, standard name, keyword --
  `.kb/packages.md` "The member table"), returning the canonical spelling; on the compiled
  backends a symbol IS its canonical spelling, so for a READ/COMPILE-TIME package
  `expandFindSymbolInPackage` BUILDS it (`(intern (concatenate 'string "PKG:" name))`, or the
  same over `(string PKG)` when computed), while a package the program created with
  `make-package` answers from its `%runtime-packages%` member table (`runtimeMemberLookup`, so
  nil before an intern and the symbol after hold there too). What the registry KNOWS a baked
  package reaches -- an import, a re-export, a `:use`-inherited symbol, a recorded own member
  (`:shadow`, `:intern`) -- answers as on the interpreter, literal or computed
  (`.kb/packages.md`, "Compiled lookups answer through the registry"). For the rest the
  build answers: `PKG::NAME :internal` in a package the program defines (its exports are row
  entries, `.kb/packages.md`), `PKG:NAME :external` in a pre-seeded one. The deviation left:
  an unknown name yields a symbol instead of nil. **It is NOT harmless in the `find-symbol` ->
  `symbol-function` idiom**: sxql's `find-make-op` probes with `:errorp nil` expecting nil, so
  every sxql SQL FUNCTION operator dies on JVM and WASM while the interpreter is correct. The
  honest fix is symbol IDENTITY. Tripwire:
  `MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends`.
- **A bare non-keyword symbol in the package-argument position is a VARIABLE REFERENCE, never a
  designator literal** — reading its name as the package once built `IRONCLAD::IRONCLAD:SHA256`.
- **A quoted LONE SYMBOL is package-resolved in ordinary code**, not only inside a defmacro
  template. Quoted LISTS stay untouched, and the `wasm-export`/`wasm-import` option tail is
  exempt (`inHostFacingData`) because its quoted values are host-facing data.
- **`(let ((*package* X)) ...)`** is a genuine dynamic binding on every backend
  (`.kb/packages.md`). The runtime package API: `make-package` / `delete-package` /
  `rename-package` / `packagep` / `package-nicknames` / `find-all-symbols` /
  `do-all-symbols` / `apropos` / `apropos-list` / `package-error-package` (.todo/741) and,
  over the member table, `shadow` / `shadowing-import` / `unintern` (.todo/917,
  `.kb/packages.md` "Runtime tier").

## The spelling-identity model (with a member table)
rontolisp symbols compare by name -- a symbol IS its canonical spelling, there are no symbol
objects -- which shapes every deviation. What a package RECORDS is a separate question:
since `.todo/917` a package keeps a MEMBER table of what `intern` / `export` / `import` /
`shadowing-import` / `shadow` put into it (`.kb/packages.md`, "The member table"), so
"interned in P" is answerable without symbol identity.

- `symbol-name` returns the STORED spelling **without the package marker**
  (`LispSymbol.displayName`, shared with `princ`/`~A`/`string`; `prin1`/`print` keep the stored
  spelling), upcased for every symbol read from source under
  `.kb/reader-case-upcase.md`.
- `intern`/`find-symbol` take the name VERBATIM: `(find-symbol "car")` = `NIL`,
  `(intern "time")` = the distinct `time`.
- 1-arg `intern` interns into the **current package** on the interpreter
  (`PackageResolver.internSpelling(name, record)`, the `LispEvaluator` override; the
  `Environment` converter and the compiled backends' 1-arg intern stay package-blind) and
  RECORDS a fresh name in the package's member table (`recordInterned`). The interpreter's
  runtime READ homes each symbol token the same way (`PackageResolver.readSpelling`,
  `.kb/packages.md`); the compiled backends' runtime reader stays package-blind with their
  intern. `(intern name
  :keyword)` builds a keyword; any other package argument goes through
  `PackageResolver.internSpellingIn`. A designator naming no package is a catchable
  `package-error` on every backend (the interpreter's `intern` checks before the resolver,
  whose `LispPackageException` no handler sees).
- `t` and `nil` are singletons, not `LispSymbol`s: everything that answers a symbol by NAME
  (`find-symbol`, `intern`, the enumerations) maps the spellings `"T"`/`"NIL"` back to them,
  on EVERY backend. Interpreter: `LispEvaluator.symbolOfSpelling`, plus
  `PackageResolver.internSpellingExact` answering bare `T`/`NIL` in a cl-using package (the
  resolver gave `(intern "T" "CL")` the cl-owned spelling `CL:T`). Compiled: only NIL needs an
  arm, because `t` already IS the symbol `"T"` there (the JVM string `"T"`, `eq` by content;
  the WASM interned symbol at T's offset) -- the JVM's 1-arg intern goes through the shared
  `_internName` helper (quote strip, then `"NIL"` -> `null`), WASM's `_intern_sym` compares the
  interned offset against NIL's and returns the null ref (the reader's own rule); the cl arm
  of a literal 2-arg `find-symbol` folds through `symbolFormOfSpelling`. Pinned by
  `TAndNilSingletonCorpus` (`LispEvaluatorTest` / `JvmLispCompilerTest` /
  `WasmLispCompilerIntegrationTest#...TAndNilSingletons`) and the ci-spec `symbol-runtime-api`
  case. **Measured 2026-09-22** (premise correction of `.todo/924`, which said the compiled
  `(intern "T")` was not `t`): `(eq (intern "T") t)` was already T on JVM, WASM and the
  component; what diverged was NIL (`(intern "NIL")`, `(find-symbol "NIL" "CL")`, a computed
  `find-symbol`, `do-external-symbols` over cl and `find-all-symbols` yielding a non-nil `NIL`)
  and the literal 1-arg fold (`(find-symbol "T")` -> NIL). Size of the rail: a program that
  never interns is byte-identical (the WASM arm is emitted only under `usesIntern`); a
  program that interns pays the helper once on the JVM and the arm once on wasm, most of it
  the print path's nil branch the non-null `_intern_sym` had let the shaker drop. ANSI `packages` / `symbols`
  (interpreter): 0 fixed, 0 regressed -- `.todo/917` had already fixed every row the suite
  reads.
- `make-symbol` prepends the `#:` uninterned marker (same string twice = `eq` symbols, unlike
  CL).

**Interpreter**: the pure converters live in `Environment`, but `intern` is overridden in
`LispEvaluator.registerEval` (it needs the `packageResolver`);
`boundp`/`symbol-value`/`fboundp`/`find-symbol` live there too because they capture `globalEnv`
(variable lookups see GLOBAL bindings only — CL's dynamic-only semantics) and
`userMacros`/`SPECIAL_OPERATORS`. t/nil/keywords are self-bound in boundp/symbol-value on every
backend.

**Interpreter `fboundp` answers what a call would** (`LispEvaluator.fbound` ->
`loadFunction`, the lazy-load half `resolveFunction` shares): a probe of a library-defined name
LOADS it (a prelude defun, a uiop definition, a qualified library, a system generic, the
restart runtime), as the first call would. It does not take `resolveFunction`'s last step, the
`pkg::member` -> bare image-function fallback: a package's own symbol that only falls back is no
definition of it. Measured 2026-10-31 against SBCL 2.2.9's 636 external `cl` functions
(macros and special operators excluded): 223 NIL before (183 of them callable, e.g.
`alphanumericp`, `equalp`, `get`, `read`, which esrap's rule check warned about), 40 after --
exactly the unimplemented ones (`CL_EXPORTED_ONLY`: `add-method`, `boole`, `describe`, ...,
owned by `.todo/715`/`041`/`042`), the same 40 the compiled literal fold (`CL_FUNCTIONS`)
answers NIL for. `uiop:split-string`, `linalg:` and `appkit:` names were NIL before their first
call too. **Compiled computed probe** (data-held symbol, same 636): 163 NIL on the JVM before
2026-10-31 -- 123 implemented functions: the reference-gated / native / complex wrappers, the
restart runtime and the generated `find-class`/`allocate-instance`/`compile` defuns, all injected
only for a program spelling `#'name`, and the 28 `car`/`cdr` compositions, which compile inline.
Now 39 on the JVM, P1 and the component: the interpreter's 37 plus `provide`/`require`, literal
top-level only. The ten CL functions the expander rewrites in call position (`complement`,
`make-condition`, `make-sequence`, `documentation`, `proclaim`, `change-class`, `slot-value`/
`-boundp`/`-exists-p`/`-makunbound`; `BuiltinFunctionWrappers.INLINE_EXPANDED_VALUE_FUNCTIONS`)
had no function value on ANY backend until 2026-10-31 -- the interpreter's `fboundp` answered T,
but its `symbol-function` of seven of them was undefined. They are reference-gated catalog
wrappers now, so the interpreter's value is the same lambda: a computed slot name lowers to the
`%slot-*-runtime` dispatches (whose gates count `#'slot-value` and siblings), `make-sequence`
classifies its computed type at run time, and `make-condition` / `change-class` forward to the
plist dispatches `%make-condition-runtime` (generated over every registered condition class for a
program naming `#'make-condition`) and `%change-class-runtime`, which the interpreter defines as
the literal call over quoted arguments. Measured: all ten answer T and run, `#'` and data-held, on
all four (`BoundpFunctionValueFixture`). The reach
(`FunctionDesignators.dataCalledNames`): a program that can turn data into a call (a computed
`funcall`/`apply`/designator argument, a computed `fboundp`/`symbol-function`/`fdefinition`, or
one of those taken as a value) and spells such a name inside a `quote` gets a top-level
`(function name)` appended by `normalizeBuiltinDesignators`, so every wrapper gate and every
scan pairing a runtime with a wrapper body sees the spelling it already reads; a composition
instead gets a named `(lambda (x) (cadr x))` wrapper (`FunctionValueWrappers.generate`), so a
program spelling `#'cadr` keeps its inline lambda. A quoted name an operator reads as a type,
class, condition or slot (`(typep c 'error)`, `(slot-value o 'log)`) does not count. A program
that does not do both is unchanged. Measured with all 101 names in one program's data: compiles
and answers T on all four. Cost on the ci-spec corpus class (2026-10-31): 63,363 constant-pool
entries against 63,348 with the reach off (16 names in its data). Pinned by `BoundpFunctionValueFixture` (all four, `aref`/`cadr` in data),
`FunctionDesignatorsTest` and `LispEvaluatorTest#fboundpChecksFunctionsMacrosAndSpecialForms`.

**JVM** (`JvmSymbolApiCompiler`): symbol-name = the princ-to-string emission; intern/make-symbol
= quote-strip `substring(1, len-1)` (+ `"#:".concat`); boundp, and symbol-value of a name no
special has, read the `_genv` mirror via `_envLookup` (binding pair `Object[2]`, value = index 1;
unbound throws `The variable X is unbound`) -- a special's value comes from its variable
(`.kb/dynamic-special-variables.md`, "One home"), and the bound-ness of one declared without a
value too ("Bound-ness of a special without a value"); computed fboundp probes `_fenv` then
`_lookup`. All three are in
the `usesEval` force list, which also turns on the top-level `_store` mirroring.

**WASM** (`WasmSymbolApiCompiler` + `WasmSymbolApiRuntimeBuilder`): five always-present unary
helpers `FUNC_MAKE_SYMBOL`..`FUNC_FBOUNDP` (type `TYPE_CALLABLE_BASE`, appended before
`FUNC_USER_BASE` like gensym); symbol-name reuses `FUNC_PRINC_TO_STR`. **`_intern_sym` interns
the content range verbatim through the reader runtime's `_intern` so the result's string-table
offset matches literals in the offset-based `_env_lookup`/`eq`**; a `usesIntern` gate
(`usesRead || program uses intern`) emits the real `_intern` body + blob without the rest of the
reader. Unbound symbol-value traps (`unreachable`, the `%error` convention).

**`_intern` is a hash table, not a scan** (`WasmReadRuntimeBuilder.buildInternBody`):
open addressing over `(offset, length)` slots keyed by the token bytes (FNV-1a, linear probing,
load under 1/2). The `RT_INTERN_BASE_ADDR` cell holds the table's address; the
`RT_INTERN_COUNT_ADDR` cell is 0 before the first call and 1 + the runtime-added symbols after,
and the slot count is DERIVED from it and the compile-time row count (`internTableSlots`), so
the table has no header. The first call fills the initial table -- reserved at the seeded
base, `max(RT_INTERN_REGION_SIZE, slots * 8)` -- from the compile-time rows, skipping the
zeroed rows the shaker cut; a miss pools the token, and at half load a doubled table is carved
permanently off the heap and rehashed. Every permanent heap advance changes the count, which
the per-call reset guards (`cabi_post_*`, the serve adapter) depend on. History: the scan cost
O(table) per call, and the runtime records lived in a small fixed region, so a fresh
symbol past the region's capacity overwrote the heap's first pooled token (out-of-bounds trap on P1 and component).
Measured 2026-10-04 (jar): long package walks and `apropos-list` drop from seconds to a
fraction; ci-spec `runtime-package-api` halves per WASM leg (and drops further once the
eval mirror stopped recording `%mv-spill`, `.kb/eval-runtime.md`). Size: a module without the real `_intern` is byte-identical; one with it carries the
table's few hundred bytes. Pinned by `WasmLispCompilerIntegrationTest#runtimeInternKeepsEverySymbolPastAThousand`:
every symbol past the old region, and the cost as a wasmtime FUEL budget (32,000 fresh
interns, then 100,000 of the last: 280 M measured 2026-10-23 against 1.2 G, where the scan
is 3.2e9 row compares).

**Compile-path folds and limits**: `find-symbol` requires a literal string and matches its
VERBATIM name against `isClMemberName` (every name cl exports, implemented or not -- `T`,
`NIL`, `DEBUG` included, as the interpreter answers) + keyword + Pass-1 `userDefunNames`
(`LispMacroExpander.foldLiteralFindSymbol`, the same arms as its status); a literal `(fboundp 'x)`
folds with full knowledge, a computed one sees functions only. `#'symbol-name`/`#'intern`/
`#'make-symbol` have wrappers; find-symbol/boundp/fboundp/symbol-value/set have reference-gated ones (`#'boundp` / `#'fboundp`: `unary`, injected only for a program that names them, so the wrapper body is the computed probe and counts as one for `boundpProbes`); fmakunbound has none.

### Is identity-by-name stable? Three persisting costs
Nothing on the roadmap forces a redesign (everything CL hangs off a symbol object works as a
name-keyed side table; macro hygiene rests on `gensym`'s counter). Costs: **WASM
canonical-offset discipline** — env lookup and `eq` compare string-table offsets, so EVERY
future primitive that builds a symbol at runtime must route the bytes through `_intern` (reuse
the `usesIntern` gate + `_intern_sym` rail) or it princs correctly and fails lookups/`eq`;
**true uninterned identity is unrepresentable** (`(make-symbol "x")` twice is `eq`, and
`copy-symbol` inherits that — code wanting a name nobody else uses wants `gensym`; an
uninterned symbol `import`ed into a package cannot gain that home, ANSI `import.5`); and
**`unintern` cannot produce a DISTINCT symbol**: it is implemented (`.todo/917`) as a member
removal plus a tombstone that makes `symbol-package` answer nil, but interning the name again
homes the OLD symbol again where CL mints a new one. `.todo/917` weighed a real intern table
against this model and kept the model (`.kb/packages.md`, "The member table"):
**re-evaluate only when a consumer needs symbol OBJECTS**, not for a conformance count.
plump (2026-10-30) was not one: it needed MEMBERSHIP (a name it only read, found by
`find-symbol`), which the member table answers once the reader records into it.

## A definition IS an interning: the find-symbol namespace probe
- **Interpreter `find-symbol` probes the global namespaces under the canonical spelling**
  (`LispEvaluator.definedInImage`: user macros, functions, global bindings) as one arm of
  `PackageResolver.accessible` -- after the member table's own/imported arms, before the
  inherited one (`LispEvaluator.findSymbolAccessible` passes the probe; both arities go
  through it, the 1-arg form against the current package). The probe still covers the names
  a definition DERIVES (a `defstruct`'s accessors); a symbol that was only READ is a recorded
  member now, on the interpreter and in the compile paths' macro-time evaluator
  (`.kb/packages.md`, "The member table", the reader of a live image). `nil` is a SYMBOL to
  `fboundp` now. Pinned by `LispEvaluatorTest#findSymbolSeesSymbolsReadOrDefinedInsideAUserPackage`;
  the COMPILED 2-arg lowering keeps its unknown-name-yields-a-symbol deviation for
  read/compile-time packages, a read-only name included.
- **`#'find-symbol` is a reference-gated wrapper** (`BuiltinFunctionWrappers.findSymbolWrapper`)
  dispatching on argument count onto the two call-position lowerings; gated because the body
  lowers to `intern` and `usesIntern` counts a `find-symbol` reference.
- **A RUNTIME `(export ...)`/`(unexport ...)` call** compiles to its arguments evaluated for
  effect plus `t` (`LispMacroExpander.expandRuntimeExport`): the compiled registry is frozen.

## Runtime (COMPUTED) package/symbol operations
**`fmakunbound`: the name becomes call-time-undefined again.** Interpreter — the global function
binding AND any user macro of the same name are dropped (`Environment.undefineFunction` +
`userMacros.remove`). Compiled backends — a **TOMBSTONE** is installed in the eval runtime's
function namespace (`_fenv` on the JVM, `GLOBAL_FENV` on WASM): a binding whose value cell is
nil. That namespace is probed BEFORE the compiled-function registry, so it shadows it, and
`fboundp` had to learn the same rule (a binding decides the answer on its own instead of merely
existing).

**`(fboundp 'x)` stops being a bare constant when the program calls `fmakunbound`**: the fold is
emitted BEHIND the tombstone probe (`Ctx.usesFmakunbound`;
`JvmSymbolApiCompiler.emitTombstoneGuard`, `WasmSymbolApiCompiler.emitTombstoneGuardedFold`).
A program without `fmakunbound` is byte-identical to before.

**The divergence**: a call site the compiler already bound directly keeps working after
`fmakunbound` — eager compilation cannot be undone, so only LATE-bound references see the
retirement. `symbol-function`/`fdefinition` of a LITERAL name are folded the same way and are
likewise not tombstone-aware.
**A SYMBOL reaching a dispatcher is one of those late-bound references**: the dispatcher's
symbol arm probes the function namespace before `_lookup`, the order `symbol-function` and
`_apply` use, and a binding decides on its own -- its function replaces the symbol (an
interpreted closure then takes the `_apply` arm), the tombstone reports the SYMBOL as
undefined although the registry may still answer it. JVM: `JvmRuntimeBuilder.FunctionNamespace`
/ `emitNamespaceProbe`; WASM: `emitDispatchPrologue`'s `probesNamespace` (the `$undefined` arm
in EH mode, a trap outside it). `_apply`'s own probe hands a tombstoned SYMBOL on instead of the
nil its cell holds (JVM `_notFn`; WASM the spread dispatcher, which probes and reports it), so
the report names it. Gated on `writesFunctionNamespace` (every eval-runtime reason except the
four readers `boundp`/`symbol-value`/`set`/`fboundp`), so a program that cannot write the
namespace is unchanged. Before (measured 2026-10-06): `(funcall (intern "F"))` after
`fmakunbound` called `F` on the JVM, P1 and the component, a name only `eval`'s `defun` or a
computed `(setf (symbol-function ...))` bound was undefined there, and `(apply (intern "F")
nil)` reported `The function NIL is undefined`. Pinned on all four backends by
`RuntimeFunctionNamespaceCallFixture`.

### `(setf (symbol-function 'f) fn)` / `(setf (fdefinition 'f) fn)`
`expandSetf` lowers both places to `(%set-symbol-function name value)` (the CL internals
`%SET-SYMBOL-FUNCTION`/`%FENV-FUNCTION` are in `CL_INTERNALS`).

- **Interpreter**: a builtin over `Environment.defineFunction` + `userMacros.remove`. **JVM**
  (`compileSetSymbolFunction`): mutate the existing `_fenv` cell, else prepend a binding.
  **WASM**: `FUNC_SET_SYMBOL_FUNCTION` (`WasmSymbolApiRuntimeBuilder.buildSetSymbolFunction`),
  same over `GLOBAL_FENV`. Both force `usesEval` via
  `LispMacroExpander.usesSymbolFunctionWrite`, which scans the RAW setf place shape — the
  lowering happens per expression, after the gates.
- **The alias idiom needs a defun to call**: a name bound ONLY by the setf has no compiled
  function, so `expandTopLevelDefinitions` injects one FORWARDER defun per such name
  (`setfOnlyFunctionAliasNames` + `symbolFunctionForwarderDefuns`):
  `(defun NAME (&rest args) (apply (%fenv-function 'NAME) args))`. **`%fenv-function` probes the
  function NAMESPACE ONLY** — probing the compiled registry would find the forwarder itself and
  loop — and a miss (no binding, or the tombstone) signals the undefined-function a direct call
  of an undefined name signals: JVM `emitUndefinedFunctionThrow`, wasm inline
  (`WasmFunctionFormCompiler.emitFenvRead` -> `emitUndefinedFunctionSignal`; it was a fixed
  `_fenv_function` runtime that trapped until 2026-10-07).
- **The forwarder is no definition of the name**: it serves direct call sites only.
  `LispMacroExpander.symbolFunctionForwarderNames` recognizes it in the expanded program, and
  each backend (`Ctx.fenvForwarders`) compiles `#'NAME` and the literal `symbol-function` /
  `fdefinition` (both via `compileNamed`) as the `%fenv-function` read, takes a literal
  `(fboundp 'NAME)` down the computed probe, and keeps the forwarder out of the name registry
  (its name joins `callOnlyRuntimes`), so a computed designator, `symbol-function` or `fboundp`
  reaches `_fenv` / `GLOBAL_FENV` and then misses. Before (measured 2026-10-07, all of these
  before the setf ran): JVM `#'NAME`, the literal and computed `symbol-function` answered the
  forwarder (`#<function NAME>`, never `eq` to the installed function afterwards) and both
  `fboundp` spellings T; P1 and the component trapped on every call. The interpreter and SBCL
  signal / answer NIL. The eval runtime's named application (`_eval`, both backends) learns the
  forwarder names too (`EvalConstants.fenvForwarders` / `buildEvalBody`'s list): with no
  `_fenv` binding such an operator is reported undefined -- registry-less, it would otherwise
  fall to the unknown operator's silent nil (`.todo/384`, which stays open for other names;
  JVM `(eval '(NAME 1))` answered NIL there for a day, P1 and the component had trapped). A
  tombstoned binding (any name `fmakunbound` retired) is reported the same way, naming it: it
  used to apply the nil cell, `undefined-function` with `cell-error-name` NIL. The report is
  `_apply` of the operator SYMBOL and no arguments, before any argument form is evaluated.
- **A nested defun a run-time name can reach takes the same path** (`Ctx.fenvForwarders` holds
  its name too, its definition is the `%set-symbol-function`): `.kb/core-representation.md`, "A
  nested defun a RUN-TIME name can reach".
- **Divergences**: an eagerly-bound call site of a name that HAD a defun keeps the old function
  after a re-setf; `--no-gc` has no eval runtime.
- Tests: `LispEvaluatorTest#setfSymbolFunction*`/`#setfFdefinition*`,
  `JvmLispCompilerTest#compileAndRunSetfSymbolFunction*`,
  `WasmLispCompilerIntegrationTest#setfSymbolFunctionAliasAndRedefinition`, ci-spec
  `setf-symbol-function-and-fdefinition`; `SetfSymbolFunctionReferenceFixture`
  (`aNameOnlySetfSymbolFunctionBindsIsUndefinedUntilTheSetfRuns` in the three backend suites).

### A `(setf name)` function name
`fdefinition`, `fboundp`, `fmakunbound` and `(setf (fdefinition ...))` take a `(setf name)` list
as well as a symbol (`symbol-function` does not: SBCL signals a type-error). The writer is stored
under `%setf-NAME` (`LispMacroExpander.setfFunctionName`), so the list maps onto that name.

- **Interpreter**: at run time, any list (`LispEvaluator.functionName`); `fmakunbound` answers the
  list.
- **Compile paths**: a QUOTED list only, rewritten in `FunctionDesignators.normalizeBuiltinDesignators`
  (both backends, before every gate): `(fdefinition '(setf n))` -> `#'(setf n)`, `(fboundp '(setf
  n))` -> `(fboundp '%setf-N)`, `(fmakunbound '(setf n))` -> `(progn (fmakunbound '%setf-N) '(setf
  n))`, the place `(fdefinition '(setf n))` -> `(fdefinition '%setf-N)` -- so the setf-only alias
  forwarder serves it (`setfOnlyFunctionAliasNames` also counts `(defun (setf n) ...)` as a
  definition of `%setf-N`). A miss reports `(SETF N)` through `ClosRegistry.functionNameForReport`;
  the JVM's `%fenv-function` miss now throws the static text for that (it concatenated the
  symbol at run time, so a retired forwarder name reported `|%setf-N|`).
- **Compile paths, a list built at run time** (`compiler/RuntimeFunctionNames`): three prelude
  helpers, `%function-name` (a `(setf place)` list -> `(%setf-function-symbol place)`, anything
  else itself), `%fdefinition` (an unbound `(setf place)` -> `(%undefined-setf-function place)`,
  the backend's own undefined-function signal naming the list) and `%fmakunbound` (answers the
  name given). The expression compilers of both backends call them in place of the operator
  for a COMPUTED argument (`mayBeComputedName`: not quoted, not `#'`, not a constant, not an
  `intern`/`find-symbol`/`make-symbol`/`gensym`/`gentemp` call) where the program defines the
  helper: `fboundp` and `%set-symbol-function` (so `(setf (symbol-function x) fn)` too, as the
  interpreter) wrap the name in `%function-name`, `fdefinition` / `fmakunbound` become the
  helper call; a `(%function-name ...)` argument is never wrapped again, which is how the
  helpers' own bodies call the operators. `#'fboundp` / `#'fdefinition` / `#'fmakunbound`
  bodies are the computed call, so they map too. `%setf-function-symbol`: JVM
  `"%setf-".concat(place)`, wasm the prefix and `_str_to_mem` of the place in the heap scratch
  through `_intern` (so it joins `usesIntern`).
- **Selection** (`RuntimeFunctionNames.sites`, the prelude's surface fact and a
  `LibraryDefunPruner` synthesized root): a computed site of one of the operators (or the
  operator as a function value), AND a way for the run time to hold `SETF` as data -- quoted
  (backquote reads as quote; the argument of `(fboundp '(setf n))` excluded, it is mapped at
  compile time), a `"SETF"` string with a symbol builder, or a data evaluator
  (`RuntimeNameProducers`). Otherwise nothing is spliced and the program compiles as before
  (checked by `cmp` on a computed-`fboundp` program, `.class` / P1 / component, 2026-10-07).
- **Reachability**: in a program that defines `%function-name`, a `%setf-PLACE` function gets
  a registry row wherever PLACE is spelled (`DesignatorSpellings.matched`'s `setfNamesBuilt`,
  both backends), and every writable defstruct slot gets its writer defun
  (`referencedStructWriterDefuns`). A place assembled out of computed pieces is the
  `RuntimeNameProducers` carve-out.
- **A name only the run time binds is looked up when it is used.** The quoted spelling gets the
  setf-only forwarder; a computed one cannot be known at compile time, so where the program can
  bind such a name -- `Ctx.bindsRuntimeFunctionNames` = `eval` || `load` ||
  `LispMacroExpander.writesComputedFunctionName` (a `symbol-function` / `fdefinition` place whose
  name is not a quoted symbol or `(setf n)` list; `fmakunbound` and quoted writes excluded) --
  the three late-binding arms of a name no definition has read the namespace instead: the direct
  call compiles to `runtimeFunctionNamespaceCall`, `(funcall (%fenv-function 'NAME) arg...)` (the
  read, so a miss signals, ahead of the arguments, as the interpreter's direct call does), `#'NAME`
  / literal `symbol-function` / `fdefinition` to the `%fenv-function` read, a literal
  `(fboundp 'NAME)` to the computed probe, warning `looked up when the call runs` / `... the
  reference runs` instead of `compiled as a ... error`. Every other program compiles as before.
  Measured 2026-10-07 (SBCL 2.2.9 / interpreter answered; JVM, P1, component before): a direct
  call, a place, `#'` and `fboundp` of a name installed through `(list 'setf p)`, `(intern s)` or
  `eval`'s `defun` were the call-time undefined-function (a trap on wasm outside EH mode) / NIL.
  Pinned by `SetfFunctionNameFixture.COMPUTED_INSTALL` (`aFunctionANameBuiltAtRunTimeInstallsIsCalledByName`
  in the three backend suites), the gate by
  `LispMacroExpanderTest#onlyAFunctionNamespaceWriteThroughAComputedNameCanBindANameTheCompileCannotSee`,
  the warnings by `RontoLispCliStreamsTest#anUndefinedNameAProgramCanBindAtRunTimeWarnsThatItIsLookedUp`.
- Measured 2026-10-07 (SBCL 2.2.9 / interpreter / JVM / P1 / component): before, `(fdefinition
  '(setf n))` was `FDEFINITION expects a symbol` / `ClassCastException` / trap / trap; a list
  built at run time, after that change, `(1 2)` / `(1 2)` / `ClassCastException` / `Not a
  function: (SETF N)` trap / the same trap, and the same for `fboundp`, `fmakunbound` and
  `(setf fdefinition)`.
- Pinned by `SetfFunctionNameFixture.DESIGNATOR` (`theFunctionNameOperatorsTakeASetfFunctionName`
  in the three backend suites) and `.COMPUTED_DESIGNATOR`
  (`theFunctionNameOperatorsTakeASetfFunctionNameBuiltAtRunTime`, P1 and the component in the
  wasm one); the selection by
  `LispPreludeLibraryTest#theRunTimeSetfFunctionNameHelpersAreSplicedOnlyWhereSuchANameCanReachAnOperator`;
  the interpreter's refusal of a list that is no function name by
  `LispEvaluatorTest#theFunctionNameOperatorsTakeAComputedSetfFunctionName`.

### `set` / `(setf (symbol-value name) value)` for a computed name (`.todo/852`)
`expandSetf` lowers the place to `(set name value)` -- one store, evaluated once each
side, answering the value -- so only `set` needs the per-backend work.

- **Semantics, all four backends** (CL's, 2026-10-04 `.todo/c86`): the CURRENT dynamic
  binding when one is active -- a `let`, a parameter or `progv` made it, the name
  declared or not -- exactly what `setq` assigns; otherwise the global, creating the
  binding when the name is unbound (what a run-time evaluator defines program globals
  through -- and a deliberate creation where CL's `set` signals). Constants (nil, t and
  keywords, by value or by computed `NIL`/`T` name, and the empty name) and non-symbols
  signal on the interpreter and the JVM and trap on WASM, the symbol API's usual split.
  Before, the backends answered three ways (`(let ((*dv* 2)) (set '*dv* 3) (list *dv*
  (symbol-value '*dv*)))`, then `*dv*`): interpreter `(2 2)` / 3 (it defined the global
  under its binding), JVM `(2 3)` / 3 (`_g$` beside the active `_d$` cell), wasm `(3 3)` /
  1 -- shallow binding: the module global IS the binding -- but `(symbol-value '*dv*)`
  after the extent answered 3, from the mirror.
- **Interpreter**: a builtin: `*package*` through `assignCurrentPackage` (setq's arm),
  an active `DynamicBindings` entry through `setCurrent`, else `define` on the global env.
  **Compilers** (`LispMacroExpander.expandSetForCompile`, 2026-10-04 `.todo/c81`): a site is
  one call to the shared `%set-global` (`setGlobalRuntime`): `%set-mirror` validates and
  writes the `_store` / `FUNC_STORE` mirror (which creates the binding when the name has
  none), then the store half of the shared accessor `%global-access` dispatches the name over
  the globals onto `%global-store-set` -- the store a non-lexical `setq` of the name makes: JVM
  `JvmSetqCompiler.emitGlobalStore` (`_dset` into the thread's active cell of a
  dynamically-bound special, falling to `putstatic _g$`), wasm `global.set` (the binding
  under shallow binding; `--reentrant`: `WasmDynVars.emitWrite`, the task record's cell
  first) -- segmented and
  tested like the progv dispatches (`.kb/dynamic-special-variables.md`: `%symbol-is`, wasm by
  canonical string-table offset, so a literal and a run-time `intern` agree). Mirror before
  field is unobservable: no global is a constant or a non-symbol. Injected when a form calls
  `set` with two arguments or writes a `symbol-value` place through `setf` or a modify macro
  (`push`/`pushnew`/`pop`/`incf`/`decf`/`remf`/`psetf`/`rotatef`/`shiftf`) --
  `programUsesSet`. `symbol-value` reads a special through its variable in every program
  (`.kb/dynamic-special-variables.md`, "One home"), so it answers the binding a `set` wrote,
  not the mirror. A variable NAMED `set` does not count: a `let`
  binding or lambda-list entry of it is walked for its init forms only (`callsSet`). Until
  2026-10-04 one did (the walk took any list headed by `SET` for a call): the "dead `set`
  site" `.todo/c81` found in the ningle examples was one. Without it the same `let` + checks + chain is spelled at the site
  (JVM over `ctx.globals`, wasm over the sorted global names) -- kilobytes of JVM bytecode a
  site over the corpus's globals. Both force `usesEval` (a `SET`
  spelling; a raw `(setf (symbol-value ...) ...)` spells `SYMBOL_VALUE`, which is
  already in the chain), and `#'set` is a reference-gated wrapper like
  `#'symbol-value`. `--no-gc` refuses by omission, like every other eval-runtime
  operator.
- **The `boundp` soundness gate gains an arm** (`CompileTimeBoundp`): a program calling
  `set` -- or spelling the raw `symbol-value` place (`usesSymbolValueWrite`, the
  lowering happens per expression, after the gate) -- can make a global appear at run
  time, so the whole fold is off.
- A direct reference to a name only `set` created does not compile (like an
  eval-created global); the program reads it through `symbol-value`/`eval`. Inside a
  compiled runtime `eval`, `set` is an unknown operator and answers nil, like every
  other inline-only operator (`boundp`, `symbol-value`).
- Cost (2026-10-04): size-report, bench-report and the ci-spec program: byte-identical on
  wasm and component; the JVM grows only where a progv program's dynamically-bound specials
  take the `%global-store-set` arm (a `_dset`
  call and a branch instead of a bare `putstatic`). Examples: byte-identical but
  for build-info strings, except the six whose libraries bind a variable named `set`
  (the three ningle ones, cloudflare httpbin, roman, minesweeper-core-test): the phantom
  `set` site is gone and they shrink a little on wasm. Computed `set`s of a bound special
  run in the same time before and after (the mirror's `_store` walk dominates).
- **The mirror is still written inside an active binding** (`%set-mirror` runs first,
  as a callee's `setq` mirrors). No read takes a special's VALUE from it (`symbol-value` and
  `eval` read the variable), and `boundp` of a special declared without a value reads its
  variable too (`.kb/dynamic-special-variables.md`, "Bound-ness of a special without a
  value"), so a `set` inside a binding leaves it unbound after the extent.
- Tests: `LispEvaluatorTest#set*`, `JvmLispCompilerTest#compileAndRunSet*`,
  `WasmLispCompilerIntegrationTest#set*`, `CompileTimeBoundpTest`
  (the gate arm), `BuiltinFunctionWrapperCatalogTest` (the `#'set` value), ci-spec
  `set-and-setf-symbol-value-for-computed-names`. The active binding:
  `SetInDynamicBindingFixture` (a `let`/parameter program, a modify-macro-only program, a
  `progv` program; SBCL's answers) on `setWritesTheActiveDynamicBinding` in
  `LispEvaluatorTest`, `JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest`
  (Preview 1 and component), ci-spec `set-writes-the-active-dynamic-binding`,
  `WasmReentrantE2eTest#overlappedCallsEachSetTheirOwnDynamicBinding`;
  `LispMacroExpanderTest#aVariableNamedSetIsNoSetSite` /
  `#aSetCallOrASymbolValuePlaceWriteIsASetSite`.

### Computed `find-package` is answered from a BAKED table
A computed designator lowers to `(cdr (assoc (string x) '(("CL" . :CL) ...) :test #'string=))`,
built by `LispMacroExpander.expandRuntimeFindPackage` from
`PackageResolver.runtimePackageTable()` — read AFTER `resolveProgram`, so it covers every
`defpackage` — and threaded to both backends as `Ctx.packageTable`. Keys match VERBATIM and the
table carries both the registered spelling and its uppercase form. Pure AST, no per-backend
codegen. **Divergence**: the table is frozen at compile time, so a package a compiled program
creates later is invisible. The three registry QUERIES (`list-all-packages` /
`package-use-list` / `package-used-by-list`) are frozen the same way from
`runtimePackageUseTable()` (`.kb/packages.md`).

### Computed `find-symbol`
A computed NAME lowers to `(intern name)` (`LispMacroExpander.computedFindSymbol`) — interning
IS the lookup — carrying the unknown-name-yields-a-symbol deviation. A computed PACKAGE
designator builds the spelling through a runtime test of the three packages whose members carry
no qualifier (`computedQualifiedSpelling`: `keyword` -> `:NAME`, `cl`/`cl-user` -> bare,
anything else -> `PKG:NAME`, or `K::NAME` under the found package's own name when
`%program-package-p` says the program defines it); unconditionally prefixing `(string PKG)`
built `KEYWORD:X` instead of the keyword `:X`. Before either build, a package the registry knows to reach the name
another way answers it (`%baked-access`, `.kb/packages.md` "Compiled lookups answer through
the registry"), and a standard name a package using `cl` inherits answers the `cl` symbol
(`.kb/packages.md`, "The standard names at run time").

**A designator naming no package signals a `package-error`, as `intern`'s does** (`.todo/997`;
nil until then). `nil` designates the package `"NIL"`, which no image has, so it signals too.
Interpreter: `findSymbolAccessible`, shared by `find-symbol` and `%find-symbol-status`, so the
multiple-value lowering signals once. Compile paths: a LITERAL designator the baked table lacks
lowers to the `%package-error` stub (behind the runtime table when `usesRuntimePackages`); a
COMPUTED one is guarded by `(find-package p)` (`computedGuardedSpelling`, shared with
`intern`) -- before that the computed path built `NOPKG:X` for any non-nil designator, a
cross-backend divergence the interpreter's nil hid.
Survey before the change (2026-09-26):
every shipped-library and corpus probe of an optional package already guards on
`find-package` (postmodern's json-encoder, alexandria's `sequence-emptyp`,
trivial-with-current-source-form, esrap, dissect, local-time), sits behind a reader
conditional for another implementation (jzon `#+ecl`, ironclad/trivial-garbage `#+sbcl`,
swank), or errors on nil anyway (lack's `locate-symbol`, flexi-streams' version check); the one
unguarded shipped caller, `format-render-slash.lisp`'s `~/pkg:fn/` resolver, now guards. ANSI
`packages` + `symbols` chapters unchanged. The optional-system probe is
`(and (find-package p) (find-symbol n p))`.
- **A computed-designator site is one call to the `%symbol-in-package` prelude defun**
  (`computedPackageLookup`; its body IS `computedGuardedSpelling`, printed by
  `symbolInPackageDefinition`, so the two cannot drift). Selected by the surface fact
  `callsWithComputedPackageDesignator`; a site the selection cannot see keeps the inline form
  (`ctx.functions::containsKey`, the `%make-array-et` pattern). Why: the guard inline is a
  computed `find-package` -- the baked table is a quoted constant BUILT AT EACH SITE,
  kilobytes of JVM code -- plus the package-error, per site in all; the runtime-package path
  (`usesRuntimePackages`) cost more still, and `JvmLispCompilerTest#compileAndRunRuntimePackageMemberTable`'s
  many-site top method crossed the 64 KB method limit. With the helper (2026-09-26) a site
  is one call, on either path. The first site of a small program pays the
  helper's `string-upcase` (the package-error's keyword) and `intern`, the same as a
  computed `intern` already cost after `.todo/996`. The guard's `find-package` is itself one
  call to `%find-package` now (`.kb/packages.md`, after "Divergence").

Tests: the *Fmakunbound* / *FindPackage* / *FindSymbol* groups in the three backend tests
(`findSymbolInAMissingPackage*` for the signal), the `runtime-package-symbol-ops` and
`find-symbol-in-a-missing-package` ci-spec cases.

## The standard stream variables are bound in the MIRROR too
A compiled program keeps a special in **two homes that did not know about each other** — the
per-name global field (JVM) / module global (WASM) a direct read uses, seeded with the stream
defaults, and the `_genv` / `GLOBAL_ENV` mirror `symbol-value`/`boundp`/`eval` probe, which every
store to a global writes (b78 -- a top-level assignment only, before). Both now seed from ONE table,
`compiler.StreamDesignators.standardStreamDefaults()` (`*standard-output*`/`*standard-input*` ->
the `t` designator, `*error-output*` -> the reserved handle `2`).

- **JVM**: `<clinit>` emits `_genv = new Object[]{new Object[]{name, default}, _genv}` — the
  binding shape `_store` prepends, so a later top-level assignment MUTATES that cell instead of
  shadowing it. **WASM**: `_start` emits `GLOBAL_ENV = cons(cons(name, default), GLOBAL_ENV)`,
  the name through the string table so its offset is the one `_env_lookup` compares against.
- **Gate is per variable: `usesEval` AND the NAME APPEARS IN THE SOURCE** (`programUsesSymbol`,
  counting quoted data) — for byte-identity, and because it is the SAME scan the `--component`
  stderr narrowing uses (`.kb/standard-output-redirect.md`), so the mirror seed cannot
  materialize handle 2 in a component whose `wasi:cli/stderr` was pruned. **Limit**: a name the
  source never spells is still unbound. `symbol-value` and `eval` of one of them read its
  variable, an active `let` binding included (`.kb/dynamic-special-variables.md`, "One home").
- **Latent JVM bug this exposed**: `_writeString`'s socket probe indexes `_streams` from the raw
  handle BEFORE delegating to `_writeStr` where the stderr branch lives, and `_streams` was
  lazily created. `<clinit>` now allocates it with the reserved slots empty whenever
  `usesErrorOutput`. Do not make it lazy again.
- Tests: `LispEvaluatorTest#standardStreamVariablesAreBoundToTheirDefaultsThroughTheSymbolApi`,
  the `standardStreamVariables*` pair in the two compiler tests, the `symbol-runtime-api`
  ci-spec case, `LackEcosystemE2eTest#backtraceMiddlewareReportsTheApplicationsRealError*` and
  `ServeConditionCatchComponentE2eTest` (opt-in, `.kb/lack.md`).

## Runtime-interned symbols as function designators
Clack's handler protocol is late-bound by NAME, so the compile paths carry a full
symbol-to-function route (the interpreter resolves designators against the live environment):

- **2-arg `(intern name pkg)` lowers like 2-arg `find-symbol`**
  (`LispMacroExpander.expandInternInPackage`, from `Jvm/WasmSymbolApiCompiler.compileIntern`):
  `keyword` keeps the byte-identical keyword lowering; a literal `cl`/`cl-user` drops the
  qualifier; any other literal known package builds `(intern (concatenate "PKG:" name))`; a
  computed designator runs the same three-way `cond` as `computedPackageFindSymbol` (shared
  `computedQualifiedSpelling`). A designator naming no package
  SIGNALS (find-symbol's contract too since `.todo/997`) -- a LITERAL one as a
  call-time `(%package-error ':X "No such package: X")` stub, a COMPUTED one (nil included)
  behind a `(find-package p)` guard, since building the spelling unguarded CREATES the
  package (`.todo/996`: `(f "NOPKG")` answered `NOPKG:X`). `%package-error` lowers like
  `%file-error` (`lowerPackageError`): a typed `package-error` behind a landing pad, the plain
  `%error` channel otherwise. It is built during body compilation, after the condition
  scans, so `PACKAGE_ERROR_SITES` (`intern`, `find-symbol`) stands in for the tag in `conditionNarrowing`
  and wasm's `usedLayoutTags`. Pinned in ci-spec (`intern-into-a-missing-package`) and
  `{LispEvaluatorTest,JvmLispCompilerTest,WasmLispCompilerIntegrationTest}#internIntoAMissingPackage*`.
- **Alias rows for internal names in the `_lookup` registries**
  (`JvmEvalRuntimeBuilder.lookupSegments`, the registry-blob loop in `WasmLispCompiler`): the
  runtime-built spelling in a pre-seeded package, or in a program package whose row a site the
  scan missed did not carry, is the single-colon EXTERNAL one, so every registered `PKG::NAME`
  defun also answers to `PKG:NAME` — appended after the base rows, genuine keys win. (Before
  `.todo/h55` every build was single-colon, so these rows carried every internal designator.)
  Emitted only when the registry is (`usesRuntimeFunctionDesignator` / `usesEval`) **and only
  when the alias SPELLING can reach the run time** (a builder can assemble it, the reader can
  read it under `--dynamic`/`anyNameResolvable`, or this compile spells it). Pinned by
  `WasmLispCompilerTest.theRegistrysSingleColonAliasShipsOnlyWhereItCanBeSpelled`. **VARIABLES
  do not get the alias**: an internal special resolves through the `_genv` mirror only where
  the build spells it `PKG::NAME` (a program package, `.todo/h55`).
- **CLASS names get the same widening, at the LOOKUP**: every generated class-designator
  dispatch (`%find-class`, `%mop-make-instance`, `%allocate-instance`, the runtime
  `typep`/`subtypep` tables, the condition-class arm of `error`) matches BOTH colon spellings of
  a registered class. One helper builds them (`LispMacroExpander.addDesignatorSpellings`);
  ungated (`.kb/clos.md`).
- **`_apply`'s symbol miss is LOUD**: a designator resolving in neither `_fenv` nor the registry
  throws `The function X is undefined` (JVM `emitUndefinedFunctionThrow`) / traps (WASM). It
  used to return nil silently; the tree-shaker carve-out (`.kb/library-defun-pruning.md`)
  promises a loud failure there.
- **Computed `(symbol-function x)` / `(fdefinition x)` BOXES the resolved funcId as
  a function value** (`.todo/750`, fixed 2026-09-09): the JVM emits
  `Object[]{Integer funcId}`, WASM a `{funcId, null env}` closure struct -- exactly
  what `#'name` would have produced. `functionp` answers t, the value prints its
  registered name, `funcall` dispatches, and an undefined name signals at the
  `symbol-function` itself as an `undefined-function` (WASM: the arity-0 dispatcher's
  report, so a trap only where no handler can catch; a name `fmakunbound` retired throws
  through `_undefined_function` instead, since the dispatcher's registry lookup may still
  answer it and call the retired function), matching the interpreter and SBCL. `t`,
  `nil` and keywords are such names: the interpreter's `fboundp` / `symbol-function` /
  `fdefinition` / `funcall` accept the `t` and `nil` VALUES as the symbols they are, and the
  JVM maps a null designator to `NIL` before the registry sees it. The eval runtime's
  function namespace (`_fenv` / `GLOBAL_FENV`, where `(setf (symbol-function ...))`
  installs and `fmakunbound` leaves its tombstone) is probed first and decides on
  its own; otherwise the compiled-function registry (`_lookup`) answers. Only the
  function namespace is read -- a global VARIABLE holding a lambda is not a
  function binding (the interpreter and SBCL signal for it). The gate is
  `LispMacroExpander.usesRuntimeFunctionBox` (computed symbol-function/fdefinition,
  `#'symbol-function`/`#'fdefinition` -- whose injected wrapper body is the computed call --,
  literal-`'function` coerce, computed coerce): it keeps the registry live on both
  backends. The JVM probes `_fenv` only when the eval runtime exists
  (`Ctx.evalStoreRef != null`) -- every writer forces it, so without it the probe
  could only ever miss -- which keeps a boxing program without `setf`/`fmakunbound`
  at the registry's own size (measured 2026-09-09 on
  `(defun foo () 1) (print (symbol-function <computed>))` vs its literal twin: a few
  hundred bytes either backend, where the forced-`usesEval` shape tried first cost
  kilobytes of
  class). **Any future lowering that synthesizes a computed `symbol-function` at
  compile-expression time has the same obligation** as the `intern`/`funcall` one
  above: the pre-lowering spelling must count in that scan.
- **`uiop:symbol-call` is REAL on the compile paths**: `expandUiopStubCall` lowers it to
  `(funcall (intern (string name) (find-package pkg)) args...)` over two fixed `%UIOP-SC-*`
  temps keeping the package-before-name order. That lowering happens INSIDE the per-expression
  compilers, AFTER the emission-gate scans, so the pre-lowering spelling counts in
  `containsRuntimeFunctionDesignator` and the WASM `usesIntern`. **Any future lowering that
  synthesizes `intern`/`funcall`/`apply` at compile-expression time has the same obligation.**
  `#'uiop:symbol-call` is a VALUE too, so `uiop-package.lisp` carries upstream's own
  `(apply (find-symbol* name package) args)` beside the fold (`.kb/uiop.md`), and the dispatch
  gate probes `#:member` alongside `:member` (`compiler.DesignatorSpellings`,
  `.kb/optimize-dead-code-elimination.md`) since `'#:name` reaches the function through
  `(string '#:NAME)`.
- Tests: the *InternIntoALiteralPackage* / *InternIntoAComputedPackage* /
  *InternIntoAnUnknownPackage* / *ApplyOfAnUndefinedRuntimeSymbol* groups in
  `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`, and the `runtime-intern-funcall`
  ci-spec case.

## `find-symbol`/`intern` answer the ACCESSIBILITY STATUS as a second value
**The status is a SYNTACTIC second value, not a spill value** — the
`%array-disp-target`/`%array-disp-offset` pattern (`LispMacroExpander.lowerMvProducer`): a
`find-symbol`/`intern` producer lowers to the call itself plus a `%find-symbol-status` call over
the SAME argument temps, so every backend gets the second value from its own compile path and
nothing crosses a function boundary. `intern` RECORDS a fresh name in the member table, and
CL's second value is the status from BEFORE the intern (nil for a fresh name), so for `intern`
the status is bound FIRST and the call reads the temp (`find-symbol`'s two lookups are pure and
run in either order); the interpreter's `#'intern` value computes it first the same way
(`publishSecondValue(..., secondFirst)`).

**A literal argument is passed through, never bound to a temp.** Load-bearing: the compile paths
decide both values by folding the LITERAL name and the LITERAL package designator, and a
temporary hides both (binding them turned `CAR :INHERITED` into a runtime-built
`COMMON-LISP:CAR` with the can't-tell `:INTERNAL`).

- **Interpreter**: the status half of `PackageResolver.accessible` (`memberStatus` is a view
  of it), the same lookup as the value half — that is what makes the pair nil TOGETHER (CL's
  invariant). `cl` owns the standard symbols and exports all but the `%`-prefixed internals;
  `cl-user` uses `cl`, so a standard symbol read through it is `:inherited`; a recorded or
  defined name is `:internal`; anything else nil (`cl-user` used to provide every name).
  `%find-symbol-status` runs the same definition-IS-an-interning probe.

**The `cl` arm admits the standard names `cl` exports WITHOUT implementing** (CLHS 11.1.2.1, the
978): `PackageRegistry.isClMemberName`, not `isClSymbol`, in `memberSpelling`/`memberStatus` and
in the compile paths' literal fold. `(find-symbol "BIT-AND" 'common-lisp)` is `BIT-AND :EXTERNAL`
on all four backends while `fboundp`, `macro-function` and `special-operator-p` still answer nil
— exported is a different question from bound, and the resolver never asks the wider one
(`.kb/packages.md`, "The `cl` external list is the STANDARD's list"). The 1-argument form and the
`cl-user` arm stay on `isClSymbol`, so they still answer nil for such a name where CL answers
`:inherited` — the deviation below, deliberately not widened.
- **JVM + WASM**: one shared fold, `LispMacroExpander.expandFindSymbolStatus`, called from
  `Jvm`/`WasmSymbolApiCompiler.compileFindSymbolStatus` — a keyword or nil constant, never a
  runtime call. Anything it cannot decide reports the status of the SPELLING the lowering
  builds: `PKG:` -> `:external`, bare -> `:internal`.

Three lowering bugs it had to fix first, all on `(find-symbol "CAR" 'common-lisp)`: a QUOTED
bare symbol was read as a computed designator (the `quote` unwrap now carries a flag, since
under a quote a bare symbol IS a designator literal); a built-in package NICKNAME was not
canonicalized before the keyword/cl/cl-user arms (`canonicalDesignator` ->
`PackageRegistry.canonicalBuiltinName`); and `(find-symbol LITERAL 'cl)` took the
build-a-spelling deviation instead of the answer the compile paths can compute.

**A user package that uses `cl` answers a standard symbol it does not own as `:inherited`**
(CL's answer) since `.todo/917`: the inherited arm of `accessible` walks the use list, `cl`
included. The old note that widening this would hand sxql's `find-make-op` `CL:COUNT` was
about the compiled build-a-spelling path (the name probed there, `MAKE-COUNT-OP`, is no cl
symbol), which is unchanged; the tripwire stays
`MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends`.

`symbol-plist` and `remprop` came with it: prelude entries over the same `%symbol-plists` store
`get`/`(setf get)` use, each carrying its OWN copy of `(defvar %symbol-plists nil)` (`defvar`
assigns only when unbound, so a program pulling in both gets one table). There is no
`(setf symbol-plist)`; `remprop` answers `t`/`nil` where SBCL answers the plist tail. Pinned by
`LispEvaluatorTest#{rempropDropsOnePropertyFromThePlist,
findSymbolAnswersTheAccessibilityStatusAsItsSecondValue, symbolPlistReadsTheWholePropertyList}`,
the matching pairs in the two compiler tests, and the `symbol-plist-remprop` /
`symbol-runtime-api` ci-spec cases.

## `macro-function` / `special-operator-p` PARTITION the operators
**Invariant: every operator with no function value is either a special operator or a macro, on
all four backends, and the two predicates agree on which.** `special-operator-p` is t for
exactly the 25 ANSI special operators (`PackageRegistry.ansiSpecialOperatorNames()`);
`macro-function` is non-nil for everything else rontolisp expands —
`PackageRegistry.runtimeMacroNames()` is `specialOperatorNames()` minus those 25, plus the
program's own `defmacro` names. The split is deliberately NOT rontolisp's own
special-form/macro boundary (`defun`, `handler-case`, `dolist`, `lambda`, `in-package` are
special forms HERE and macros in CL): a caller is only asking "may I `apply` this name".
Verified form for form against SBCL 2.2.9.

**`while` is the ONE exception, and it is a name-space exception, not a partition one.**
`macro-function` answers NIL for it (`PackageRegistry.namesWithoutMacroFunction()` = the 25 ANSI
operators plus `while`, consulted by BOTH `runtimeMacroNames()` and
`LispEvaluator.isMacroName`), because iterate's `walk` asks `(macro-function (car form))` before
recognizing its own clauses and a yes made it refuse to walk `(iter ... (while test))`.
**General rule: a name CL does not have must not claim a macro function.** Nor does a CL
FUNCTION that sits in `CL_MACROS` only because the expander rewrites its calls inline
(`typep`, `format`, `error`, `signal`, `make-instance`, `slot-value`, ... --
`PackageRegistry.inlineExpandedClFunctions()`), nor `declare`: both join
`namesWithoutMacroFunction()`. iterate warned "a macro that won't expand" for every `typep` in
an `iter` body and skipped the clauses inside it (2026-10-30). SBCL 2.2.9 answers nil for all.

**`macroexpand-1` expands exactly what `macro-function` claims** (`LispEvaluator.macroexpand1`
gates on `isMacroName`, so the literal fold on the compile paths follows). A CL macro the
evaluator runs natively expands through `LispMacroExpander.expandNativeMacro`: the expansion
every backend already lowers it to (`prog`, `psetf`, `shiftf`, `with-open-stream`,
`do-symbols`, ...) or CL's own (`return` -> `(return-from nil ...)`, `lambda` -> `#'(lambda
...)`). It is kept out of `expandBuiltinMacro`, which also feeds compile passes that keep these
forms native. A walker EMITS what it gets, so the expansion must run on every backend.
`handler-case` and the definers have no lowering, so they expand to an internal form that
`LispMacroExpander.foldNativeExpansion` turns back into the native one:
`(%handler-case form 'type #'(lambda ll . body)...)`, `(%defun 'name #'(lambda ll [doc] [decls]
(block name . forms)))`, and `(%definition 'form)` for `defmacro`, `defvar`, `defparameter`,
`defconstant`, `defclass`, `defgeneric`, `defmethod`, `defstruct`, `defsetf`, `defpackage`,
`define-symbol-macro`, `in-package` (`QUOTED_DEFINERS`: their code sits in a grammar of their own
a walker cannot read). A walker sees the protected form, clause bodies and defun body as an
ordinary argument and lambda bodies (iterate's `walk-fspec`), and the rest as quoted data. The
interpreter folds in `rareOperatorExpansion`; the compile path in `UserMacroExpander`
(`expandAllLocated` and `expandTopLevel`, right after a user macro expands -- the only producer),
so no surface scan (`usesRestartSystem`, `needsSignalClauseMatch`, EH mode, `FreeVarAnalyzer`)
ever sees the internal head and the output is the native form's. CL's own `handler-case`
expansion (`handler-bind` + a `go` out of the handler lambda) was not taken: a `handler-bind`
anywhere puts the whole compiled program in restart mode (`.kb/error-handling.md`, "The
restart-mode gate"), and a cross-lambda `go` forces EH mode on WASM. The compiled `eval`
runtime does not fold them (it runs no `defvar`/`defparameter`/`defconstant` either,
`doc/en/guides/eval-limitations.md`).
Pins: `IterateE2eTest` (clauses inside `typep`/`handler-case`/`return`),
`LispEvaluatorTest#macroFunctionAndSpecialOperatorPPartitionTheOperators`, ci-spec
`macro-function-and-special-operator-p` (a macro emitting the expansions, four backends).

**One definition per predicate, shared by every backend, and no compile-time fold.** Both are
`LispPreludeLibrary` entries whose baked name table is GENERATED from `PackageRegistry` (so it
cannot drift from the expander). A literal `(special-operator-p 'if)` compiles to a call, not a
constant, unlike the `fboundp` fold — deliberately, since two paths that can disagree is what
this removes. **Re-evaluation trigger**: if a program is measured to care, fold the LITERAL case
in `LispMacroExpander` (both compile paths at once) — never per backend.

**The interpreter answers `macro-function` natively; the compile paths answer a name test.**
`LispEvaluator.registerEval` defines it over the macro table it holds, and the value is the REAL
expander: a `LispFunction` that macroexpand-1's the form after re-heading it with the macro's
own name (`macroCallForm`). A compiled program has no macro table, so `macro-function` answers
`#'%macro-expander-stub`: non-nil, and a signal when CALLED. The program's own macro names
cannot be in a static table, so `UserMacroExpander.emitMacroFunctionTable` APPENDS
`(defun macro-function (symbol &optional environment) (%macro-fn symbol '(names...)))` when the
program names `macro-function` at all, suppressing the prelude entry.

**The VALUE `macro-function` answers is a function of EXACTLY two arguments** (CLHS 3.2.1: the
form and the environment) — `macro-function` itself takes an optional environment for the
LOOKUP, but the expander it returns does not: `(funcall (macro-function 'name))`,
`(funcall (macro-function 'name) form)` and a call with more than two arguments all signal a
`program-error`, verified against SBCL 2.2.9 (2026-09-22; `.todo/922` split this off `.todo/917`
after `def-macro-test`'s middle probe read `got (T NIL T) want (T T T)` in 24 rows across the
suite). The interpreter's expander checks `callArgs.size() != 2` explicitly (it has no ordinary
lambda list to lean on — it is a raw `LispFunction`); the compiled stub gets the same answer for
free by DROPPING the `&optional` from `%macro-expander-stub`'s lambda list, so the ordinary
arity check every compiled function has already covers 0/1/4 arguments as a program-error before
the body's own "cannot expand at run time" signal is ever reached for the correct 2-argument
call (`ClosRegistry.arityMessage`, the same mechanism `defun`'s own mismatches use).

**Two load-bearing emission details, both because the BACKENDS resolve the program a second
time**: it is APPENDED (by the end every `defpackage` has been seen), and an unqualified name is
spelled `cl-user::name` (`CL-USER::X` canonicalizes back to bare `X` under any current package,
where a bare `x` would be re-qualified into the trailing `in-package`'s package).

`macroexpand-1`/`macroexpand` gained CL's `expanded-p` second value with it: the interpreter
publishes it through `%mv-spill` (`expandedWithFlag`; identity decides the flag), the compile
paths from `UserMacroExpander.expandAll`'s literal-argument fold.

**The two must not contradict each other.** For a COMPUTED `macroexpand-1` argument the compiled
prelude body consults `macro-function`: a macro call SIGNALS, anything else comes back unchanged
with `expanded-p` nil. Answering a macro call with itself leaves `macro-function` saying "macro"
while the expander makes no progress, and the standard
`(do ((step form (macroexpand-1 step))) ((not (macro-function (first step)))) ...)` loop spins
forever. Because those bodies read `macro-function`, a surviving `macroexpand`/`macroexpand-1`
call also triggers `emitMacroFunctionTable` (`usesMacroIntrospection`).
Mechanics: `.kb/gensym-macroexpand.md`.

The one shape that must NOT be walked as a call is
`(setf (macro-function 'new) (macro-function 'existing))` — a write to the macro table,
recognized syntactically (`.kb/defmacro-backquote.md`); `expandAll`'s `SETF` case returns it
verbatim.

Tests: `LispEvaluatorTest#macroFunctionAndSpecialOperatorPPartitionTheOperators` (incl. the
`while` leg) / `#macroFunctionIsTheRealExpanderOnTheInterpreter` (incl. the arity probes) /
`#macroexpand1AnswersTheExpandedPFlag`, the matching pairs in `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest` (incl. `{compileAndRun,compile}MacroFunctionValueArityMismatch`),
and the `macro-function` ci-spec case.

Function-count pins (ci-spec + LispEvaluatorTest + JvmLispCompilerTest x2 +
WasmLispCompilerIntegrationTest) move with each group: the symbol API added several, the
`fmakunbound` and `symbol-plist` groups one each.
