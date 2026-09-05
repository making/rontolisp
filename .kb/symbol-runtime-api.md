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
`string-designators-440` ci-spec case and the `stringDesignators` tests on all three engines.
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
- `symbol-package`: registry-backed on the interpreter; elsewhere a `LispPreludeLibrary` defun
  reading the qualifier off `prin1-to-string`, so it cannot tell `cl` from `cl-user`.
- `type-of`: a prelude defun over the internal `%class-designator` (NOT `class-of`, which
  answers a metaobject), stripping the `%struct-`/`%class-` tag prefix. It `intern`s the
  remainder, so it is right only because `PackageResolver.internSpelling` routes a qualifier the
  REGISTRY knows through `resolveQualified` instead of homing the whole string into the current
  package (which produced `APP::LIB:WIDGET`). Pinned by
  `LispEvaluatorTest#evalTypeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage`
  (+ twins) and ci-spec `type-of-foreign-package-class-name`.
- **2-argument `find-symbol`**: interpreter = registry-backed, returning the canonical spelling;
  on the compiled backends a symbol IS its canonical spelling, so `expandFindSymbolInPackage`
  BUILDS it (`(intern (concatenate 'string "PKG:" name))`, or the same over `(string PKG)` when
  computed). Two deviations: an unknown name yields a symbol instead of nil, and the qualifier
  is the single-colon EXTERNAL spelling. **The first is NOT harmless in the `find-symbol` ->
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
  (`.kb/packages.md`). The runtime package-mutation API is complete except for `unintern`, a
  NON-GOAL (below).

## The no-intern-table model
rontolisp symbols compare by name (no intern table), which shapes every deviation:

- `symbol-name` returns the STORED spelling **without the package marker**
  (`LispSymbol.displayName`, shared with `princ`/`~A`/`string`; `prin1`/`print` keep the stored
  spelling), upcased for every symbol read from source under
  `.kb/reader-case-upcase.md`.
- `intern`/`find-symbol` take the name VERBATIM: `(find-symbol "car")` = `NIL`,
  `(intern "time")` = the distinct `time`.
- 1-arg `intern` interns into the **current package** on the interpreter
  (`PackageResolver.internSpelling`, the `LispEvaluator` override; the `Environment` converter
  and the compiled backends stay package-blind). `(intern name :keyword)` builds a keyword; any
  other package argument goes through `PackageResolver.internSpellingIn`, which throws
  `No such package: X` on every backend.
- `make-symbol` prepends the `#:` uninterned marker (same string twice = `eq` symbols, unlike
  CL).

**Interpreter**: the pure converters live in `Environment`, but `intern` is overridden in
`LispEvaluator.registerEval` (it needs the `packageResolver`);
`boundp`/`symbol-value`/`fboundp`/`find-symbol` live there too because they capture `globalEnv`
(variable lookups see GLOBAL bindings only — CL's dynamic-only semantics) and
`userMacros`/`SPECIAL_OPERATORS`. t/nil/keywords are self-bound in boundp/symbol-value on every
backend.

**JVM** (`JvmSymbolApiCompiler`): symbol-name = the princ-to-string emission; intern/make-symbol
= quote-strip `substring(1, len-1)` (+ `"#:".concat`); boundp/symbol-value read the `_genv`
mirror via `_envLookup` (binding pair `Object[2]`, value = index 1; unbound throws
`The variable X is unbound`); computed fboundp probes `_fenv` then `_lookup`. All three are in
the `usesEval` force list, which also turns on the top-level `_store` mirroring.

**WASM** (`WasmSymbolApiCompiler` + `WasmSymbolApiRuntimeBuilder`): five always-present unary
helpers `FUNC_MAKE_SYMBOL`..`FUNC_FBOUNDP` (type `TYPE_CALLABLE_BASE`, appended before
`FUNC_USER_BASE` like gensym); symbol-name reuses `FUNC_PRINC_TO_STR`. **`_intern_sym` interns
the content range verbatim through the reader runtime's `_intern` so the result's string-table
offset matches literals in the offset-based `_env_lookup`/`eq`**; a `usesIntern` gate
(`usesRead || program uses intern`) emits the real `_intern` body + blob without the rest of the
reader. Unbound symbol-value traps (`unreachable`, the `%error` convention).

**Compile-path folds and limits**: `find-symbol` requires a literal string and matches its
VERBATIM name against `isClSymbol` + keyword + Pass-1 `userDefunNames`; a literal `(fboundp 'x)`
folds with full knowledge, a computed one sees functions only. `#'symbol-name`/`#'intern`/
`#'make-symbol` have wrappers; find-symbol/boundp/fboundp/fmakunbound/symbol-value have none.

### Is identity-by-name stable? Three persisting costs
Nothing on the roadmap forces a redesign (everything CL hangs off a symbol object works as a
name-keyed side table; macro hygiene rests on `gensym`'s counter). Costs: **WASM
canonical-offset discipline** — env lookup and `eq` compare string-table offsets, so EVERY
future primitive that builds a symbol at runtime must route the bytes through `_intern` (reuse
the `usesIntern` gate + `_intern_sym` rail) or it princs correctly and fails lookups/`eq`;
**true uninterned identity is unrepresentable** (`(make-symbol "x")` twice is `eq`, and
`copy-symbol` inherits that — code wanting a name nobody else uses wants `gensym`); and
**`unintern` and shadowing can never be implemented**, the same line as `defpackage` rejecting
`:shadow`. **Re-evaluate when** symbol IDENTITY lands; those become one item.

## A definition IS an interning: the find-symbol namespace probe
- **Interpreter `find-symbol` probes the global namespaces under the canonical spelling**
  (`LispEvaluator.definedInImage`: user macros, functions, global bindings) when the registry
  misses. Both arities: 2-arg builds `qualifyInternal(pkg, name)`, 1-arg asks `internSpelling`.
  Deliberately NOT done: a symbol merely READ is still invisible. `nil` is a SYMBOL to `fboundp`
  now. Pinned by `LispEvaluatorTest#findSymbolSeesDefinitionsMadeInsideAUserPackage`; the
  COMPILED 2-arg lowering keeps its unknown-name-yields-a-symbol deviation.
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
  loop — and a miss signals `The function NAME is undefined`.
- **Divergences**: an eagerly-bound call site of a name that HAD a defun keeps the old function
  after a re-setf; `_invoke_N` still probes `_lookup` only; `--no-gc` has no eval runtime.
- Tests: `LispEvaluatorTest#setfSymbolFunction*`/`#setfFdefinition*`,
  `JvmLispCompilerTest#compileAndRunSetfSymbolFunction*`,
  `WasmLispCompilerIntegrationTest#setfSymbolFunctionAliasAndRedefinition`, ci-spec
  `setf-symbol-function-and-fdefinition`.

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
no qualifier (`computedPackageFindSymbol`: `keyword` -> `:NAME`, `cl`/`cl-user` -> bare,
anything else -> `PKG:NAME`); unconditionally prefixing `(string PKG)` built `KEYWORD:X` instead
of the keyword `:X`.

**A package that does not exist provides no symbol: `find-symbol` answers nil, not an error.** A
LITERAL designator naming no package folds to nil on the compile paths (same baked table). CL
signals a `package-error` and the interpreter used to. `nil` is accepted as the designator
naming the package `"NIL"` (`packageDesignator`).

Tests: the *Fmakunbound* / *FindPackage* / *FindSymbol* groups in the three backend tests, the
`runtime-package-symbol-ops` ci-spec case.

## The standard stream variables are bound in the MIRROR too
A compiled program keeps a special in **two homes that did not know about each other** — the
per-name global field (JVM) / module global (WASM) a direct read uses, seeded with the stream
defaults, and the `_genv` / `GLOBAL_ENV` mirror `symbol-value`/`boundp`/`eval` probe, which only
a top-level assignment writes. Both now seed from ONE table,
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
  source never spells is still unbound. The dynamic-scope divergence is UNCHANGED —
  `symbol-value` reads the global default, not an active `let` binding.
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
  `computedQualifiedSpelling`). **Intern's two contract differences from find-symbol**: an
  unknown LITERAL package is a call-time `(error "No such package: X")` stub, not a nil fold,
  and a nil COMPUTED designator signals the same way. That interpreter error
  (`LispPackageException`) is NOT handler-case-catchable on any backend, so it is pinned
  per-backend, not in ci-spec.
- **Alias rows for internal names in the `_lookup` registries**
  (`JvmEvalRuntimeBuilder.lookupSegments`, the registry-blob loop in `WasmLispCompiler`): the
  runtime-built spelling is always the single-colon EXTERNAL one, so every registered
  `PKG::NAME` defun also answers to `PKG:NAME` — appended after the base rows, genuine keys win.
  Emitted only when the registry is (`usesRuntimeFunctionDesignator` / `usesEval`) **and only
  when the alias SPELLING can reach the run time** (a builder can assemble it, the reader can
  read it under `--dynamic`/`anyNameResolvable`, or this compile spells it). Pinned by
  `WasmLispCompilerTest.theRegistrysSingleColonAliasShipsOnlyWhereItCanBeSpelled`. **VARIABLES
  do not get the alias**: only EXPORTED specials resolve through the `_genv` mirror.
- **CLASS names get the same widening, at the LOOKUP**: every generated class-designator
  dispatch (`%find-class`, `%mop-make-instance`, `%allocate-instance`, the runtime
  `typep`/`subtypep` tables, the condition-class arm of `error`) matches BOTH colon spellings of
  a registered class. One helper builds them (`LispMacroExpander.addDesignatorSpellings`);
  ungated (`.kb/clos.md`).
- **`_apply`'s symbol miss is LOUD**: a designator resolving in neither `_fenv` nor the registry
  throws `The function X is undefined` (JVM `emitUndefinedFunctionThrow`) / traps (WASM). It
  used to return nil silently; the tree-shaker carve-out (`.kb/library-defun-pruning.md`)
  promises a loud failure there.
- **Computed `(symbol-function x)` / `(fdefinition x)` lowers to the IDENTITY**
  (`LispMacroExpander.expandRuntimeSymbolFunction`): on the compiled backends a symbol is a
  function designator wherever a function value is consumed. Deviations vs the interpreter:
  `functionp` of the result is nil, and an undefined name signals at the CALL. The literal-name
  fold is untouched.
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
nothing crosses a function boundary. Because there is no intern table, `intern` never mutates
one, so the two may run in either order (unlike CL).

**A literal argument is passed through, never bound to a temp.** Load-bearing: the compile paths
decide both values by folding the LITERAL name and the LITERAL package designator, and a
temporary hides both (binding them turned `CAR :INHERITED` into a runtime-built
`COMMON-LISP:CAR` with the can't-tell `:INTERNAL`).

- **Interpreter**: `PackageResolver.memberStatus`, mirroring `memberSpelling` arm for arm — that
  is what makes the pair nil TOGETHER (CL's invariant). `cl` owns the standard symbols and
  exports all but the `%`-prefixed internals; `cl-user` uses `cl`, so a standard symbol read
  through it is `:inherited`, and every other name is `:internal`. `%find-symbol-status` adds
  the same definition-IS-an-interning probe.
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

**Deviation**: a user package that uses `cl` answers nil for a standard symbol it does not own,
where CL answers `:inherited`. The status is pinned to `memberSpelling`'s admission test, and
widening THAT would hand sxql's `find-make-op` `CL:COUNT` (tripwire
`MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends`). Revisit when symbol IDENTITY lands.

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
**General rule: a name CL does not have must not claim a macro function.**

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
`while` leg) / `#macroFunctionIsTheRealExpanderOnTheInterpreter` /
`#macroexpand1AnswersTheExpandedPFlag`, the matching pairs in `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest`, and the `macro-function` ci-spec case.

Function-count pins (ci-spec + LispEvaluatorTest + JvmLispCompilerTest x2 +
WasmLispCompilerIntegrationTest) move with each group: 210 -> 217 (the symbol API), 324 -> 325
(`fmakunbound`), 391 -> 392 (`symbol-plist`).
