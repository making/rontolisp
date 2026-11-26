# Runtime symbol API (`symbol-name`/`intern`/`find-symbol`/`make-symbol`/`boundp`/`fboundp`/`symbol-value`/`macro-function`/`special-operator-p`)

## `symbol-name` drops the package qualifier
**`symbol-name` and every string-designator coercion return the MEMBER name**:
`(symbol-name 'foo::bar)` is `"BAR"` (`LispSymbol.memberName`); `princ`/`~A` keep the qualifier,
`prin1` everything. Otherwise `(intern (concatenate 'string (symbol-name x) "-SUFFIX"))` under
`(in-package p)` re-qualifies into `P::P:X-SUFFIX`. The case-folding designators are in the set
(dropping only a leading keyword colon made `(string-downcase 'foo::test)` answer `"foo::test"`
compiled). Pinned by ci-spec `symbol-runtime-api` (four backends, SBCL-checked).

## String designators: `(string ...)` is the ONE coercion, and it type-checks
**A string designator is a string, a symbol or a character, nothing else.** Every designator
position reaches the shared `(string ...)` coercion (`LispMacroExpander.normalizeStringDesignatorArg`
compiled, `Environment.stringDesignator` interpreted). **Do not teach an intrinsic the designator
rules; route the position through `string`.**

Positions, and nothing else: `string`/`symbol-name`; `string-upcase`/`-downcase`/`-capitalize`; the
`string-trim` family's trimmed value, **not** its character bag (a sequence); `string=`/
`string-equal`; `string<` and the other ordering predicates (inside `%string-compare`).
`concatenate` arguments are not designators; `~A` is `princ`, total.

**The coercion SIGNALS on a non-designator, on all four** -- the bare princ coercion is total
(`(string 42)` -> `"42"`) and silently turned type errors into strings. A computed argument goes
through `strictStringDesignatorForm`; a compile-time-known one costs nothing
(`literalStringDesignator`). Pinned by ci-spec `string-designators` and the `stringDesignators`
tests on all three engines. **Re-evaluate when** a new operator names a string designator in CLHS:
one `normalizeStringDesignatorArg` call at its dispatch site, not a new coercion.

## Packages at runtime
**A runtime "package" is the UPCASED canonical package name as a keyword** -- no package objects;
`eq` compares symbols by content, so `find-package` and `symbol-package` agree by construction.
Upcased because the compile paths' spelling comes from reader-upcased literals.

- `find-package`: nil for an unknown package; a literal designator folds
  (`PackageResolver.resolveCons`), a computed one is a runtime call.
- **`package` is BOTH a type specifier and a `defmethod` specializer, from ONE definition**
  (`isSupportedTypeSpecializer` + `makeTypeTest`), the same predicate on all four (`.kb/clos.md`).
  **Re-evaluate when** a consumer needs package objects distinct from keywords.
- `symbol-package`: interpreter registry-backed (`PackageResolver.symbolPackageName`; `cl` for every
  standard name, `t`/`nil`/exported-only included, minus `unintern` tombstones, `.kb/packages.md`
  "The member table"); compiled a prelude defun reading the qualifier off `prin1-to-string`, a bare
  name `cl`'s when the standard-name table holds it (`.kb/packages.md`, "The standard names at run
  time"). An uninterned symbol keeps its old home.
- `type-of`: prelude defun over `%class-designator` (not `class-of`, a metaobject), stripping the
  `%struct-`/`%class-` prefix and `intern`ing the rest -- right only because
  `PackageResolver.internSpelling` routes a qualifier the registry knows through `resolveQualified`
  (else `APP::LIB:WIDGET`). Pinned by
  `LispEvaluatorTest#evalTypeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage`
  (+ twins), ci-spec `type-of-foreign-package-class-name`.
- **2-argument `find-symbol`**: interpreter registry-backed (`PackageResolver.accessible`: present
  member, inherited export, standard name, keyword -- `.kb/packages.md` "The member table"; the
  keyword package keeps no table so it answers every name, where SBCL answers `NIL NIL` for one never
  interned; `gentemp` must not ask it, it hung coalton's `make-local-vars`). Compiled, a symbol IS its
  spelling: for a read/compile-time package `expandFindSymbolInPackage` BUILDS it; a `make-package`
  package answers from its `%runtime-packages%` member table (`runtimeMemberLookup`). What the
  registry knows a baked package reaches (import, re-export, `:use`-inherited, recorded own member)
  answers as interpreted (`.kb/packages.md`, "Compiled lookups answer through the registry"). Else
  the build: `PKG::NAME :internal` in a program package, `PKG:NAME :external` in a pre-seeded one.
  **Deviation: an unknown name yields a symbol instead of nil.** Not harmless: sxql's `find-make-op`
  probes with `:errorp nil` expecting nil, so every sxql SQL function operator dies on JVM and WASM.
  The honest fix is symbol identity. Tripwire: `MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends`.
- **A bare non-keyword symbol in the package-argument position is a VARIABLE REFERENCE, never a
  designator literal** (reading its name built `IRONCLAD::IRONCLAD:SHA256`).
- **A quoted LONE SYMBOL is package-resolved in ordinary code**, not only in a defmacro template.
  Quoted lists stay untouched; the `wasm-export`/`wasm-import` option tail is exempt
  (`inHostFacingData`: host-facing data).
- `(let ((*package* X)) ...)` is a real dynamic binding on every backend; the rest of the runtime
  package API (`make-package` ... `shadow`/`shadowing-import`/`unintern`): `.kb/packages.md`
  "Runtime tier".

## The spelling-identity model (with a member table)
A symbol IS its canonical spelling -- no symbol objects -- which shapes every deviation. What a
package RECORDS is separate: a MEMBER table of what `intern`/`export`/`import`/`shadowing-import`/
`shadow` put in (`.kb/packages.md`, "The member table"), so "interned in P" is answerable without
identity.

- `symbol-name` returns the stored spelling without the package marker (`LispSymbol.displayName`),
  upcased for source-read symbols (`.kb/reader-case-upcase.md`).
- `intern`/`find-symbol` take the name VERBATIM: `(find-symbol "car")` = `NIL`, `(intern "time")`
  = the distinct `time`.
- **1-arg `intern` interns into the current package** and records a fresh name
  (`internSpelling(name, record)`, `recordInterned`). **The compile paths reach it by rewriting the
  call** (`compiler/CurrentPackageIntern`): in a program with an `in-package` of something other
  than `cl-user`, a user program's 1-arg `intern`/`find-symbol` becomes
  `(if (eq cl:*package* :cl-user) (intern x) (intern x cl:*package*))` before any library splice,
  so every later gate sees a computed-designator site; function values of them likewise. Why the
  cl-user arm keeps the 1-arg lowering: a computed designator naming cl-user answers a fresh name
  `:external`, and a literal `(find-symbol "car")` would stop folding to NIL (ci-spec
  `mv-producer-function-return` / `symbol-runtime-api`). Why only the user program: the
  implementation's own Lisp uses 1-arg `intern` on CANONICAL spellings (`"PKG::MEMBER"`). Still
  package-blind: a designator the pass cannot see (`(symbol-function 'intern)`, a symbol in a
  variable) and `eval`'d code. Pinned by ci-spec
  `intern-of-an-internal-name-is-the-read-symbol` (SBCL-checked). The runtime READ homes tokens the
  same way (`PackageResolver.readSpelling`; compiled `%read-home`, `.kb/read-load-streams.md`, "A
  symbol token reads in the current package").
- `(intern name :keyword)` builds a keyword; another package goes through `internSpellingIn`. A
  designator naming no package is a catchable `package-error` on every backend (the interpreter
  checks before the resolver, whose `LispPackageException` no handler sees).
- **`t` and `nil` are singletons, not `LispSymbol`s**: everything answering a symbol by NAME maps
  `"T"`/`"NIL"` back to them on every backend; compiled, only NIL needs an arm (`t` already is the
  symbol `"T"`), on WASM only under `usesIntern`. Pinned by
  `TAndNilSingletonCorpus` (`LispEvaluatorTest` / `JvmLispCompilerTest` /
  `WasmLispCompilerIntegrationTest#...TAndNilSingletons`) and ci-spec `symbol-runtime-api`.
- `make-symbol` prepends the `#:` marker (same string twice = `eq`, unlike CL).

Interpreter `boundp`/`symbol-value` see GLOBAL bindings only (CL's dynamic-only semantics).
t/nil/keywords are self-bound in `boundp`/`symbol-value` on every backend.

**Interpreter `fboundp` answers what a call would** (`fbound` -> `loadFunction`): a probe of a
library-defined name LOADS it. It skips `resolveFunction`'s `pkg::member` -> bare image-function
fallback: a symbol that only falls back has no definition. Of SBCL's 636 external `cl` functions
only the unimplemented `CL_EXPORTED_ONLY` answer NIL, the same set the compiled literal fold
(`CL_FUNCTIONS`) answers NIL for; the compiled computed probe adds `provide`/`require` (literal
top-level only).

**Every CL function has a function value**, the ten the expander rewrites in call position too
(`BuiltinFunctionWrappers.INLINE_EXPANDED_VALUE_FUNCTIONS`: reference-gated catalog wrappers).
**The reach** (`FunctionDesignators.dataCalledNames`): a program that can turn data into a call and
spells such a name in a `quote` gets a top-level `(function name)` appended
(`normalizeBuiltinDesignators`), so every wrapper gate sees it; a `car`/`cdr` composition gets a
named lambda wrapper (`FunctionValueWrappers.generate`). A quoted name an operator reads as a type,
class, condition or slot does not count. Pinned by `BoundpFunctionValueFixture` (all four), `FunctionDesignatorsTest`,
`LispEvaluatorTest#fboundpChecksFunctionsMacrosAndSpecialForms`.

**JVM** (`JvmSymbolApiCompiler`): `boundp`, and `symbol-value` of a name no special has, read the
`_genv` mirror; a special's value and bound-ness come from its variable
(`.kb/dynamic-special-variables.md`, "One home", "Bound-ness of a special without a value");
computed `fboundp` probes `_fenv` then `_lookup`. All are in the `usesEval` force list, which turns
on the top-level `_store` mirroring.

**WASM** (`WasmSymbolApiCompiler` + `WasmSymbolApiRuntimeBuilder`): **`_intern_sym` interns the
bytes through the reader runtime's `_intern` so the result's string-table offset matches literals
in the offset-based `_env_lookup`/`eq`**; `usesIntern` (`usesRead` || the program interns) emits
`_intern` without the rest of the reader. Unbound `symbol-value` traps (the `%error` convention).

**`_intern` is a hash table** (`WasmReadRuntimeBuilder.buildInternBody`, FNV-1a, linear probing,
load < 1/2, no header: the slot count derives from `RT_INTERN_COUNT_ADDR` and the compile-time row
count). At half load a doubled table is carved PERMANENTLY off the heap; every permanent heap
advance changes the count, which the per-call reset guards (`cabi_post_*`, the serve adapter)
depend on. A scan cost O(table) per call (ci-spec `runtime-package-api` halved per WASM leg).
Pinned by `WasmLispCompilerIntegrationTest#runtimeInternKeepsEverySymbolPastAThousand` (fresh
symbols past any fixed region; a wasmtime fuel budget: 280 M measured against 1.2 G, a scan 3.2e9
row compares).

**Compile-path folds and limits**: `find-symbol` of a literal string matches VERBATIM against
`isClMemberName` (every name cl exports, `T`/`NIL`/`DEBUG` included) + keyword + `userDefunNames`
(`foldLiteralFindSymbol`) + what the registry says `cl-user` holds, read names included
(`.kb/packages.md`, "What a program read"); a literal `(fboundp 'x)` folds with full knowledge, a
computed one sees functions only. `#'symbol-name`/`#'intern`/`#'make-symbol` have wrappers;
`find-symbol`/`boundp`/`fboundp`/`symbol-value`/`set` reference-gated ones (`#'boundp`/`#'fboundp`
bodies are the computed probe and count for `boundpProbes`); `fmakunbound` none.

### Is identity-by-name stable? Three persisting costs
Nothing on the roadmap forces a redesign (whatever CL hangs off a symbol object works as a
name-keyed side table; hygiene rests on `gensym`'s counter). Costs: **WASM canonical-offset
discipline** -- env lookup and `eq` compare string-table offsets, so EVERY future primitive that
builds a symbol at runtime must route through `_intern` (the `usesIntern` gate + `_intern_sym`) or
it princs right and fails lookups/`eq`; **no true uninterned identity** (`(make-symbol "x")` twice
is `eq`, `copy-symbol` too; an uninterned symbol `import`ed cannot gain that home, ANSI
`import.5`); **`unintern` cannot produce a DISTINCT symbol** (member removal + a tombstone for
`symbol-package`; interning again homes the OLD symbol). A real intern table was weighed and the
model kept (`.kb/packages.md`, "The member table"): **re-evaluate only when a consumer needs symbol
OBJECTS**, not for a conformance count. Needing MEMBERSHIP (plump) is answered by the member table.

## The empty name
**No symbol is the empty string.** A compiled symbol is a bare string told from a framed one by
byte 0, so `cl-user`'s `||` is spelled `CL-USER::` (`LispSymbol.CL_USER_EMPTY_NAME`) on all four.
`P::||`, `:||`, `#:||` were never empty.

- As a qualified spelling of that very symbol it needs nothing from readers of a member name or
  home. The printers print it bare (`||`; `COMMON-LISP-USER::||` under another package).
- Rejected: a guard at each of ~30 `charAt(0)` tests on every program's hot paths. This shape costs a
  program that never spells the name almost nothing (wasm: only where it interns or the string table
  holds the spelling).
- `set` takes it (SBCL does).
- **A `cl-user` name holding a colon is spelled the same way** (`LispSymbol.clUserSpelling`):
  `|a:b|` is `CL-USER::a:b`, since a bare `a:b` is `A`'s `B`. The readers frame it
  (`.kb/read-load-streams.md`, "Emitted reader: frontend parity"), and so do `intern`/`find-symbol`
  with `cl-user` named (the interpreter's `internSpellingIn` takes the name whole for ANY package
  named); printers print it bare. **The one-argument `intern` does not**, on any
  backend: it takes a colon name as spelled (`internSpellingExact`) because the implementation's
  canonical spellings and `":KW"` go through it, so `(intern "a:b")` is `A`'s `B`.
- Limits: `(intern "CL-USER::")` is this symbol, as `(intern "P::X")` is `P::X`. A `(defstruct ||)`
  instance prints `#S( :A 1)` (SBCL `#S(|| :A 1)`): type names print raw on all four. A run-time read
  under another package answers that package's.
- Pinned by ci-spec `the-empty-name-is-a-symbol-like-any-other`,
  `a-colon-inside-a-multiple-escape-is-a-constituent`.

## A definition IS an interning: the find-symbol namespace probe
- **Interpreter `find-symbol` probes the global namespaces under the canonical spelling**
  (`LispEvaluator.definedInImage`: user macros, functions, global bindings) as one arm of
  `PackageResolver.accessible`, after the own/imported arms and before the inherited one (both
  arities, via `findSymbolAccessible`). It covers names a definition DERIVES (a `defstruct`'s
  accessors); a READ symbol is a recorded member, in the interpreter and the compile paths'
  macro-time evaluator. `nil` is a symbol to `fboundp`. Pinned by
  `LispEvaluatorTest#findSymbolSeesSymbolsReadOrDefinedInsideAUserPackage`; the compiled 2-arg
  lowering keeps its unknown-name-yields-a-symbol deviation.
- **`#'find-symbol` is a reference-gated wrapper** (`findSymbolWrapper`), gated because its body
  lowers to `intern` and `usesIntern` counts a `find-symbol` reference.
- **A runtime `(export ...)`/`(unexport ...)`** compiles to its arguments for effect plus `t`
  (`expandRuntimeExport`): the compiled registry is frozen.

## Runtime (COMPUTED) package/symbol operations
**`fmakunbound`: the name becomes call-time-undefined again.** Interpreter: the function binding
and any user macro of the name are dropped. Compiled: a **TOMBSTONE** (a binding whose cell is nil)
in the eval runtime's function namespace (`_fenv` / `GLOBAL_FENV`), probed BEFORE the compiled
registry, so it shadows it; `fboundp` follows the same rule (a binding decides on its own). A
built-in or library function is retired too: the interpreter keeps the names
(`LispEvaluator.retiredFunctions`), since dropping `car`'s binding left `fboundp` T through the
catalog and `(car x)` resolving its catalog wrapper forever (ci-spec
`a-method-on-a-lowered-built-in-answers-a-form-eval-runs`). SBCL refuses this by package lock.

**`(fboundp 'x)` stops being a bare constant when the program calls `fmakunbound`**: the fold sits
behind the tombstone probe (`Ctx.usesFmakunbound`; `emitTombstoneGuard`,
`emitTombstoneGuardedFold`). Without `fmakunbound`, byte-identical.

A top-level `defun` retired by a literal or computed name is retired for every reference
(`compiler/RedefinedDefuns`, below).
**A SYMBOL reaching a dispatcher is a late-bound reference**: the dispatcher's symbol arm probes the
function namespace before `_lookup` (the order of `symbol-function` and `_apply`), and a binding
decides on its own -- its function replaces the symbol; the tombstone reports the SYMBOL undefined
although the registry may still answer it. JVM `JvmRuntimeBuilder.FunctionNamespace` /
`emitNamespaceProbe`; WASM `emitDispatchPrologue`'s `probesNamespace` (`$undefined` in EH mode, a trap
outside). `_apply` hands a tombstoned SYMBOL on (not its nil cell) so the report names it. Gated on
`writesFunctionNamespace` (every eval-runtime reason except the readers `boundp`/`symbol-value`/
`set`/`fboundp`). Pinned on all four by `RuntimeFunctionNamespaceCallFixture`.

### `(setf (symbol-function 'f) fn)` / `(setf (fdefinition 'f) fn)`
`expandSetf` lowers both places to `(%set-symbol-function name value)`.

- Both compilers force `usesEval` via `usesSymbolFunctionWrite`, which scans the RAW setf place --
  the lowering happens per expression, after the gates.
- **The alias idiom needs a defun to call**: a name bound ONLY by the setf (or a literal
  `(compile 'NAME definition)`) gets one FORWARDER defun (`setfOnlyFunctionAliasNames`,
  `symbolFunctionForwarderDefuns`): `(defun NAME (&rest args) (apply (%fenv-function 'NAME) args))`.
  **`%fenv-function` probes the function NAMESPACE ONLY** -- the registry would find the forwarder
  itself and loop -- and a miss signals undefined-function like a direct call (JVM
  `emitUndefinedFunctionThrow`, wasm `emitFenvRead` -> `emitUndefinedFunctionSignal`).
- **The forwarder is no definition of the name**: it serves direct calls only. Each backend
  (`Ctx.fenvForwarders`) compiles `#'NAME`, literal `symbol-function`/`fdefinition` and
  `(fboundp 'NAME)` as namespace reads and keeps the forwarder out of the name registry
  (`callOnlyRuntimes`), so computed designators miss as on the interpreter and SBCL. The eval
  runtime's `_eval` knows the names too (`EvalConstants.fenvForwarders`): an unbound or tombstoned
  such operator is reported undefined, naming it, before any argument is evaluated -- not the
  unknown operator's silent nil.
- **A nested defun a run-time name can reach takes the same path**: `.kb/core-representation.md`,
  "A nested defun a RUN-TIME name can reach".
- **A definition the program writes by a literal name has the same one home**
  (`compiler/RedefinedDefuns`). A top-level `defun` (also a generated one: a generic's dispatcher, a
  slot accessor) of a name the program writes literally, `compile`s or `fmakunbound`s becomes a
  renamed defun installed by `%set-symbol-function` where the `defun` stood, plus the forwarder, so
  every reference reads the namespace. A call before the definition ran is `undefined-function`, as
  in a load.
  `LetBoundDesignators` does not propagate a forwarded name: propagated, a wrapper's
  `(let ((old #'n)) (setf (fdefinition 'n) (lambda (x) (funcall old x))))` called itself. An exported
  name is refused (an export binds one static definition). `(fmakunbound 'g)` counts only for the
  program's own `defun`, not a later `defgeneric`. Not in a JVM host or fragment (`Ctx.linked`;
  `JvmFunctionCell` serves those). Pinned by ci-spec
  `a-defun-the-program-replaces-by-name-answers-the-new-definition`, `RedefinedDefunsTest`.
- **Interpreter, a generic's `#'name`** is late-bound through `genericDispatchers`: a `#'g` taken
  before `(setf (fdefinition 'g) ...)` keeps reaching the generic (wrapping itself overflowed the
  stack), one taken after is what the setf stored.
- **A name in `%fenv-function`'s path reads a cell** (below), and a direct call of a forwarder's name
  is `(funcall (%fenv-function 'n) arg...)`: no rest list (`fib 32` JVM 203-247 -> 41-69 ms, P1
  490-575 -> 115-119 ms). The forwarder stays for what cannot take that call: on wasm-GC a count past
  `callArityCeiling`, and `(apply 'n ...)`.
- **A definition of a generic function binds its name again** (`%generic-defined-again`, ci-spec
  `a-definition-of-a-generic-function-binds-its-name-again`, SBCL agrees where it does not refuse). A
  generic has one dispatcher, placed at its first `defgeneric`/`defmethod`, so each later definition
  is marked (`ClosRegistry.markGenericDefinitions`) and `RedefinedDefuns.rewrite` makes it bind the
  name. A name holding its generic function metaobject keeps it (`%gf-installed-p`). On the
  interpreter a regeneration of the dispatchers (for a `defclass`, `defstruct`, condition class) is
  no definition (`regenerateDispatcher`). SBCL refuses a `defmethod` over an ordinary function; here it
  replaces it, on all four.
- **Residuals**: a generic's methods survive `fmakunbound` on all four (SBCL makes a fresh generic).
  A name with two top-level `defun`s has the last one's on restore ("A redefined defun" in
  `.kb/core-representation.md`). After a `(setf p)` writer is retired by a computed list, a direct
  call reports `%setf-P` on wasm-GC (JVM `(SETF P)`). `--no-gc` has no eval runtime.
- Tests: `LispEvaluatorTest#setfSymbolFunction*`/`#setfFdefinition*`,
  `JvmLispCompilerTest#compileAndRunSetfSymbolFunction*`,
  `WasmLispCompilerIntegrationTest#setfSymbolFunctionAliasAndRedefinition`, ci-spec
  `setf-symbol-function-and-fdefinition`; `SetfSymbolFunctionReferenceFixture`
  (`aNameOnlySetfSymbolFunctionBindsIsUndefinedUntilTheSetfRuns` in the three backend suites).

### A write through a computed name
**A top-level `defun` a write by a name the compile cannot read can reach keeps its compiled
definition and gets a cell: a reference reads the cell in constant time and takes the compiled
definition while the program has written nothing there** (`compiler/RedefinedDefuns`, all four; the
interpreter's bindings always were this).

- **The writers** (`writesComputedNames`, plus `eval` / run-time `load`) are read on the expanded
  program, so the compile runtime and the generic-function protocol count.
- **The names** (`celledDefuns`): `NestedDefunNamespace`'s rule. A `%` name only where spelled,
  `%setf-` writers excepted (nothing says data defines one, and they are most of a library). Never a
  method body or a forwarder. A name assembled from computed pieces is the `RuntimeNameProducers`
  carve-out. A generic
  on a lowered built-in is reached through its dispatcher's cell, a literal write too
  (`namespaceCells`/`functionCells` hold both spellings; `.kb/clos.md`, "A user method on a BUILT-IN
  name").
- **The cell** holds nil while `_fenv`/`GLOBAL_FENV` has no binding of the name, the binding once it
  has: the alist stays what the program wrote, and only MAKING a binding publishes (once per name,
  also under the one-colon alias `_lookup` answers). JVM `_fc$name` is volatile only where another
  thread runs Lisp (`lispOnOtherThreads`).
- **The read**: a call by name goes through the function's named entry: the function while the cell
  is nil, else `_apply` of the binding. A function value keeps its code, so a wrapper's `old` runs the
  old definition. A count this definition rules out goes through `#'n`, so the replacement's lambda
  list decides; `LetBoundDesignators` leaves the name alone. Both fusions check the cell
  ([wasm-int-fusion.md](wasm-int-fusion.md), "A name with a cell", "A leaf compiled twice";
  [jvm-int-fusion.md](jvm-int-fusion.md)).
- **`%restore-compiled-definition`**: each `defun` form of such a name unlinks the binding and clears
  the cell, so the compiled definition answers from there, whatever a write did before
  (`(undefine 'f) (defun f ...)`, uiop's `defun*`). A call ahead of the form still reaches the
  compiled definition.
- **Tail calls stay proper through a replaced name** (ci-spec `cwn-loop`, and a tail-recursive defun
  under a wrapper, 200,000 deep; the JVM overflowed past ~30,000). Wasm-GC `return_call`s. JVM: a
  tail call whose answer reaches a check takes `_nt$name`, whose replaced arm answers a bounce; the
  celled defun's named entry checks for it (`Ctx.answersCellBounces`, `Ctx.celledBouncers`), only
  while `_fenv` holds anything. A tail group with an uncelled member keeps real calls. Rejected:
  `bounceVisible` for every celled defun -- a check behind every call of every program holding
  `eval`.
- **Rejected: one array of cells by funcId** (no per-name publish code): `fib 40` on wasmtime (direct
  1.4 s) took 2.7 s through an array element, 1.9 s through a global -- the array reference pays the
  collector's read barrier per call. An inline check at a self call instead of the entry gained
  nothing.
- Cost, `fib 38` in a program holding `eval`, every function celled: Graal 271-299 -> 383-402 ms, C2
  439-469 -> 479-486 ms, P1 963-989 -> 1128-1155 ms; class +0.2%, module +0.1%. Ci-spec corpus as one
  program, every function celled: classes +1.8%, module +1.4%, all lines correct on all four.
- Pinned by ci-spec `a-defun-written-through-a-computed-name-answers-the-new-definition`, standalone
  `a-defun-written-through-a-name-held-as-a-value` (both SBCL-agreed),
  `a-write-of-the-name-of-a-method-on-a-lowered-built-in-reaches-every-reference` (interpreter's
  answers; SBCL refuses the method); `RedefinedDefunsTest`.

### A `(setf name)` function name
`fdefinition`, `fboundp`, `fmakunbound` and `(setf (fdefinition ...))` take a `(setf name)` list as
well as a symbol (`symbol-function` does not: SBCL signals a type-error). The writer is stored under
`%setf-NAME` (`LispMacroExpander.setfFunctionName`).

- **A standard accessor's `(setf F)` is not fbound**, on all four (`#'(setf car)` is
  `undefined-function`, `(fboundp '(setf gethash))` NIL; SBCL answers T): the place lowers where
  `setf` is. CLHS 5.1.2.9 leaves it implementation-dependent. **Re-evaluate when** a library takes one
  as a value.
- **Interpreter**: any list at run time (`LispEvaluator.functionName`); `fmakunbound` answers the list.
- **Compile paths, a QUOTED list**: rewritten onto `%setf-N` in `normalizeBuiltinDesignators`
  before every gate, so the setf-only forwarder serves it. A miss reports `(SETF N)`
  (`ClosRegistry.functionNameForReport`).
- **Compile paths, a list built at run time** (`compiler/RuntimeFunctionNames`): prelude helpers
  called in place of the operator for a COMPUTED argument (`mayBeComputedName`); a
  `(%function-name ...)` argument is never wrapped again (how the helpers call the operators).
  `%setf-function-symbol` on wasm goes through `_intern` (joins `usesIntern`).
- **Selection** (`RuntimeFunctionNames.sites`, a `LibraryDefunPruner` root): a computed site of one
  of the operators (or one as a value) AND a way for the run time to hold `SETF` as data (quoted,
  backquoted, a `"SETF"` string with a symbol builder, or a data evaluator, `RuntimeNameProducers`;
  `(fboundp '(setf n))`'s argument excluded). Otherwise byte-identical.
- **Reachability**: where `%function-name` is defined, a `%setf-PLACE` function gets a registry row
  wherever PLACE is spelled (`DesignatorSpellings.matched`'s `setfNamesBuilt`), and every writable
  defstruct slot its writer defun (`referencedStructWriterDefuns`). A place assembled from computed
  pieces is the `RuntimeNameProducers` carve-out.
- **A name only the run time binds is looked up when it is used.** Where the program can bind such
  a name -- `Ctx.bindsRuntimeFunctionNames` = `eval` || `load` ||
  `LispMacroExpander.writesComputedFunctionName` (a place whose name is not a quoted symbol or
  `(setf n)` list; `fmakunbound` and quoted writes excluded) -- a name no definition has reads the
  namespace (`runtimeFunctionNamespaceCall`; a miss signals ahead of the arguments, as the
  interpreter), warning `looked up when the call runs` / `... the reference runs` instead of
  `compiled as a ... error`. Pinned by `SetfFunctionNameFixture.COMPUTED_INSTALL`
  (`aFunctionANameBuiltAtRunTimeInstallsIsCalledByName` in the three backend suites), the gate by
  `LispMacroExpanderTest#onlyAFunctionNamespaceWriteThroughAComputedNameCanBindANameTheCompileCannotSee`,
  the warnings by `RontoLispCliStreamsTest#anUndefinedNameAProgramCanBindAtRunTimeWarnsThatItIsLookedUp`.
- Pinned by `SetfFunctionNameFixture.DESIGNATOR` (`theFunctionNameOperatorsTakeASetfFunctionName` in
  the three backend suites) and `.COMPUTED_DESIGNATOR`
  (`theFunctionNameOperatorsTakeASetfFunctionNameBuiltAtRunTime`, P1 and component in the wasm one);
  selection by
  `LispPreludeLibraryTest#theRunTimeSetfFunctionNameHelpersAreSplicedOnlyWhereSuchANameCanReachAnOperator`;
  the interpreter's refusal of a non-function-name list by
  `LispEvaluatorTest#theFunctionNameOperatorsTakeAComputedSetfFunctionName`.

### `set` / `(setf (symbol-value name) value)` for a computed name
`expandSetf` lowers the place to `(set name value)`, so only `set` needs per-backend work.

- **Semantics, all four** (CL's): the CURRENT dynamic binding when one is active (`let`, parameter,
  `progv`, declared or not) -- what `setq` assigns; otherwise the global, CREATING it when unbound
  (how a run-time evaluator defines program globals; a deliberate deviation where CL signals).
  Constants (nil, t, keywords, by value or computed name) and non-symbols signal on the interpreter
  and JVM, trap on WASM. Before, the backends disagreed three ways (wasm's shallow binding: the
  module global IS the binding).
- **Compilers**: one call to the shared `%set-global` (`setGlobalRuntime`; inline at each site it
  was kilobytes of JVM bytecode): the mirror write, then the store a non-lexical `setq` makes,
  dispatched like progv (`.kb/dynamic-special-variables.md`: `%symbol-is`, wasm by canonical offset
  so literal and run-time `intern` agree). Mirror before field is unobservable: no global is a
  constant or non-symbol. Injected per `programUsesSet` (2-arg `set`, or a `symbol-value` place
  written by `setf` or a modify macro). **A variable NAMED `set` is no site**: a `let` binding or
  lambda-list entry of it is walked for its init forms only (`callsSet`). Forces `usesEval`; `--no-gc`
  refuses by omission.
- **The `boundp` gate gains an arm** (`CompileTimeBoundp`): a program calling `set` or spelling the
  raw `symbol-value` place (`usesSymbolValueWrite`) can make a global appear, so the fold is off.
- A direct reference to a name only `set` created does not compile (like an eval-created global);
  read it through `symbol-value`/`eval`. Inside a compiled runtime `eval`, `set` is an unknown
  operator answering nil, like `boundp`/`symbol-value`.
- **The mirror is still written inside an active binding** (`%set-mirror` first). No read takes a
  special's VALUE from it (`symbol-value`/`eval` read the variable, `boundp` of a valueless special
  too, `.kb/dynamic-special-variables.md`), so a `set` inside a binding leaves it unbound after the
  extent.
- Tests: `LispEvaluatorTest#set*`, `JvmLispCompilerTest#compileAndRunSet*`,
  `WasmLispCompilerIntegrationTest#set*`, `CompileTimeBoundpTest` (the gate arm),
  `BuiltinFunctionWrapperCatalogTest` (the `#'set` value), ci-spec
  `set-and-setf-symbol-value-for-computed-names`. The active binding: `SetInDynamicBindingFixture`
  on `setWritesTheActiveDynamicBinding` in `LispEvaluatorTest`, `JvmLispCompilerTest` and
  `WasmLispCompilerIntegrationTest` (P1 and component), ci-spec
  `set-writes-the-active-dynamic-binding`,
  `WasmReentrantE2eTest#overlappedCallsEachSetTheirOwnDynamicBinding`;
  `LispMacroExpanderTest#aVariableNamedSetIsNoSetSite` / `#aSetCallOrASymbolValuePlaceWriteIsASetSite`.

### Computed `find-package` is answered from a BAKED table
A computed designator looks up `PackageResolver.runtimePackageTable()` (read AFTER `resolveProgram`,
so every `defpackage` counts; `expandRuntimeFindPackage`, `Ctx.packageTable`). Keys match VERBATIM;
the table carries the registered spelling and its uppercase form. **Divergence**: frozen at compile
time, so a package the compiled program creates later is invisible. `list-all-packages` /
`package-use-list` / `package-used-by-list` are frozen the same way (`runtimePackageUseTable()`,
`.kb/packages.md`).

### Computed `find-symbol`
A computed NAME lowers to `(intern name)` (`computedFindSymbol`) -- interning IS the lookup --
carrying the unknown-name-yields-a-symbol deviation. A computed PACKAGE builds the spelling through a
runtime test of the three packages whose members carry no qualifier (`computedQualifiedSpelling`:
`keyword` -> `:NAME`, `cl`/`cl-user` -> bare, else `PKG:NAME`, or `K::NAME` under the program
package's own name, `%program-package-p`); prefixing `(string PKG)` unconditionally built `KEYWORD:X`.
Before either build, a package the registry knows to reach the name answers it (`%baked-access`,
`.kb/packages.md` "Compiled lookups answer through the registry"), and a standard name inherited from
`cl` answers the `cl` symbol (`.kb/packages.md`, "The standard names at run time").

**A designator naming no package signals a `package-error`, as `intern`'s does.** `nil` designates
the package `"NIL"`, which no image has. Interpreter: `findSymbolAccessible`, shared with
`%find-symbol-status`, so the multiple-value lowering signals once. Compiled: a LITERAL unknown
designator lowers to the `%package-error` stub (behind the runtime table when
`usesRuntimePackages`); a COMPUTED one is guarded by `(find-package p)` (`computedGuardedSpelling`,
shared with `intern`). Every shipped-library probe of an optional package already guarded on
`find-package`, sat behind another implementation's reader conditional, or errored on nil anyway.
The optional-system probe is `(and (find-package p) (find-symbol n p))`.
- **A computed-designator site is one call to the `%symbol-in-package` prelude defun** (its body IS
  `computedGuardedSpelling`, so they cannot drift); a site the selection cannot see keeps the inline
  form. Why: inline, the guard's baked table is a quoted constant built at EACH site, and
  `JvmLispCompilerTest#compileAndRunRuntimePackageMemberTable`'s many-site method crossed the 64 KB
  method limit. The guard's `find-package` is one call to `%find-package` (`.kb/packages.md`).

Tests: the *Fmakunbound* / *FindPackage* / *FindSymbol* groups in the three backend tests
(`findSymbolInAMissingPackage*` for the signal), ci-spec `runtime-package-symbol-ops`,
`find-symbol-in-a-missing-package`.

## The standard stream variables are bound in the MIRROR too
A compiled special has **two homes**: the per-name field (JVM) / module global (WASM) a direct read
uses, and the `_genv`/`GLOBAL_ENV` mirror `symbol-value`/`boundp`/`eval` probe. Both seed from ONE
table, `compiler.StreamDesignators.standardStreamDefaults()`.

- JVM `<clinit>` prepends the binding shape `_store` uses, so a later top-level assignment MUTATES
  that cell instead of shadowing it. WASM `_start` conses it, the name through the string table so
  its offset is the one `_env_lookup` compares.
- **Gate per variable: `usesEval` AND the NAME APPEARS IN THE SOURCE** (`programUsesSymbol`, quoted
  data counted) -- for byte-identity, and because it is the SAME scan the `--component` stderr
  narrowing uses (`.kb/standard-output-redirect.md`), so the seed cannot materialize handle 2 where
  `wasi:cli/stderr` was pruned. **Limit**: a name the source never spells is unbound in the mirror;
  `symbol-value`/`eval` read its variable (`.kb/dynamic-special-variables.md`, "One home").
- **JVM trap**: `_writeString`'s socket probe indexes `_streams` from the raw handle BEFORE
  delegating to `_writeStr` (where stderr lives). `<clinit>` allocates `_streams` whenever
  `usesErrorOutput`. Do not make it lazy again.
- Tests: `LispEvaluatorTest#standardStreamVariablesAreBoundToTheirDefaultsThroughTheSymbolApi`, the
  `standardStreamVariables*` pair in the two compiler tests, ci-spec `symbol-runtime-api`,
  `LackEcosystemE2eTest#backtraceMiddlewareReportsTheApplicationsRealError*`,
  `ServeConditionCatchComponentE2eTest` (opt-in, `.kb/lack.md`).

## Runtime-interned symbols as function designators
Clack's handler protocol is late-bound by NAME, so the compile paths carry a full symbol-to-function
route (the interpreter resolves against the live environment):

- **2-arg `(intern name pkg)` lowers like 2-arg `find-symbol`** (`expandInternInPackage`). A
  designator naming no package SIGNALS (literal: a `%package-error` stub; computed, nil included:
  behind a `(find-package p)` guard, since building the spelling unguarded CREATES the package). The
  stub is built during body compilation, after the condition scans, so `PACKAGE_ERROR_SITES` stands
  in for the tag in `conditionNarrowing` and wasm's `usedLayoutTags`. Pinned by ci-spec `intern-into-a-missing-package`,
  `{LispEvaluatorTest,JvmLispCompilerTest,WasmLispCompilerIntegrationTest}#internIntoAMissingPackage*`.
- **Alias rows for internal names in the `_lookup` registries** (`JvmEvalRuntimeBuilder.lookupSegments`,
  `WasmLispCompiler`'s registry-blob loop): a runtime-built spelling in a pre-seeded package, or in a
  program package whose row a missed site did not carry, is single-colon, so every `PKG::NAME` defun
  also answers to `PKG:NAME`, appended after the base rows (genuine keys win). Emitted only with the
  registry (`usesRuntimeFunctionDesignator` / `usesEval`) and only when the alias SPELLING can reach
  the run time (a builder, the reader under `--dynamic`/`anyNameResolvable`, or this compile spells
  it). Pinned by `WasmLispCompilerTest.theRegistrysSingleColonAliasShipsOnlyWhereItCanBeSpelled`.
  **Variables get no alias**: an internal special resolves through `_genv` only where the build spells
  it `PKG::NAME` (a program package).
- **Class names get the same widening at the LOOKUP**: every generated class-designator dispatch
  matches BOTH colon spellings (`LispMacroExpander.addDesignatorSpellings`, ungated, `.kb/clos.md`).
- **`_apply`'s symbol miss is LOUD** (`The function X is undefined` / trap), as the tree-shaker
  carve-out (`.kb/library-defun-pruning.md`) promises.
- **Computed `(symbol-function x)` / `(fdefinition x)` BOX the resolved funcId as a function value**,
  exactly what `#'name` gives: `functionp` t, prints its name, `funcall` dispatches; an undefined name
  signals `undefined-function` at the `symbol-function` (WASM: the arity-0 dispatcher's report; a
  name `fmakunbound` retired throws through `_undefined_function`, since the registry may still
  answer it); a special operator or macro answers its `%operator-function` ("`symbol-function` of an
  operator"). `t`, `nil` and keywords are such names. The function namespace is probed first and
  decides on its own, then `_lookup`; a global VARIABLE holding a lambda is no function binding
  (interpreter and SBCL signal). Gate `usesRuntimeFunctionBox`. The JVM probes `_fenv` only when the
  eval runtime exists (every writer forces it), so a boxing program without writers stays small
  (forcing `usesEval` cost kilobytes). **Any future lowering that synthesizes a computed `symbol-function` at
  compile-expression time must make the pre-lowering spelling count in that scan.**
- **`uiop:symbol-call` is REAL on the compile paths**: `expandUiopStubCall` lowers it to
  `(funcall (intern (string name) (find-package pkg)) args...)` over two fixed `%UIOP-SC-*` temps
  keeping the package-before-name order, INSIDE the per-expression compilers, AFTER the emission-gate
  scans, so the pre-lowering spelling counts in `containsRuntimeFunctionDesignator` and WASM
  `usesIntern`. **Any future lowering that synthesizes `intern`/`funcall`/`apply` at
  compile-expression time has the same obligation.** `#'uiop:symbol-call` is a value too:
  `uiop-package.lisp` carries upstream's `(apply (find-symbol* name package) args)` (`.kb/uiop.md`),
  and the dispatch gate probes `#:member` beside `:member` (`compiler.DesignatorSpellings`,
  `.kb/optimize-dead-code-elimination.md`) since `'#:name` reaches the function through `(string
  '#:NAME)`.
- Tests: the *InternIntoALiteralPackage* / *InternIntoAComputedPackage* /
  *InternIntoAnUnknownPackage* / *ApplyOfAnUndefinedRuntimeSymbol* groups in `JvmLispCompilerTest` +
  `WasmLispCompilerIntegrationTest`, ci-spec `runtime-intern-funcall`.

## `find-symbol`/`intern` answer the ACCESSIBILITY STATUS as a second value
**The status is a SYNTACTIC second value, not a spill value** (the `%array-disp-target` pattern,
`lowerMvProducer`): the producer lowers to the call plus a `%find-symbol-status` call over the SAME
argument temps, so nothing crosses a function boundary. CL's `intern` status is from BEFORE the
intern, and `intern` records a fresh name, so for `intern` the status is bound FIRST (interpreter
`#'intern` too: `publishSecondValue(..., secondFirst)`).

**A literal argument is passed through, never bound to a temp.** The compile paths decide both
values by folding the LITERAL name and package; a temp hid both (`CAR :INHERITED` became a built
`COMMON-LISP:CAR :INTERNAL`).

- **Interpreter**: the status half of `PackageResolver.accessible` (`memberStatus`), the same lookup
  as the value half, so the pair is nil TOGETHER (CL's invariant). `cl` exports all standard symbols
  but the `%` internals; through `cl-user` a standard symbol is `:inherited`; a recorded or defined
  name `:internal`; else nil.

**The `cl` arm admits the standard names `cl` exports WITHOUT implementing** (CLHS 11.1.2.1):
`PackageRegistry.isClMemberName`, not `isClSymbol`. `(find-symbol "BIT-AND" 'common-lisp)` is
`BIT-AND :EXTERNAL` on all four while `fboundp`, `macro-function`, `special-operator-p` answer nil --
exported is not bound (`.kb/packages.md`, "The `cl` external list is the STANDARD's list"). The
1-argument form and the `cl-user` arm stay on `isClSymbol` (nil where CL says `:inherited`),
deliberately not widened.
- **JVM + WASM**: one shared fold, `expandFindSymbolStatus` -- a constant, never a runtime call.
  What it cannot decide reports the status of the spelling the lowering builds: `PKG:` ->
  `:external`, bare -> `:internal`.
- Traps it fixed on `(find-symbol "CAR" 'common-lisp)`: under a `quote` a bare symbol IS a designator
  literal (the unwrap carries a flag); a built-in package NICKNAME is canonicalized before the
  keyword/cl/cl-user arms (`canonicalDesignator` -> `PackageRegistry.canonicalBuiltinName`); a literal
  `cl` designator takes the computable answer, not the build.

**A user package using `cl` answers a standard symbol it does not own as `:inherited`** (the
inherited arm walks the use list, `cl` included). sxql's `find-make-op` probes `MAKE-COUNT-OP`, no cl
symbol, so the compiled build path is unchanged; tripwire
`MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends`.

`symbol-plist`/`remprop`: prelude entries over the `%symbol-plists` store `get` uses, each with its
OWN `(defvar %symbol-plists nil)` (`defvar` assigns only when unbound, so one table). No
`(setf symbol-plist)`; `remprop` answers `t`/`nil` where SBCL answers the plist tail. Pinned by
`LispEvaluatorTest#{rempropDropsOnePropertyFromThePlist,
findSymbolAnswersTheAccessibilityStatusAsItsSecondValue, symbolPlistReadsTheWholePropertyList}`, the
matching pairs in the two compiler tests, ci-spec `symbol-plist-remprop` / `symbol-runtime-api`.

## `macro-function` / `special-operator-p` PARTITION the operators
**Invariant: every operator with no function value is either a special operator or a macro, on all
four, and the two predicates agree on which.** `special-operator-p` is t for exactly the 25 ANSI
special operators (`PackageRegistry.ansiSpecialOperatorNames()`); `macro-function` is non-nil for
everything else rontolisp expands (`runtimeMacroNames()`: `specialOperatorNames()` minus those 25,
plus the program's `defmacro`s). Deliberately NOT rontolisp's own special-form/macro boundary
(`defun`, `handler-case`, `dolist`, `lambda`, `in-package` are special forms HERE): a caller asks
only "may I `apply` this name". Verified against SBCL 2.2.9.

**`while` is the ONE exception, a name-space one.** `macro-function` answers NIL for it
(`PackageRegistry.namesWithoutMacroFunction()`, read by `runtimeMacroNames()` and
`LispEvaluator.isMacroName`): iterate's `walk` asks `(macro-function (car form))` before its own
clauses and a yes refused `(iter ... (while test))`. **Rule: a name CL does not have must not claim
a macro function.** Nor does a CL FUNCTION in `CL_MACROS` only because the expander rewrites it
inline (`typep`, `format`, `error`, ... -- `inlineExpandedClFunctions()`), nor `declare`: iterate
skipped clauses inside every `typep`. SBCL answers nil for all.

**`macroexpand-1` expands exactly what `macro-function` claims** (`macroexpand1` gates on
`isMacroName`). A natively-run CL macro expands through `LispMacroExpander.expandNativeMacro`: the
lowering every backend already has, or CL's own (`return` -> `(return-from nil ...)`). Kept out of
`expandBuiltinMacro`, which feeds passes that keep these forms native. A walker EMITS what it gets,
so the expansion must run on every backend, and must be READABLE: `%block` and `while` answer as
`(block nil ...)` and a `tagbody` loop (`withStandardIteration`; arnesi's walker refused a `dolist`'s
`return` with no block). Ci-spec `macroexpand-of-iteration-answers-standard-operators`.
`handler-case` and the definers expand to internal forms `foldNativeExpansion` turns back:
`(%handler-case form 'type #'(lambda ...)...)`, `(%defun 'name #'(lambda ...))`, `(%definition 'form)`
for the `QUOTED_DEFINERS` (their grammar a walker cannot read). Interpreter folds in
`rareOperatorExpansion`; compile path in `UserMacroExpander` right after a user macro expands (the
only producer), so no surface scan ever sees the internal head. CL's own `handler-case` expansion
(`handler-bind` + a cross-lambda `go`) was rejected: a `handler-bind` puts the whole program in
restart mode (`.kb/error-handling.md`, "The restart-mode gate") and the `go` forces EH mode on WASM.
The compiled `eval` runtime does not fold them (`doc/en/guides/eval-limitations.md`). Pins:
`IterateE2eTest`, `LispEvaluatorTest#macroFunctionAndSpecialOperatorPPartitionTheOperators`, ci-spec
`macro-function-and-special-operator-p`.

**One definition per predicate, shared by every backend, no compile-time fold** (name table
GENERATED from `PackageRegistry`). A literal
`(special-operator-p 'if)` compiles to a call -- two paths that can disagree is what this removes.
**Re-evaluate when** a program is measured to care: fold the LITERAL case in `LispMacroExpander`,
never per backend.

**The interpreter answers `macro-function` natively; the compile paths answer a name test.** The
interpreter's value is the REAL expander (`macroCallForm` re-heads the form), or what a
`(setf macro-function)` stored. Compiled, it answers `#'%macro-expander-stub`: non-nil, signals when
CALLED. `UserMacroExpander.emitMacroFunctionTable` APPENDS a `macro-function` defun over the
program's macro names when the program names `macro-function`, suppressing the prelude entry; with
a `(setf macro-function)` it reads the writes first (`.kb/defmacro-backquote.md`).

**The VALUE is a function of EXACTLY two arguments** (CLHS 3.2.1: form and environment); 0, 1 or
more than 2 signal `program-error` (SBCL agrees; ANSI `def-macro-test`). The interpreter checks
`callArgs.size() != 2` (a raw `LispFunction`); the compiled stub has no `&optional`, so the ordinary
arity check (`ClosRegistry.arityMessage`) answers before the body's "cannot expand at run time".

**Two load-bearing emission details, because the BACKENDS resolve the program a second time**: the
table is APPENDED (after every `defpackage`), and an unqualified name is spelled `cl-user::name`
(a bare `x` would be re-qualified into the trailing `in-package`'s package).

`macroexpand-1`/`macroexpand` answer CL's `expanded-p`: interpreter via `%mv-spill`
(`expandedWithFlag`; identity decides), compile paths from `UserMacroExpander.expandAll`'s literal
fold.

**The two must not contradict each other.** For a COMPUTED `macroexpand-1` argument the compiled body
consults `macro-function`: a macro call SIGNALS, anything else returns unchanged with `expanded-p`
nil. Answering a macro call with itself spins the standard
`(do ((step form (macroexpand-1 step))) ((not (macro-function (first step)))) ...)` loop forever. So
a surviving `macroexpand`/`macroexpand-1` also triggers `emitMacroFunctionTable`
(`usesMacroIntrospection`). Mechanics: `.kb/gensym-macroexpand.md`.

`(setf (macro-function name) function)` must NOT be walked as a call (`.kb/defmacro-backquote.md`);
`expandAll`'s `SETF` case returns it verbatim.

### `symbol-function` of an operator
**A special operator or a macro is fbound, and `symbol-function`/`fdefinition` answer one function
per name that signals `undefined-function` naming it when called, on all four** (CLHS leaves it to
the implementation; SBCL's `(:special if)`/`(:macro or)` are one per name and signal the same
class). typo keys a hash table by it (`ensure-fdefinition`).

- **Interpreter**: `LispEvaluator.symbolFunction` asks `ansiSpecialOperatorNames`/`isMacroName`
  first, one `LispFunction` per name (`operatorFunctions`).
- **Compile paths**: the prelude's `%operator-function` asks `special-operator-p`/`macro-function`,
  so the three answers agree by construction. Asked on a registry MISS only: a found function pays
  nothing, the tombstone still decides first. A form operand is bound first
  (`OperatorFunctions.throughVariable`; the miss arm reads it again). A literal `(symbol-function 'op)`
  is lowered by `UserMacroExpander`, the one pass that knows the program's macros.
- **Gate** (`OperatorFunctions.reached`: a computed operand of `fboundp`/`symbol-function`/
  `fdefinition`, one of them or `compile` as a value, a helper call). Every example program that
  gains it evaluates data, so narrowing the table to names data spells would still leave it whole.
  Cost: `net/hello-clack` +0.4%; a 2-line computed-`fboundp` program +70 KB class / +40 KB module
  (the `macro-function` table). **Re-evaluate when** a program with no data evaluator is measured to
  care -- then narrow, keeping `macro-function` the one table.
- Residuals: a literal `(fboundp 'name)` of a name only a run-time `(setf macro-function)` defines is
  NIL compiled (the fold), a computed one T. A `defun` after a `defmacro` of the same name leaves the
  macro on all four (SBCL replaces it).
- Pinned by ci-spec `macro-function-and-special-operator-p` (SBCL agrees).

Tests: `LispEvaluatorTest#macroFunctionAndSpecialOperatorPPartitionTheOperators` (incl. `while`) /
`#macroFunctionIsTheRealExpanderOnTheInterpreter` (incl. arity) / `#macroexpand1AnswersTheExpandedPFlag`,
the matching pairs in `JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` (incl.
`{compileAndRun,compile}MacroFunctionValueArityMismatch`), and the `macro-function` ci-spec case.

Function-count pins (ci-spec + LispEvaluatorTest + JvmLispCompilerTest x2 +
WasmLispCompilerIntegrationTest) move with each group of new built-ins.
