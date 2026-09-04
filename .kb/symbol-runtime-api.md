# Runtime symbol API (`symbol-name`/`intern`/`find-symbol`/`make-symbol`/`boundp`/`fboundp`/`symbol-value`/`macro-function`/`special-operator-p`)

## `symbol-name` drops the package qualifier

**`symbol-name` (and the string-designator coercions) return the MEMBER name**:
`(symbol-name 'foo::bar)` is `"BAR"` (`LispSymbol.memberName`). `princ`/`~A`/`display` keep
the qualifier (`LispSymbol.displayName` strips only the `:`/`#:` markers); `prin1` keeps
everything. This is what makes name surgery work: `(intern (concatenate 'string (symbol-name
x) "-SUFFIX"))` under `(in-package p)` would otherwise re-qualify into `P::P:X-SUFFIX`
(ironclad's `optimized-maker-name`).

The CASE-folding designators (`string-upcase`/`-downcase`/`-capitalize`) are in that set too;
on the compiled backends they used to drop only a LEADING keyword colon, so
`(string-downcase 'foo::test)` answered `"foo::test"` (sxql renders a column name that way, so
mito's DDL came out `CREATE TABLE t (mito.type::test ...)` on the compiled backends only).
Pinned by the `symbol-runtime-api` ci-spec case (all four backends, SBCL-checked).

## String designators: `(string ...)` is the ONE coercion, and it type-checks

**A CL string designator is a string, a symbol, or a character — and NOTHING else.** Every
position specified that way reaches it through the shared `(string ...)` coercion, injected by
`LispMacroExpander.normalizeStringDesignatorArg` on the compile paths and applied by
`Environment.stringDesignator` in the interpreter. **Do not teach an intrinsic the designator
rules; widen the position to route through `string`.**

Exactly how far it is widened (nothing outside this list is a designator position):

| position | designator? | notes |
| --- | --- | --- |
| `string`, `symbol-name` argument | yes | the coercion itself |
| `string-upcase` / `-downcase` / `-capitalize` argument | yes | `normalizeStringDesignatorArg(cons, 1)` |
| `string-trim` / `-left-trim` / `-right-trim` **trimmed value** | yes | `normalizeStringTrimArgs`, position 2 |
| `string-trim` family **character bag** | **no** | a SEQUENCE of characters; `(string-trim #\* "*x*")` signals |
| `string=` / `string-equal` operands | yes | `normalizeStringComparisonDesignators` |
| `string<` + the nine other ordering predicates | yes | via `(string a)` inside the shared `%string-compare` prelude walk |
| `concatenate` arguments | **no** | SEQUENCES; SBCL signals for `(concatenate 'string "a" 'foo)` |
| `format` `~A` | n/a | `princ`, total by definition |

**The coercion SIGNALS on a non-designator, on all four backends.** Both compile backends used
to render `(string x)` as the bare princ coercion, which is total (`(string 42)` -> `"42"`);
because every designator position routes through this one call, that leniency silently turned
a type error into a plausible string in `string=`, the whole `string<` family, and every
widened position. A computed argument compiles to
`LispMacroExpander.strictStringDesignatorForm`:
`(let ((g x)) (if (or (stringp g) (symbolp g) (characterp g)) (%princ-piece g) (error ...)))`.
`stringp` is true of a mutable character vector on every backend (a fill-pointer buffer still
coerces) and `symbolp` covers `nil`/`t` (`"NIL"`/`"T"`).

**A compile-time-known designator costs nothing**: `literalStringDesignator` folds a literal
string / character / `nil` / `t` / `(quote sym)` / keyword to its constant before the guard is
emitted.

Before the guard: the compile backends carry a string as a QUOTED runtime value (`"abc"`) and
a symbol as its bare spelling, so `string-trim` took the symbol's framing quotes as characters
— `(string-trim "o" 'foo)` answered `"O"` (silently wrong), and `(string-upcase #\a)` was an
uncatchable `cast failure` trap on WASM. Pinned by the `string-designators-440` ci-spec case
and the `stringDesignators` tests on all three engines.

**Re-evaluate when** a new operator names a string designator in its CLHS entry: the change is
one `normalizeStringDesignatorArg` call at its dispatch site, not a new coercion. If `string`
ever needs to be lenient for an internal lowering, give THAT caller its own form rather than
reverting the guard.

## Packages at runtime

**A "package" at runtime is the UPCASED canonical package name as a keyword** — there are no
package objects, and `eq` compares symbols by content, so `find-package` and `symbol-package`
agree by construction and `(eq (symbol-package s) (find-package :p))` works. Upcased because
the compile paths' spelling comes from reader-upcased literals.

- `find-package`: nil for an unknown package. A LITERAL designator is folded by
  `PackageResolver.resolveCons` (the one pass with the registry); a computed designator stays
  a runtime call, which only the interpreter serves (see the baked table below).
- **`package` is BOTH a type specifier and a `defmethod` specializer, from ONE definition**:
  `LispMacroExpander.isSupportedTypeSpecializer` admits the name and `makeTypeTest` builds the
  test, so `(typep x 'package)`, a `typecase` clause and a `((p package))` parameter are the
  same "a keyword naming a registered package" predicate on all four backends (rove's
  `find-suite`; specializer rank and the recursion hazard in `.kb/clos.md`). **Re-evaluate
  when** a consumer needs package objects DISTINCT from keywords — that one change moves the
  type and the specializer together.
- `symbol-package`: registry-backed on the interpreter; a backend-neutral
  `LispPreludeLibrary` defun elsewhere, reading the qualifier off `prin1-to-string`, so it
  cannot tell `cl` from `cl-user` (both answer `:CL-USER` on the compiled backends).
- `type-of`: a prelude defun over the internal `%class-designator` (NOT `class-of`, which
  answers a class metaobject since the DAO/MOP migration) — strips the `%struct-`/`%class-`
  tag prefix to yield the type NAME, so a digest object's type is usable as the digest-name
  designator, and it drags no metaobject runtime into the program. The tag remainder of a
  class from ANOTHER package is a canonical `PKG:NAME` spelling and `type-of` `intern`s it, so
  the answer is right only if `intern` returns an already-qualified spelling unchanged. It
  does: the compile paths' `intern` is package-blind, and `PackageResolver.internSpelling`
  recognizes a qualifier the REGISTRY knows and routes it through `resolveQualified` instead
  of homing the whole string into the current package (which produced `APP::LIB:WIDGET`). An
  exported class keeps its single colon and an internal one its double, so `type-of` agrees
  with `class-name`. Pinned by
  `LispEvaluatorTest#evalTypeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage`,
  its `Jvm`/`Wasm` twins, and ci-spec `type-of-foreign-package-class-name`. **Re-evaluate
  when** symbol identity stops being spelling.
- **2-argument `find-symbol`**: interpreter = registry-backed ("interned" means the package
  owns/exports/imports the verbatim name), returning the canonical spelling. On the compiled
  backends a symbol IS its canonical spelling, so `expandFindSymbolInPackage` BUILDS it:
  `(intern (concatenate 'string "PKG:" name))` for a literal package designator, or the same
  over `(string PKG)` when computed. Two deviations: an unknown name yields a symbol instead
  of nil, and the qualifier is the single-colon EXTERNAL spelling.
  **The first deviation is NOT harmless in the `find-symbol` -> `symbol-function` idiom**:
  sxql's `find-make-op` probes with `:errorp nil`, expecting nil and falling back to a generic
  function-op. We answer a symbol, so `symbol-function` signals and every sxql SQL FUNCTION
  operator (`:count`/`:sum`/`:max`/…) — `mito:count-dao` with them — dies on JVM and WASM
  while the interpreter is correct. The honest fix is symbol IDENTITY (does this package OWN
  this name?). Tripwire: `MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends`.
  **A bare non-keyword symbol in the package-argument position is a VARIABLE REFERENCE, never
  a designator literal** — reading its name as the package once built the doubly-qualified
  `IRONCLAD::IRONCLAD:SHA256`.
- **A quoted LONE SYMBOL is package-resolved in ordinary code**, not only inside a defmacro
  template: CL interns it in the current package at read time, so a `'%indicator` in a defun
  body must name the same canonical symbol a template in the same package stores (ironclad's
  `defdigest` / `digestp`). Quoted LISTS stay untouched, and the `wasm-export`/`wasm-import`
  option tail is exempt (`inHostFacingData`) because its quoted values are host-facing data —
  an export field name must stay `tick`, not `gl::tick`.
- **`(let ((*package* X)) ...)`** is a genuine dynamic binding on every backend
  (`.kb/packages.md`): the interpreter's `evalLet` swaps the resolver's current package (which
  IS the variable there) for the binding's extent; on the compile paths `*package*` is a
  `defvar`'d special and the let is the ordinary shallow binding
  (`.kb/dynamic-special-variables.md`).

The runtime package-mutation API is complete except for `unintern`, a NON-GOAL (below).
`export`/`unexport`/`use-package`/`import` are read/compile-time directives with an
interpreter-side runtime function; `find-package`/`package-name`/`symbol-package`/
`list-all-packages`/`package-use-list`/`package-used-by-list`/`package-shadowing-symbols` are
the queries (`.kb/packages.md`).

## The no-intern-table model

rontolisp symbols compare by name (no intern table), which shapes every deviation:

- `symbol-name` returns the STORED spelling **without the package marker** (a keyword's
  leading `:` and a gensym's `#:` stripped, `LispSymbol.displayName`, shared with
  `princ`/`~A`/`string`; `prin1`/`print` keep the stored spelling), and under the
  uppercase-canonical model (`.kb/reader-case-upcase.md`) that is upcased for every symbol read
  from source, user AND standard (`(symbol-name 'car)` = `"CAR"`; no lowercase-standard-name
  deviation).
- `intern`/`find-symbol` take the name VERBATIM: `(find-symbol "car")` = `NIL`,
  `(intern "TIME")` = `TIME`, `(intern "time")` = the distinct `time`.
- 1-arg `intern` interns into the **current package** on the interpreter
  (`PackageResolver.internSpelling`: an accessible symbol keeps its canonical home spelling,
  an unknown name is homed verbatim into the resolver's `in-package` state — the
  `LispEvaluator` override; the `Environment` converter and the compiled backends stay
  package-blind). `(intern name :keyword)` builds a keyword; any other package argument works
  via the canonical-spelling lowering (the interpreter is registry-backed via
  `PackageResolver.internSpellingIn`, which throws `No such package: X` on every backend).
- `make-symbol` prepends the `#:` uninterned marker (same string twice = `eq` symbols, unlike
  CL). `find-symbol` returns the symbol only when the verbatim name is "known".

**Interpreter**: the pure converters live in `Environment` (next to gensym), but `intern` is
overridden in `LispEvaluator.registerEval` (it needs the evaluator's `packageResolver`);
`boundp`/`symbol-value`/`fboundp`/`find-symbol` live there too because they capture `globalEnv`
(variable lookups see GLOBAL bindings only — CL's dynamic-only semantics;
`Environment.lookupOrNull` exists for this) and `userMacros`/`SPECIAL_OPERATORS` (fboundp is t
for macros, special forms and car/cdr compositions). t/nil/keywords are self-bound in
boundp/symbol-value on every backend.

**JVM** (`JvmSymbolApiCompiler`): symbol-name = the princ-to-string emission;
intern/make-symbol = quote-strip `substring(1, len-1)` (+ `"#:".concat`); boundp/symbol-value
read the eval runtime's `_genv` mirror via `_envLookup` (binding pair `Object[2]`, value =
index 1; unbound symbol-value throws `The variable X is unbound`); computed fboundp probes
`_fenv` then `_lookup`. All three are in the `usesEval` force list in `JvmLispCompiler`, which
also turns on the top-level `_store` mirroring they depend on.

**WASM** (`WasmSymbolApiCompiler` + `WasmSymbolApiRuntimeBuilder`): five always-present unary
helpers `FUNC_MAKE_SYMBOL`..`FUNC_FBOUNDP` (type `TYPE_CALLABLE_BASE`, appended before
`FUNC_USER_BASE` like gensym); symbol-name reuses `FUNC_PRINC_TO_STR`. `_intern_sym` interns
the content range verbatim through the reader runtime's `_intern` so the result's string-table
offset matches literals in the offset-based `_env_lookup`/`eq`; a `usesIntern` gate
(`usesRead || program uses intern`) emits the real `_intern` body + blob without the rest of
the reader. The helper bodies embed the offset of the symbol `t` (interned before the blob
snapshot). boundp/symbol-value/fboundp force `usesEval`; unbound symbol-value traps
(`unreachable`, no message — the `%error` convention).

**Compile-path folds and limits (both compilers)**: `find-symbol` requires a literal string and
matches its VERBATIM name against `isClSymbol` + keyword + Pass-1 `userDefunNames`; a literal
`(fboundp 'x)` folds with full knowledge (specialOperatorNames + clFunctionNames + carcdr +
userDefunNames + ctx.functions), a computed one sees functions only (`(fboundp (intern
"COND"))` = nil compiled, t interpreted). `#'symbol-name`/`#'intern`/`#'make-symbol` have
wrappers; find-symbol/boundp/fboundp/fmakunbound/symbol-value deliberately have none (fold-only
or eval-runtime-dependent). On compiled backends symbol-name is princ-to-string-lenient on
non-symbols (the interpreter type-errors); JVM intern/make-symbol do not type-check either.

### Is identity-by-name stable? Three persisting costs

Nothing on the roadmap forces a redesign — almost everything CL hangs off a symbol object works
as a name-keyed side table (`symbol-plist`, class/method tables, condition type names,
`macro-function`, special-variable bindings), and cross-package distinctness rides the
canonical spelling. Macro hygiene rests on `gensym`'s counter, not object identity. Costs:

1. **WASM canonical-offset discipline (recurring)**: env lookup and `eq` compare string-table
   offsets, so EVERY future primitive that builds a symbol at runtime must route the bytes
   through `_intern` (reuse the `usesIntern` gate + `_intern_sym` rail) or it princs correctly
   and fails lookups/`eq`. A per-feature tax.
2. **True uninterned identity is unrepresentable**: `(make-symbol "x")` twice is `eq`;
   hand-written `#:x` literals in two independent macros collide. `copy-symbol` EXISTS —
   `(make-symbol (symbol-name s))`, property-list argument accepted and ignored (there is no
   `(setf symbol-plist)`) — and inherits the deviation: a copy is a fresh NAME, not a fresh
   identity. Code wanting a name nobody else uses wants `gensym`.
3. **`unintern` and shadowing can never be implemented** (an intern table is the thing you
   unintern from) — the same line as `defpackage` rejecting `:shadow`. A registry-only
   `unintern` would answer t and change nothing observable: the interpreter's find-symbol also
   probes DEFINITIONS in the image (`definedInImage`), and the compile paths have no registry
   at run time. **Re-evaluate when** symbol IDENTITY lands; `unintern`/`shadow`/`copy-symbol`
   become one item. Consumers to check then: uiop/package.lisp (`ensure-package` reads the
   status `unintern` returns), series, mgl-pax, slime.

## A definition IS an interning: the find-symbol namespace probe

Three widenings for trivia level2, all on the no-intern-table model:

- **Interpreter `find-symbol` probes the global namespaces under the canonical spelling**
  (`LispEvaluator.definedInImage`: user macros, functions, global bindings) when the registry
  misses: a `defun` — or a defstruct-generated `POINT-P` — under `(in-package p)` registers
  only in the function namespace as `P::POINT-P`. Both arities: 2-arg builds
  `qualifyInternal(pkg, name)` (bare for cl-user), 1-arg asks `internSpelling`. trivia's
  `predicatep` is the driving consumer. Deliberately NOT done: a symbol merely READ (not
  defined) is still invisible. Also `nil` is a SYMBOL to `fboundp` now (answers nil instead of
  type-erroring). Pinned by `LispEvaluatorTest#findSymbolSeesDefinitionsMadeInsideAUserPackage`;
  the COMPILED 2-arg lowering keeps its unknown-name-yields-a-symbol deviation, which is why
  the ci-spec trivia case omits the probe.
- **`#'find-symbol` is a reference-gated wrapper** (`BuiltinFunctionWrappers.findSymbolWrapper`):
  dispatches on argument count onto the two call-position lowerings. Gated because the body
  lowers to `intern` and the WASM `_intern` runtime is gated — `usesIntern` also counts a
  `find-symbol` reference. trivia's `(remove-if-not #'find-symbol ...)` is the consumer.
- **A RUNTIME `(export ...)`/`(unexport ...)` call** (inside a defun body; a literal top-level
  call is still consumed by `PackageResolver`) compiles to its arguments evaluated for effect
  plus `t` (`LispMacroExpander.expandRuntimeExport`): the compiled registry is frozen and every
  reference already resolved. The interpreter's live registration keeps running the real one.

## Runtime (COMPUTED) package/symbol operations

The literal forms fold; postmodern reaches the same API through variables, so the computed
forms grew real answers.

**`fmakunbound`: the name becomes call-time-undefined again.** Interpreter — the global
function binding AND any user macro of the same name are dropped
(`Environment.undefineFunction` + `userMacros.remove`). Compiled backends — a **TOMBSTONE** is
installed in the eval runtime's function namespace (`_fenv` on the JVM, `GLOBAL_FENV` on WASM):
a binding whose value cell is nil. That namespace is probed BEFORE the compiled-function
registry, so it shadows it, and both lookups already read "no value" as "undefined". `fboundp`
had to learn the same rule (a binding decides the answer on its own instead of merely existing).
Returns the name on every backend.

**`(fboundp 'x)` stops being a bare constant when the program calls `fmakunbound`**: the fold
is emitted BEHIND the tombstone probe (`Ctx.usesFmakunbound`;
`JvmSymbolApiCompiler.emitTombstoneGuard`, `WasmSymbolApiCompiler.emitTombstoneGuardedFold` —
inline `_env_lookup` on the literal's string-table offset, no helper call). A program without
`fmakunbound` is byte-identical to before.

**The divergence**: a call site the compiler already bound directly (an `invokestatic`/`call`
to the defun) keeps working after `fmakunbound` — eager compilation cannot be undone, so only
LATE-bound references (`fboundp`, `funcall` / `#'name` / `eval` through the symbol) see the
retirement. `symbol-function`/`fdefinition` of a LITERAL name are folded the same way and are
likewise not tombstone-aware: the interpreter signals, the compiled backends return the
function. **Re-evaluate when** literal `symbol-function`/`fdefinition` gains a runtime probe.

### `(setf (symbol-function 'f) fn)` / `(setf (fdefinition 'f) fn)`

The write-side twin of the tombstone. `expandSetf` lowers both places to
`(%set-symbol-function name value)` (returns the value; the CL internals
`%SET-SYMBOL-FUNCTION`/`%FENV-FUNCTION` are in `CL_INTERNALS`).

- **Interpreter**: a builtin over `Environment.defineFunction` + `userMacros.remove`.
- **JVM** (`JvmSymbolApiCompiler.compileSetSymbolFunction`): mutate the existing `_fenv` cell,
  else prepend a binding. **WASM**: `FUNC_SET_SYMBOL_FUNCTION`
  (`WasmSymbolApiRuntimeBuilder.buildSetSymbolFunction`), same over `GLOBAL_FENV` (the name's
  string struct carries the interned offset). Both force `usesEval` via
  `LispMacroExpander.usesSymbolFunctionWrite`, which scans the RAW setf place shape — the
  lowering happens per expression, after the gates.
- **The alias idiom needs a defun to call**: a name bound ONLY by the setf (fast-io's
  `(setf (symbol-function 'write8-le) #'write8)`) has no compiled function, so its direct call
  sites would compile to undefined stubs. `expandTopLevelDefinitions` injects one FORWARDER
  defun per such name (`setfOnlyFunctionAliasNames` + `symbolFunctionForwarderDefuns`, appended
  after the walk; names with a program defun or a generic excluded):
  `(defun NAME (&rest args) (apply (%fenv-function 'NAME) args))`. `%fenv-function` probes the
  function NAMESPACE ONLY — probing the compiled registry would find the forwarder itself and
  loop — and a miss signals `The function NAME is undefined` (JVM RuntimeException; WASM
  `unreachable`). Because the forwarder IS a defun, `#'name`, `funcall` and the
  `fboundp`/`symbol-function` literal folds all behave.
- **Divergences**: an eagerly-bound call site of a name that HAD a defun keeps the old function
  after a re-setf; the funcall `_invoke_N` dispatchers still probe `_lookup` only (a
  pre-existing hole shared with fmakunbound — re-evaluate if a library funcalls a COMPUTED
  symbol naming a re-set function). `--no-gc` has no eval runtime: the place is unsupported.

Tests: `LispEvaluatorTest#setfSymbolFunction*`/`#setfFdefinition*`,
`JvmLispCompilerTest#compileAndRunSetfSymbolFunction*`,
`WasmLispCompilerIntegrationTest#setfSymbolFunctionAliasAndRedefinition`, ci-spec
`setf-symbol-function-and-fdefinition`.

### Computed `find-package` is answered from a BAKED table

A literal designator folds via `PackageResolver.resolveCons`; a computed one lowers to
`(cdr (assoc (string x) '(("CL" . :CL) ...) :test #'string=))` built by
`LispMacroExpander.expandRuntimeFindPackage` from `PackageResolver.runtimePackageTable()` —
read AFTER `resolveProgram`, so it covers every `defpackage` — and threaded to both backends as
`Ctx.packageTable`. Keys match VERBATIM, and the table carries both the registered spelling and
its uppercase form, which is what `findPackageName` accepts (a built-in registered lowercase
answers to both cases; a user `defpackage` answers only to the reader-upcased spelling). Pure
AST, so no per-backend codegen. **Divergence**: the table is frozen at compile time, so a
package a compiled program creates later is invisible to a computed `find-package`.
**Re-evaluate when** runtime `defpackage` becomes a thing on the compiled backends. The three
registry QUERIES (`list-all-packages` / `package-use-list` / `package-used-by-list`) are frozen
the same way from `runtimePackageUseTable()` (`.kb/packages.md`).

### Computed `find-symbol`

A computed NAME lowers to `(intern name)` (`LispMacroExpander.computedFindSymbol`) — a symbol
IS its canonical spelling, so interning IS the lookup, carrying the unknown-name-yields-a-symbol
deviation. A computed PACKAGE designator builds the spelling through a runtime test of the
three packages whose members carry no qualifier (`computedPackageFindSymbol`: `keyword` ->
`:NAME`, `cl`/`cl-user` -> bare, anything else -> `PKG:NAME`) instead of unconditionally
prefixing `(string PKG)` — without it postmodern's `json-intern`, whose package comes from
`*json-symbols-package*`, built `KEYWORD:X` instead of the keyword `:X`.

**A package that does not exist provides no symbol: `find-symbol` answers nil, not an error.**
A LITERAL designator naming no package folds to nil on the compile paths (same baked table), so
all four backends agree on postmodern's `(find-symbol "TIMESTAMP" :simple-date)` and ironclad's
`(find-symbol "EA" :sb-vm)` (which used to build `SB-VM:EA`). CL signals a `package-error` and
the interpreter used to (`PackageResolver.memberSpelling` throws), making it the only backend
that did. `nil` is accepted as the designator naming the package `"NIL"` (`packageDesignator`),
so `(find-package nil)` is nil rather than a type error, matching CL.

Tests: the *Fmakunbound* / *FindPackage* / *FindSymbol* groups in the three backend tests, the
`runtime-package-symbol-ops` ci-spec case.

## The standard stream variables are bound in the MIRROR too

`(symbol-value '*error-output*)` answered `2` on the interpreter and signalled
`The variable *ERROR-OUTPUT* is unbound` on all three compile backends (a trap on wasm), same
for `*standard-output*`/`*standard-input*` and their `boundp`. Cause: a compiled program keeps
a special in **two homes that did not know about each other** — the per-name global field (JVM)
/ module global (WASM) a direct read uses, seeded with the stream defaults, and the `_genv` /
`GLOBAL_ENV` mirror `symbol-value`/`boundp`/`eval` probe, which only a top-level assignment
writes.

Both homes now seed from ONE table, `compiler.StreamDesignators.standardStreamDefaults()`
(`*standard-output*`/`*standard-input*` -> the `t` designator, `*error-output*` -> the reserved
handle `2`):

- **JVM**: `<clinit>` emits `_genv = new Object[]{new Object[]{name, default}, _genv}` — the
  binding shape `_store` prepends, so a later top-level assignment MUTATES that cell instead of
  shadowing it. **WASM**: `_start` emits `GLOBAL_ENV = cons(cons(name, default), GLOBAL_ENV)`,
  the name through the string table so its offset is the one `_env_lookup` compares against.
- **Gate is per variable: `usesEval` AND the NAME APPEARS IN THE SOURCE** (`programUsesSymbol`,
  which counts quoted data — lack's `(output '*error-output*)`). Two reasons, the second
  load-bearing: byte-identity for a program that never mentions one, and it is the SAME scan
  the `--component` stderr narrowing uses (`.kb/standard-output-redirect.md`), so the mirror
  seed cannot materialize handle 2 in a component whose `wasi:cli/stderr` was pruned away.
- **Limit**: a name the source never spells (`(symbol-value (intern "*ERROR-OUTPUT*"))`) is
  still unbound on the compile paths. Seeding unconditionally would cost byte-identity and
  defeat the narrowing scan. Re-evaluate if a library builds one of the three names at run time.
- The dynamic-scope divergence is UNCHANGED: `symbol-value` still reads the global default, not
  an active `let` binding, on the compile paths (`.kb/dynamic-special-variables.md`).

**Consumer**: lack's `:backtrace` middleware — which the default `clack:clackup` wraps every
application in — carries `(output '*error-output*)`, a SYMBOL, and reports a failing handler
through `(symbol-value output)`. On a compiled backend that call was itself an error, so the
application's ACTUAL condition was replaced by "The variable *ERROR-OUTPUT* is unbound" behind
a bare 500. On a served `--component` build the unbound signal escaped as an uncatchable wasm
`unreachable` trap that 500'd the whole request even when the application caught its own
condition.

Enabling it exposed a **latent JVM bug**: `_writeString`'s socket probe indexes `_streams` from
the raw handle BEFORE delegating to `_writeStr`, where the stderr branch lives — and `_streams`
was created lazily by `_addStream`, so in a program that opens nothing the table was null.
`<clinit>` now allocates it with the reserved slots empty whenever `usesErrorOutput`, which is
what `_addStream`'s `_streamCount = 3` reservation always assumed. Do not make it lazy again.

Tests: `LispEvaluatorTest#standardStreamVariablesAreBoundToTheirDefaultsThroughTheSymbolApi`,
the `standardStreamVariables*` pair in `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest`, the `symbol-runtime-api` ci-spec case,
`LackEcosystemE2eTest#backtraceMiddlewareReportsTheApplicationsRealError*` (interpreter + JVM,
opt-in), and `ServeConditionCatchComponentE2eTest` (served component leg, opt-in,
`.kb/lack.md`).

## Runtime-interned symbols as function designators

Clack's handler protocol is late-bound by NAME — `(apply (intern #.(string '#:run)
handler-package) ...)`, `(funcall (intern (string :wrap) :clack.middleware) ...)`,
`(symbol-value (intern (format nil "*~A*" ...) package))` — so the compile paths carry a full
symbol-to-function route. On BOTH compile backends (the interpreter resolves designators
against the live environment):

- **2-arg `(intern name pkg)` lowers like 2-arg `find-symbol`**
  (`LispMacroExpander.expandInternInPackage`, from `Jvm/WasmSymbolApiCompiler.compileIntern`):
  the `keyword` designator keeps the byte-identical keyword lowering; a literal `cl`/`cl-user`
  drops the qualifier; any other literal known package builds
  `(intern (concatenate "PKG:" name))`; a computed designator runs the same three-way `cond` as
  `computedPackageFindSymbol` (shared `computedQualifiedSpelling`). **Intern's two contract
  differences from find-symbol**: an unknown LITERAL package is a call-time
  `(error "No such package: X")` stub, not a nil fold, and a nil COMPUTED designator signals
  the same way. That interpreter error (`LispPackageException`) is NOT handler-case-catchable
  on any backend, so it is pinned per-backend, not in ci-spec.
- **Alias rows for internal names in the `_lookup` registries**
  (`JvmEvalRuntimeBuilder.lookupSegments`, the registry-blob loop in `WasmLispCompiler`): the
  runtime-built spelling is always the single-colon EXTERNAL one (exportedness is compile-time
  knowledge), so every registered `PKG::NAME` defun also answers to `PKG:NAME` — appended after
  the base rows, genuine keys win, collision-free because one package cannot house two distinct
  symbols with one member name. Emitted only when the registry is (the
  `usesRuntimeFunctionDesignator` / `usesEval` gate), **and only when the alias SPELLING can
  reach the run time**: `_lookup` matches interned offsets / pool strings, so the row is
  matchable only if a symbol BUILDER can assemble that spelling, the reader can read it
  (`--dynamic`, `anyNameResolvable`), or this compile already spells it. Without the gate a
  library's every internal accessor shipped a second copy of its name that nothing could
  address (−849 B on the zlib size-report row). Pinned by
  `WasmLispCompilerTest.theRegistrysSingleColonAliasShipsOnlyWhereItCanBeSpelled`.
  **VARIABLES do not get the alias**: `boundp`/`symbol-value` of an interned symbol probe the
  `_genv` mirror by the single-colon spelling, so only EXPORTED specials resolve. Re-evaluate
  if a library reads an unexported special through runtime intern.
- **CLASS names get the same widening, at the LOOKUP**: every generated class-designator
  dispatch — `%find-class`, `%mop-make-instance`, `%allocate-instance`, the runtime
  `typep`/`subtypep` tables, the condition-class arm of `error` — matches BOTH colon spellings
  of a registered class, so `(make-instance (intern "SPEC-REPORTER" pkg) ...)` over a
  NON-exported class resolves (rove's `make-reporter`). One helper builds the spellings
  (`LispMacroExpander.addDesignatorSpellings`); ungated, because it costs one symbol per
  package-qualified class in a table the program already emits (`.kb/clos.md`).
- **`_apply`'s symbol miss is LOUD**: a designator resolving in neither `_fenv` nor the registry
  throws `The function X is undefined` (JVM, `emitUndefinedFunctionThrow`) / traps (WASM). It
  used to return nil silently; the tree-shaker carve-out (`.kb/library-defun-pruning.md`)
  promises a loud failure there.

Two divergences retired because their "no runtime name table" reason died with the registry:

- **Computed `(symbol-function x)` / `(fdefinition x)` lowers to the IDENTITY**
  (`LispMacroExpander.expandRuntimeSymbolFunction`, was an unconditional call-time signal): on
  the compiled backends a symbol is a function designator wherever a function value is
  consumed. Two deviations vs the interpreter's live lookup: `functionp` of the result is nil,
  and an undefined name signals at the CALL, not at `symbol-function`. The literal-name fold is
  untouched.
- **`uiop:symbol-call` is REAL on the compile paths**: `expandUiopStubCall` lowers it to
  `(funcall (intern (string name) (find-package pkg)) args...)` over two fixed `%UIOP-SC-*`
  temps that keep the package-before-name evaluation order. Because that lowering happens
  INSIDE the per-expression compilers, AFTER the emission-gate scans ran, the pre-lowering
  spelling counts in two gates: `containsRuntimeFunctionDesignator` and the WASM `usesIntern`.
  **Any future lowering that synthesizes `intern`/`funcall`/`apply` at compile-expression time
  has the same obligation** — check every gate the synthesized forms need, or the runtime
  pieces are stubs.
- **`#'uiop:symbol-call` is a VALUE too**: a fold covers CALL position only, so the shape the
  idiom is written in — `(apply #'uiop:symbol-call '#:pkg '#:name uri args)`, dexador's backend
  dispatch — was `Cannot compile` until `uiop-package.lisp` gained upstream's own
  `(apply (find-symbol* name package) args)` definition beside the fold (`.kb/uiop.md`). The
  other half is the DESIGNATOR spelling: the uninterned `'#:name` reaches the function through
  `(string '#:NAME)` exactly as `:name` does, so the dispatch gate probes `#:member` alongside
  `:member` (`compiler.DesignatorSpellings`, `.kb/optimize-dead-code-elimination.md`) — without
  that row the call compiled and then died undefined at run time. Each arm resolves on its own,
  so an arm naming a missing package costs a call-time error, never the compile.

Already in place since the cl-postgres work: the registry and `_lookup` themselves, symbol
resolution in the funcall dispatchers and `_apply`, builtin designators via the injected
wrappers, and the WASM canonical-offset discipline.

Tests: the *InternIntoALiteralPackage* / *InternIntoAComputedPackage* /
*InternIntoAnUnknownPackage* / *ApplyOfAnUndefinedRuntimeSymbol* groups in JvmLispCompilerTest +
WasmLispCompilerIntegrationTest, and the `runtime-intern-funcall` ci-spec case.

## `find-symbol`/`intern` answer the ACCESSIBILITY STATUS as a second value

CL's `find-symbol` returns the symbol and its status (`:external` / `:internal` / `:inherited`,
or nil). A nil status failed 1,002 of `symbols/cl-symbols.lsp`'s 1,141 tests (that chapter read
the status once per standard symbol), pinning it at 4.2%.

**The status is a SYNTACTIC second value, not a spill value** — the
`%array-disp-target`/`%array-disp-offset` pattern (`LispMacroExpander.lowerMvProducer`): a
`find-symbol`/`intern` producer lowers to the call itself plus a `%find-symbol-status` call over
the SAME argument temps, so every backend gets the second value from its own compile path and
nothing crosses a function boundary. Both lookups are pure, and because there is no intern table
`intern` never mutates one — so the two may run in either order, unlike CL, where the status is
the one from BEFORE the intern.

**A literal argument is passed through, never bound to a temp.** Load-bearing: the compile paths
decide both values by folding the LITERAL name and the LITERAL package designator, and a
temporary hides both (binding them cost `CAR :INHERITED` -> a runtime-built `COMMON-LISP:CAR`
with the can't-tell `:INTERNAL`, on the compiled backends only).

- **Interpreter**: `PackageResolver.memberStatus`, mirroring `memberSpelling` arm for arm — that
  is what makes the pair nil TOGETHER (CL's invariant, and why a consumer may test the status
  instead of the symbol). `cl` owns the standard symbols and exports all but the `%`-prefixed
  internals; `cl-user` uses `cl`, so a standard symbol read through it is `:inherited`, and
  every other name is `:internal`. `LispEvaluator`'s `%find-symbol-status` adds the same
  definition-IS-an-interning probe (`:internal` for a defun registered under the package's
  canonical spelling).
- **JVM + WASM**: one shared fold, `LispMacroExpander.expandFindSymbolStatus`, called from
  `Jvm`/`WasmSymbolApiCompiler.compileFindSymbolStatus` — a keyword or nil constant, never a
  runtime call. Anything it cannot decide (computed name or designator) reports the status of
  the SPELLING the lowering builds: `PKG:` -> `:external`, bare -> `:internal`.

Three pre-existing lowering bugs had to go first, all on `(find-symbol "CAR" 'common-lisp)`:

1. **A QUOTED bare symbol was read as a computed designator.** `literalPackageDesignator`
   rejects a bare non-keyword symbol on purpose (it is a VARIABLE holding a package value), but
   the `quote` unwrap recursed without remembering it had passed a quote. It now carries the
   flag; under a quote a bare symbol IS a designator literal.
2. **A built-in package NICKNAME was not canonicalized** before the keyword/cl/cl-user arms, so
   `common-lisp` missed them and the qualified branch built `COMMON-LISP:CAR`.
   `canonicalDesignator` folds it through `PackageRegistry.canonicalBuiltinName`; a non-builtin
   spelling is returned verbatim.
3. **`(find-symbol LITERAL 'cl)` took the build-a-spelling deviation** instead of the answer the
   compile paths can compute. `cl`'s membership IS compile-time knowledge, so a literal name
   folds to the symbol or to nil exactly as the interpreter answers — which keeps the value and
   the status nil together. The user-package half (an unknown name in `PKG` still yields
   `PKG:NAME`) stays, and with it the computed-name arm.

**Deviation**: a user package that uses `cl` answers nil for a standard symbol it does not own,
where CL answers `:inherited`. The status is deliberately pinned to `memberSpelling`'s admission
test, and widening THAT is not free — sxql's `find-make-op` probes `(find-symbol name :sxql)`
with `:errorp nil` and takes a symbol as "this operator exists", so admitting inherited symbols
would hand it `CL:COUNT` and build the wrong op (tripwire
`MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends`). Revisit when symbol IDENTITY lands.

`symbol-plist` came with it (reaching the `:external` branch calls it): a prelude entry
(`LispPreludeLibrary`) over the same `%symbol-plists` store `get`/`(setf get)` use, carrying its
OWN copy of the store's `(defvar %symbol-plists nil)` — `defvar` assigns only when unbound, so a
program pulling in both entries gets one table. There is no `(setf symbol-plist)`.

**`remprop`** completes the plist family: same shape (one prelude entry, its own copy of the
store's `defvar`, one definition for all four backends), walking the plist two cells at a time
and splicing the pair out with `rplacd` (through the pair BEFORE it, or through the alist entry
itself when the match is at the head). It answers `t`/`nil` where SBCL answers the plist tail;
both are the generalized boolean CLHS specifies. rove's `remove-test` is
`(remprop name 'test)`. Pinned by `LispEvaluatorTest#rempropDropsOnePropertyFromThePlist`, the
matching pairs in `JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`, and the
`symbol-plist-remprop` ci-spec case.

Tests: `LispEvaluatorTest#findSymbolAnswersTheAccessibilityStatusAsItsSecondValue` +
`#symbolPlistReadsTheWholePropertyList`, the matching pairs in `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest`, and the `symbol-runtime-api` ci-spec case.

## `macro-function` / `special-operator-p` PARTITION the operators

**Invariant: every operator with no function value is either a special operator or a macro, on
all four backends, and the two predicates agree on which.** `special-operator-p` is t for
exactly the 25 ANSI special operators (`PackageRegistry.ansiSpecialOperatorNames()`);
`macro-function` is non-nil for everything else rontolisp expands —
`PackageRegistry.runtimeMacroNames()` is `specialOperatorNames()` (special forms + cl macros:
the "no function value" set) minus those 25, plus the program's own `defmacro` names. The split
is deliberately NOT rontolisp's own special-form/macro boundary: `defun`, `handler-case`,
`dolist`, `lambda`, `in-package` are special forms HERE and macros in CL, and a caller of either
predicate is only asking "may I `apply` this name". Verified form for form against SBCL 2.2.9.

**`while` is the ONE exception, and it is a name-space exception, not a partition one.** `while`
is rontolisp's own extension living in `cl` only because that is where built-in operators go, and
CL has no such symbol. `macro-function` answers NIL for it
(`PackageRegistry.namesWithoutMacroFunction()` = the 25 ANSI operators plus `while`, consulted by
BOTH `runtimeMacroNames()` and `LispEvaluator.isMacroName`). Reason: iterate's `walk` asks
`(macro-function (car form))` BEFORE recognizing its own clauses, so a yes on `while` made it
refuse to walk `(iter ... (while test))` — leaving the clause in the emitted tagbody instead of
turning it into the exit test, and the loop never ended. **General rule: a name CL does not have
must not claim a macro function.** Pinned by the `while` leg of
`LispEvaluatorTest.macroFunctionAndSpecialOperatorPPartitionTheOperators`.

**One definition per predicate, shared by every backend, and no compile-time fold.** Both are
`LispPreludeLibrary` entries whose baked name table is GENERATED from `PackageRegistry` (so it
cannot drift from the expander) and tested with `member`. A literal `(special-operator-p 'if)`
compiles to a call, not a constant, unlike the `fboundp` fold — deliberately: a fold decides from
Pass-1 knowledge while a computed name reads a table, and two paths that can disagree is what
this removes. **Re-evaluation trigger**: if a program is measured to care about the call, fold
the LITERAL case in `LispMacroExpander` (both compile paths at once) and keep the table as the
fallback — never per backend.

**The interpreter answers `macro-function` natively; the compile paths answer a name test.**
`LispEvaluator.registerEval` defines it over the macro table it holds (`isMacroName` =
`isUserMacro` ∪ the built-in set), and the value is the REAL expander: a `LispFunction` that
macroexpand-1's the form it is handed after re-heading it with the macro's own name
(`macroCallForm` — CL applies the expander to the whole form and the expander reads the car as
data, so `(funcall (macro-function 'when) '(foo t 1))` expands through `when`). A compiled
program has no macro table, so `macro-function` answers `#'%macro-expander-stub`: non-nil, and a
signal when CALLED. The program's own macro names cannot be in a static table, so
`UserMacroExpander.emitMacroFunctionTable` APPENDS
`(defun macro-function (symbol &optional environment) (%macro-fn symbol '(names...)))` when the
program names `macro-function` at all, which suppresses the prelude entry
(`LispPreludeLibrary.process` skips an entry the program defines itself).

Two load-bearing emission details, both because the BACKENDS resolve the program a second time:
it is APPENDED (by the end every `defpackage` has been seen), and an unqualified name is spelled
`cl-user::name` (`CL-USER::X` canonicalizes back to bare `X` under any current package, where a
bare `x` would be re-qualified into the trailing `in-package`'s package and stop matching).

`macroexpand-1`/`macroexpand` gained CL's `expanded-p` second value with it: the interpreter
publishes it through `%mv-spill` (`expandedWithFlag`; both expanders answer the SAME reference
when nothing expanded, so identity decides the flag), and the compile paths get it from
`UserMacroExpander.expandAll`, whose literal-argument fold emits `(values 'expansion expanded-p)`
— a literal `values` form, which a consumer's syntactic lowering recognizes.

**The two must not contradict each other.** A COMPUTED `macroexpand-1` argument is the shape no
fold can decide; on the compiled backends its prelude body consults `macro-function`: a macro
call SIGNALS (the expander stub's answer), anything else comes back unchanged with `expanded-p`
nil (the identity answer ironclad's `trivial-macroexpand-all` needs). Answering a macro call
with itself leaves `macro-function` saying "macro" while the expander makes no progress, and the
standard `(do ((step form (macroexpand-1 step))) ((not (macro-function (first step))) ...))` loop
(rove's `form-steps`) then spins forever on the compiled backends. Because those bodies read
`macro-function`, a surviving `macroexpand`/`macroexpand-1` call also triggers
`emitMacroFunctionTable` (`usesMacroIntrospection`). Mechanics: `.kb/gensym-macroexpand.md`.

The one shape that must NOT be walked as a call is
`(setf (macro-function 'new) (macro-function 'existing))` — a write to the macro table,
recognized syntactically (`.kb/defmacro-backquote.md`); `expandAll`'s `SETF` case returns it
verbatim.

Tests: `LispEvaluatorTest#macroFunctionAndSpecialOperatorPPartitionTheOperators` /
`#macroFunctionIsTheRealExpanderOnTheInterpreter` / `#macroexpand1AnswersTheExpandedPFlag`, the
matching pairs in `JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest`, and the
`macro-function` ci-spec case (all four backends).

Function-count pins (ci-spec + LispEvaluatorTest + JvmLispCompilerTest x2 +
WasmLispCompilerIntegrationTest) move with each group: 210 -> 217 (the symbol API), 324 -> 325
(`fmakunbound`), 391 -> 392 (`symbol-plist`).
