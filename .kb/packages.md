# Packages

Three built-in packages: `cl`, `cl-user` (default, uses `cl`), `rontolisp` (does NOT use `cl`).
One read/compile-time pass, `PackageResolver` (root `am.ik.rontolisp`), runs before the evaluator
and both compilers and rewrites every form into a canonical shape. Packages come in
two tiers: the read/compile-time tier (the built-ins, plus every `defpackage` the
COMPILE path resolves -- resolved statically, immutable at run time) and the runtime
tier (`make-package` products, and every `defpackage` the INTERPRETER resolves --
a live table on every backend). The runtime tier is `.todo/741`'s design decision;
its mechanics are the "Runtime tier" section below.

**Canonical shape**: bare names for `cl`/`cl-user` symbols; `pkg:name` external, `pkg::name`
internal -- so canonical forms re-resolve to themselves. `*package*` stays the bare cl variable,
read at RUN time. `(in-package P)` is consumed and replaced by `(setq *package* :P)`.

**Canonical shape**: bare names for `cl`/`cl-user` symbols; `pkg:name` external, `pkg::name`
internal -- so canonical forms re-resolve to themselves. `*package*` stays the bare cl variable,
read at RUN time. `(in-package P)` is consumed and replaced by `(setq *package* :P)`.

Hard errors (`LispPackageException`): an unqualified `cl` symbol in a package that does not use
`cl`; a single-colon reference to a non-external member. Adding a package is a registry change, not
a resolver change; the registry is per-resolver-instance, and the backends only ever see canonical
strings, so no per-backend codegen exists.

## External vs internal (`:` / `::`)
- `LispPackage` carries an `externals` set (3-arg constructor = everything exported);
  `PackageRegistry.QualifiedName` carries an `internal` flag parsed from the double colon.
- `cl` exports `CL_EXTERNALS` (= `CL_SYMBOLS` minus the `%`-prefixed `CL_INTERNALS`, plus
  `CL_EXPORTED_ONLY`; car/cdr compositions via `isCarCdrComposition`); `cl-user` exports nothing;
  `rontolisp`/`java` export everything registered.
- `pkg::anything` interns permissively; an unregistered member is internal. The JVM method-name
  mangler maps each `:` to `$colon`.
- `PackageRegistry.CL_SYMBOLS` = union of `CL_SPECIAL_FORMS`/`CL_MACROS`/`CL_FUNCTIONS`/
  `CL_VARIABLES`/`CL_INTERNALS`/`CL_TYPES`/`CL_CONDITION_TYPES` (single source of truth).
  `CL_CONDITION_TYPES` is `Set.copyOf(ClosRegistry.CONDITION_CLASS_NAMES)`, so a condition class is
  a `cl` symbol by construction -- required, because a RUNTIME `(typep c ty)` matches the
  registry's PLAIN class name by spelling. Condition names are external and non-callable.

## The `cl` external list is the STANDARD's list, not the implemented one
CLHS 11.1.2.1 fixes the `common-lisp` package's external set at the **978 standard names**,
independently of whether an implementation has an operator behind one: the name being there is a
`find-symbol` / `do-external-symbols` answer, not a promise that calling it works. The 187 names
rontolisp does not implement are `PackageRegistry.CL_EXPORTED_ONLY` -- plain strings, exactly
because there is no implementation to name, and a name that gains one moves OUT of the set into
its category.

**The split is the whole design: exported is not the same question as owned.**
`CL_EXPORTED_ONLY` joins the package's `externals` (and its `symbols`, so externals stay a
subset) but NOT the static `CL_SYMBOLS` that `isClSymbol` reads -- and every RESOLUTION decision
reads `isClSymbol`. So nothing about resolution moved: a bare `find-method` still interns in the
current package, `(defun find-method ...)` still does not meet the `cannot redefine the standard
operator` guard (`.kb/lisp2-namespaces.md`), `LibraryDefunPruner`'s reference scan still does not
see the name as resolvable (`.kb/library-defun-pruning.md`), and `(find-method x)` still signals
`undefined-function` (the eleven `bit-*` operators left this set for `CL_FUNCTIONS` in
`.todo/043`, so a bare `bit-and` now resolves to the prelude defun). The wider question -- "does `cl` ANSWER for this name?" -- is
`isClMemberName`, and only the symbol API asks it: `PackageResolver.memberSpelling` /
`memberStatus` and the compile paths' literal `find-symbol` fold
(`LispMacroExpander.expandFindSymbolInPackage` / `clPackageStatus`), which must move together or
the interpreter and the compiled backends disagree. `fboundp` / `macro-function` /
`special-operator-p` are untouched and answer nil, which is the point.

`cl:find-method` resolves (to the bare name) where a non-external name is a `not external`
package error -- the symbol exists, so a single colon reaches it.

Measured on the ANSI suite (interpreter, 2026-09-13, suite `ca06bd9`): **+187 tests, 0 regressed**
(13,371 -> 13,558 of 19,482; `symbols` 214 -> 27 failures), the whole of
`symbols/cl-symbols.lsp`'s `test-if-not-in-cl-package` row and nothing else. The tests that ask
the same question through `(find-symbol x "CL")` -- `functionp.4`, `function.4`,
`cl-function-symbols.1`, `cl-macro-symbols.1`, the `pprint-dispatch` family -- did not move:
each is blocked by a missing OPERATOR, which is the distinction this change draws.

**`boole-3` .. `boole-16` were invented spellings** of the standard's `boole-and` / `boole-ior` /
... constants (CLHS 12.1.4); `.todo/803` renamed them in `LispNames` and `ClConstants` (values
kept in the same 1..16 assignment, boole-1/boole-2 unchanged) and dropped the fourteen correct
spellings from `PackageRegistry.CL_EXPORTED_ONLY`, where `.todo/796` had had to list them as
missing standard names while the invented spellings sat beside them. `boole` itself stays
unimplemented (still in `CL_EXPORTED_ONLY`) -- a prelude defun over these constants belongs with
`.todo/037`'s `logeqv`/`lognor` row.

**One name still makes `no-extra-symbols-exported-from-common-lisp` fail**, and it is not a name
list bug: `while` is rontolisp's own loop primitive and lives in `cl` because that is where the
built-in operators live. Moving it to another package would make bare `(while ...)` stop resolving
in `cl-user`, which is a LANGUAGE change and a documentation change, not a registry one --
`PackageRegistry.NO_MACRO_FUNCTION` already carries the note that `while` is not a CL name at all.
This is accepted as the price of the extension. `PackageRegistryTest` pins the external set
against the standard's own checked-in list (`src/test/resources/cl-standard-symbol-names.txt`) in
BOTH directions, listing `while` by name, so neither a missing standard name nor a new extension
can arrive unnoticed.

## `defpackage`
A literal, top-level directive like `in-package`. `PackageResolver.resolveDefpackage` registers a
`LispPackage` (exports = owned + external) and replaces the form with the package KEYWORD
(`:NAME` -- the value `make-package` and `find-package` answer, so the three are `eq`; it was a
quoted `NAME` symbol until `.todo/917`); it does NOT switch the current package. It is in `CL_SPECIAL_FORMS`. Designators: keywords, bare
symbols, strings, `#:name` (stripped in `designator`) and CHARACTERS (`#\H` is the string
designator for `"H"`, CLHS glossary -- the ANSI chapter spells every clause that way at least
once); `:documentation`/`:size` ignored.
- `(:intern name...)` adds the names to the package's OWN symbols without exporting them, so
  `pkg::name` reaches them and `pkg:name` does not. Resolution here is textual, so owning the
  name is the whole of the clause -- except a name a used package exports, which stays
  inherited (below).
- `(:shadow name...)` -> `LispPackage.shadows`; `resolveUnqualified` checks `current.shadows(name)`
  BEFORE the `isClSymbol` branch, and `evalCons` dispatches on the FULL resolved name, so a
  shadowed `pkg::defconstant` reaches the user macro, not the special form.
- `(:shadowing-import-from PKG name...)` is recorded as an IMPORT (shares `collectImportFrom` with
  `:import-from`, shadowing entries merged LAST) and its names join `shadows`, so
  `package-shadowing-symbols` lists them. **The imports map is the FIRST thing
  `resolveUnqualified` consults** -- before the shadow set, the cl table and the use list -- which
  is CL's always-wins precedence. Pinned by `shadowingImportFromWinsOverTheUseList`.
- **A `defpackage` over an EXISTING package MODIFIES it** (CLHS 11.1.2.1): use list, exports, owned
  symbols, imports and shadows unioned; an existing `:nicknames` entry is a re-declaration.
  Required because rontolisp PRE-SEEDS its shim libraries' packages (`.kb/cffi.md`). A name that is
  another package's NICKNAME stays a hard error.
- Hard errors: `:use`/`:import-from`/`:shadowing-import-from` of a nonexistent package;
  `:nicknames` colliding with a DIFFERENT package/nickname; any other clause. No `:use` clause =
  empty use list (SBCL-like).
- **Clause validation (`.todo/923`, SBCL-checked)**: `validateDefpackageShape` runs on the FORM
  before any lookup (SBCL does it at macroexpansion) -- every option a known keyword clause,
  `:size`/`:documentation` at most once with one argument, and the names of `:shadow`,
  `:shadowing-import-from`, `:import-from`, `:intern` pairwise disjoint, `:intern` and `:export`
  too. Every clause failure is a `DefpackageException` (a `LispPackageException`, so top-level
  callers and the prelude's fallback see no change) typed `PROGRAM_ERROR` (the shape rows) or
  `PACKAGE_ERROR` with a designator (missing package; nickname collision, slot = the package
  being defined, as SBCL).
- **`:intern` of an INHERITED name finds it** (CLHS: `:intern` runs after `:use`, "found or
  created"): a name some used package exports is dropped from the owned set after the clause
  loop, whatever the clause order. `pkg::name` still mints `PKG::NAME` for any inherited non-cl
  name (the pre-existing `resolveQualified` rule); `find-symbol` answers the inherited one.
- **An `:import-from`/`:shadowing-import-from` of a name the source lacks is refused only for a
  SEALED source** (`PackageRegistry.sealed`): made by `defpackage`/`make-package` and never read
  into since. A symbol merely READ is not recorded ("The member table"), so once source minted an
  unrecorded name in a package (`resolveUnqualified`'s final intern, a quoted cl name in a non-cl
  package, a `pkg::name` the package does not provide -- all skipped under `exactCase`, i.e. the
  runtime `intern` and `find-symbol` probes) its table no longer answers "no such symbol" and
  the check is off. Built-in / pre-seeded packages are never sealed. The failure carries the
  source package and name (`DefpackageException.missingSymbol`). A pruned third-party
  `defstruct` leaves its `%struct-definition` marker in the stream so this check stays off for
  a kept defpackage that imports one of the struct's slot names (.kb/library-defun-pruning.md).
- **At run time the failures are conditions**: a nested `defpackage` (`rareOperatorExpansion`)
  and `(eval '(defpackage ...))` (the `eval` native, which would otherwise take the top-level
  directive path) both go through `LispEvaluator.registerRuntimeDefpackage`: `PROGRAM_ERROR` ->
  `program-error`, `PACKAGE_ERROR` -> `signalPackageError`, and the missing-symbol case signals
  inside `(restart-case ... (continue () :report "INTERN it." nil))` -- continuing interns the
  name in the source (recorded) and retries the registration, SBCL's behaviour. A program's own
  TOP-LEVEL `defpackage` stays a hard error. The compiled backends refuse a non-top-level
  `defpackage` anyway, so the conditions are interpreter-only by construction.
- **A NON-top-level `defpackage` is left VERBATIM** by `resolveCons` -- clauses and all, since
  they are literal data the registration reads rather than code -- and registers when its
  enclosing form RUNS: `LispEvaluator`'s `rareOperatorExpansion` sends it back through
  `resolve`, which is the same entry the top-level directive takes, and the product joins the
  runtime tier. That is what makes the ANSI chapter's `set-up-packages` shape work (a defun that
  deletes its packages and defines them again) and what `(eval '(defpackage "H"))` does. The
  compiled backends have no registry to register into, so `Jvm`/`WasmExprCompiler` refuse the
  form where they meet it, which is where the old resolver-time hard error moved to.

## `define-package` (uiop / mgl-pax variant)
A literal top-level `(uiop:define-package ...)` / `(mgl-pax:define-package ...)` -- the qualifier is
REQUIRED and must canonicalize to `UIOP` or `MGL-PAX` (`isDefinePackageOperator`); a bare
`define-package` stays a user symbol -- is consumed exactly like `defpackage`. One variant-only
clause, `(:use-reexport PKG...)` (`resolveDefpackage(cons, true)`); in a plain `defpackage` it is a
hard error, as are `:mix`, `:recycle`, redefinition tolerance. `mgl-pax:defsection` is consumed
ADDITIVELY: its `(SYMBOL LOCATIVE)` entries are exported from the current package
(`consumeDefsectionExports`) -- trivial-utf-8's ONLY export mechanism. Pinned by
`definePackageIsConsumedLikeDefpackage`, `definePackageUseReexportUsesAndReexports`,
`aBareDefinePackageIsNotTheVariant`, `paxDefsectionExportsItsEntries`.

## Nicknames
Instance `nicknames` map seeded from static `BUILTIN_NICKNAMES`: `common-lisp` -> `cl`,
`common-lisp-user` -> `cl-user`, `rl` -> `rontolisp`, `la` -> `linalg`, `quicklisp` -> `ql`. Seeded
names are reserved. User `:nicknames` stay instance-only.

**`splitQualified` normalizes a built-in nickname in the package part** (static
`canonicalBuiltinName`) -- what makes nicknames work on the compile path, because the
pre-resolution passes match `qn.pkg()` against a canonical literal and run BEFORE the resolver: the
library splice scanners (`JsonLibrary`/`LinalgLibrary`/`UrlLibrary`/`VecLibrary`/`UsocketLibrary`/
`WitLibrary`/`HttpLibrary`), `LoadInliner`'s `ql:quickload`, `HttpHandlerInliner`, the
`wasm-import`/`wit-import`/`wit-export` matchers, `LispMacroExpander.isAsyncSugarHead`. Without it
`rl:json-parse` never triggered the json.lisp splice (interpreter unaffected -- it lazy-loads
post-resolution). Pinned by `JvmLispCompilerTest.compileAndRunBuiltinNicknamesTriggerLibrarySplice`.

**Package-local nicknames are GLOBAL nicknames**: `(:local-nicknames (nick actual)...)` and
`uiop:add-package-local-nickname` both funnel into `PackageResolver.registerLocalNickname`; a
literal top-level call is CONSUMED like a defpackage, so the idiom works on every backend, while a
non-literal call stays a runtime call (interpreter only).
`uiop:remove-package-local-nickname` is the undo both ways: a literal top-level call is
consumed (`tryConsumeRemoveLocalNickname`, replaced by the t/nil the runtime answers) and a
computed call runs the interpreter's `removeLocalNickname` (a scope package only guards: the
nickname must point at it; removing a seeded built-in nickname is refused). The query
`uiop:package-local-nicknames` is Lisp over `package-nicknames`, so it runs everywhere and
answers every global nickname for the package. Residual, the frozen-registry rule applied to a
directive: the baked listing tables are built AFTER the whole program resolves, so on the
compiled backends they answer the END state -- a program that lists nicknames and then removes
one disagrees with the interpreter's mid-program answer there (`.kb/uiop.md`). ci-spec
`uiop-add-package-local-nickname`, `uiop-package-surgery`.

## Resolution order and imports
- `resolveUnqualified` consults, in order: `current.imports()`, the shadow set, the `cl` table,
  `current.owns()`, then the use list.
- **Use-list visibility checks `exports`, not `owns`.** Conflicts resolve first-wins in `:use`
  order (real CL signals). `resolveQualified` redirects through `imports` after the externality
  check. Uninterned `#:g1` symbols pass through unresolved, like keywords and `&`-markers.
- **An `:export` of an INHERITED name re-exports the used package's symbol**: `resolveDefpackage`
  records every exported name the package does not shadow, does not `:import-from`, is not a `cl`
  symbol, and that some used package exports, as an `imports` entry. Without it `(:use :s-sql)` +
  `(:export #:sql)` minted a distinct `POSTMODERN:SQL`.
- **The recorded entry must point at the TRUE home, not the used package** (`trueHome`): a used
  package may hold the name only as a redirect. `trueHome` follows the source's own import entry
  (one hop suffices by induction) and **also walks the source's USE list**, since CL's import works
  on any ACCESSIBLE symbol; when the source neither owns nor imports the member it recurses into
  the first used package that exports it. Same guard for `:import-from`. Pinned by
  `PackageResolverTest#{reExportOfARedirectedMemberRecordsItsTrueHome,importFromARedirectedMemberRecordsItsTrueHome}`.

## Quoted data resolves against the current package
`resolveCons` sends every `(quote DATUM)` through `resolveQuotedData` -- what CL's reader does when
it interns a quoted datum's symbols in `*package*`. Covers backquote templates, a lone quoted
symbol, and a quoted DATA TABLE whose symbols name functions or macros.

**Data position is more permissive than code position** (`inQuotedData`): a `cl` symbol quoted in a
package that does not use `cl` is that package's own symbol (`'(car x)` under `(in-package
:rontolisp)` = `(RONTOLISP::CAR RONTOLISP::X)`) rather than a hard error, and a quoted `*package*`
is the SYMBOL. ONE exemption, host-facing data (`inHostFacingData`): a
`rontolisp:wasm-import`/`wasm-export` directive's options are export field names or WIT types, but
its quoted NAME argument does name a function, so `resolveWasmDirective` resolves it like a defun
name (`.kb/wasm-import.md`).

## Directives consumed at read/compile time
Their effect is consulted by `resolveUnqualified` as the resolver walks, so a runtime-only effect
would be invisible to the forms it affects -- and invisible entirely on the compiled backends. Each
is consumed by `PackageResolver.resolve` and (except the two uiop nickname calls, which the macro
evaluator's resolver consumes in passing like any other form) listed in
`UserMacroExpander.isPackageDirective` (the macro pass tracks the same state and keeps the form
verbatim). Each takes LITERAL designators; a
computed call falls through to an interpreter-only runtime function using the SAME resolver.

| directive | resolver entry | notes |
|---|---|---|
| `use-package` (`LispNames.USE_PACKAGE`) | `usePackage` | replaced by `T`; only EXTERNAL symbols inherited; using a package in itself is an error |
| `unuse-package` (`LispNames.UNUSE_PACKAGE`) | `unusePackage` | the inverse, replaced by `T`; unusing what is not used is a no-op; the implied uses go with the package that implied them |
| `export` / `unexport` | `exportSymbols` | export also records the same re-export redirect the `:export` clause does |
| `import` | `importSymbols` | same `imports` redirect (member -> `trueHome`) as `:import-from`; the argument keeps its QUALIFIER; an UNQUALIFIED argument is a no-op, as in CL |
| `uiop:add-package-local-nickname` | `registerLocalNickname` | see Nicknames |
| `uiop:remove-package-local-nickname` | `removeLocalNickname` | see Nicknames; replaced by `T`/`NIL` |

All are CL FUNCTIONS, hence usable as function values. ci-spec `use-package`,
`unuse-package`, `export-and-unexport`.

**On the COMPILED backends the use-list pair has no runtime form**, and that is one decision,
not two: a literal top-level `use-package` is consumed here, so a computed one has nothing left
to do -- and `unuse-package` must match it or the halves disagree. Both lower through
`LispMacroExpander.expandRuntimeExport` beside `export`/`unexport`/`import` (evaluate the
arguments, answer `t`). `use-package` used to have no case at all there and signalled
`undefined function`; it joined the group with `unuse-package`.

**A directive changes ACCESSIBILITY, never IDENTITY. The SPELLING is the package's DECLARED
external set.** A symbol IS its canonical spelling here, so deciding the colon from the LIVE set
meant `export` re-keyed the symbol underneath definitions already made under it --
`The function SPIKE:MY-FN is undefined`, silently, on all four backends, for the everyday CL file
shape. `PackageResolver.declaredExternals` captures a package's external set the first time an
`export`/`unexport` touches it; `spellsExternal` (behind `canonical` and `spellsAsExternal`) reads
THAT, while `isExternal` -- the single-colon accessibility check and the use-list visibility test --
keeps reading the LIVE set. Consequence: a LATE-exported symbol prints `PKG::NAME` where CL prints
`PKG:NAME` (`.kb/pretty-printer.md`). A package the directives never touch has no entry, so the
`defpackage`-only corpus resolves byte-identically. ci-spec `export-after-the-definitions`.

**A COMPUTED `export` never reaches the resolver**: only a LITERAL export folds
(`tryConsumeExport`). The RUNTIME designator route works (`.kb/symbol-runtime-api.md`), so the gap
is purely compile-time spelling; deliberately not widened, because the widening would accept ANY
`pkg:x` and swallow typos.

## Registry queries
`list-all-packages`, `package-use-list`, `package-used-by-list` share one table,
`PackageResolver.runtimePackageUseTable()`. The interpreter reads the LIVE registry; the compile
paths bake it into `Ctx.packageUseTable` beside `packageTable` and lower the calls in
`LispMacroExpander.expandPackageQuery` -- a constant for `list-all-packages` and a LITERAL
designator, otherwise an `assoc` keyed by the name `find-package` answers. A "package" is its
keyword, so all three answer lists of keywords. `package-shadowing-symbols` answers the package's
shadowing symbols in name order -- a native over the live registry on the interpreter
(`PackageResolver.shadowingSymbols`), a `LispPreludeLibrary` defun over the baked row's packed
shadows or the runtime entry's shadow list on the compiled backends ("Runtime tier"). All five
are CL FUNCTIONS. When the program can create packages
at run time the three lowerings union the `%runtime-packages%` table in (sorted, like
the interpreter's registry order); a literal `package-use-list` of a static package
still folds, but a literal `package-used-by-list` stays a call -- runtime users would
be missed otherwise.

**Divergence**: the baked table is frozen at compile time, so a package a compiled program creates
later is invisible there -- the same freeze `find-package` has. A COMPUTED `(find-package x)`
becomes an `assoc` over `runtimePackageTable()` / `PackageRegistry.designatorTable()` (canonical
names plus every nickname, each also uppercase), read AFTER `resolveProgram`
(`.kb/symbol-runtime-api.md`). ci-spec `package-registry-queries`.

**That lookup is one `%find-package` defun per program**, prepended by the backends after
`injectBakedPackageTable` (`LispMacroExpander.injectFindPackageHelper`) when a `find-package`,
`package-use-list` or `package-used-by-list` survives resolution; every site -- the
`%symbol-in-package` guard and the package-query lowerings included -- calls it
(`expandRuntimeFindPackage` with `ctx.functions::containsKey`; a site without the helper stays
inline). Inline, the quoted table was built at EACH site: a quote datum is memoized per identity,
and every site made a new one. Measured 2026-09-26 (CLI, per additional site): JVM 2,494 -> 42 B,
WASM ~1,600 -> 15 B; with runtime packages JVM 2,954 -> 55 B, WASM ~2,300 -> 16 B. The mito probe
(`ql:quickload "mito"` + `table-definition`): JVM 9,670,156 -> 9,600,823 B, component
7,227,396 -> 7,174,018 B. Pinned by `aComputedFindPackageSiteDoesNotCarryItsOwnCopyOfThePackageTable`
in `JvmLispCompilerTest` and `WasmLispCompilerTest`.

**There is no package introspection, deliberately.** `rontolisp:list-functions`/`list-macros`/
`list-special-forms`, `PackageIntrospection`, `Jvm`/`WasmIntrospectionCompiler`,
`clMacroNames`/`clSpecialFormNames`, `Environment.globalFunctionNames` are GONE: the listings were
never complete and every name added to `CL_FUNCTIONS`/`CL_MACROS`/`CL_SPECIAL_FORMS` broke four
pinned expectations plus the doc pages. Classification stays visible only at `#'name`,
`special-operator-p`, `macro-function` and the reference docs. **Do not bring the listings back.**

## Pre-seeded shim packages with redirects
- **`closer-common-lisp` (nickname `c2cl`) is a FLAT RE-EXPORT package**: the `cl` externals
  overlaid with `CLOSER_MOP_EXTERNALS` (closer-mop wins collisions); every member is in
  `LispPackage.imports` pointing at its HOME package, and the package owns nothing. **Using it
  implies using `cl`** (`PackageResolver.withImpliedUses`, because cl visibility is judged by a
  DIRECT use). **The use-list loops in `resolveUnqualified` redirect through a used package's
  `imports` map** (`usedExport`) -- otherwise a re-exported member inherited through `:use`
  resolves under the re-exporting package's spelling, a latent bug for ANY re-export package.
  `C2CL` lives here; `C2MOP` stays on `closer-mop`. Pinned by
  `PackageResolverTest#{closerCommonLispQualifiedMembersResolveToTheirHomePackages,usingCloserCommonLispMakesClAndCloserMopVisible,useListReExportResolvesToTheHomePackage}`.
- **babel** records its babel-encodings members as import redirects, so `babel:X` and
  `babel-encodings:X` are ONE symbol
  (`PackageResolverTest#babelSpellingsOfTheBabelEncodingsMembersResolveToTheirHome`).

## The prelude splice selects by SYMBOL, not by member name
`LispPreludeLibrary.process` runs its selection on a
`new PackageResolver().resolveProgram(program)` copy, so a reference and a definition are matched as
the symbols they resolve to. Member-name matching was a correctness bug: alexandria's own
`alist-hash-table` counted as "the program already defines this", so `(rl:alist-hash-table ...)`
compiled to an undefined function. Two deliberate asymmetries: the entry-to-entry edges of the
fixpoint stay member-matched (the prelude sources are resolver fixed points), and a program that
throws `LispPackageException` falls back to member matching. Same shape as `LibraryDefunPruner`'s
resolved copy (`.kb/library-defun-pruning.md`); the other splice pre-passes trigger on a qualified
name plus their own `in-package` tracking. Pinned by `LispPreludeLibraryTest`.

## UserMacroExpander resolves through its own evaluator's resolver
`UserMacroExpander.expand` resolves every top-level form via `macroEval.resolvePackages` before
matching, so a `defmacro` under `(in-package P)` registers its canonical qualified name.
`in-package`/`defpackage` directives (any spelling) update the macro evaluator's state but are kept
VERBATIM for the compilers' own pass. **A form the walk did not change keeps its ORIGINAL
spelling** (compared by `print()`), because a canonical form is not always re-resolvable -- a `cl:`
symbol canonicalizes to a bare name, an error under a package that does not use `cl`. Known
residue: a macro EXPANSION spliced into a non-cl-using package region hits the same error.

## `load`/`load-system` scope `*package*`
`PackageResolver` has a package stack (`pushPackage`/`popPackage`). Interpreter:
`LispEvaluator.loadFile` pushes before the per-file eval loop and pops in the `finally`. Compile
path: `LoadInliner.spliceFile` brackets a spliced file with `(%push-package)`/`(%pop-package)`
markers (`LispNames.PUSH_PACKAGE`/`POP_PACKAGE`) -- but ONLY when the file has a top-level
`in-package` (`selectsAPackage`), so a plain-defun file splices byte-identically;
`PackageResolver.resolve` consumes the markers and `isPackageDirective` treats them as directives.
Tests: `LispEvaluatorAsdfTest`, `LoadInlinerTest`.

## `*package*` is a DYNAMIC variable -- two faces, kept in step
CL reads `*package*` when a form RUNS; folding a value-position `*package*` to `(quote CURRENT)` is
right at top level and wrong inside any defun that outlives its file.
- **Value**: the package KEYWORD `find-package` answers (`:CL-USER`), so `eq` against
  `find-package`, `package-name`, an `:test 'eq` hash keyed on packages, `(typep * 'package)` and
  printing all work. `#.*package*` splices the keyword raw.
- **Resolver**: a value-position `*package*` resolves through the GENERIC cl-symbol path.
  `(in-package P)` still leaves `(setq *package* :P)` (`packageAssignment`), and the `%pop-package`
  restore leaves the same assignment for the SAVED package. Top-level forms run in resolution
  order, so the two states agree at every top-level point.
- **Interpreter**: the variable IS the resolver's current package -- ONE cell.
  `evalSymbolRef`/`symbol-value`/`boundp` read `currentPackageValue()` (before the dynamic store
  and the env), `setq` writes through `assignCurrentPackage`, `evalLet`'s binding is
  `rebindCurrentPackage` + restore in the `finally`, so a 1-argument `intern`, `read`, a lazily
  expanded macro and the value the program reads can never disagree. Consequences: a top-level
  `(setq *package* (find-package :foo))` makes the interpreter resolve the NEXT top-level form in
  FOO (the compile paths resolve the whole file up front -- documented divergence); and it is the
  one special the interpreter does NOT thread-scope.
- **Compile paths**: `LispMacroExpander.injectMvSpillGlobal` prepends `(defvar *package* :cl-user)`
  when the program READS the variable -- any mention other than the top-level `(setq *package* :P)`
  shape, or a `with-standard-io-syntax` -- making it an ordinary proclaimed special
  (`.kb/dynamic-special-variables.md`). A program that only SWITCHES packages never observes it, so
  its assignments are DROPPED and it stays byte-identical to one without `in-package` -- UNLESS its
  PRINTER observes it (`.kb/pretty-printer.md`).
- **`with-standard-io-syntax`** expands to `(let ((*package* :cl-user)) body...)`;
  `*print-escape*`/`*print-readably*`/`*print-pretty*` are honored but not rebound by it.

**A user macro called from inside a FUNCTION BODY expands with the macro's DEFINING package
current** (`LispEvaluator.UserMacro.definitionPackage`, swapped in `expandMacroCall`, restored in
the `finally`); a TOP-LEVEL macro call keeps the current package. `functionBodyDepth` (incremented
around every user-lambda body in `apply`) tells them apart. Both halves are load-bearing
(fast-http's `callback-data` vs trivia's top-level `lispn:define-namespace`). Known approximation:
a macro defined in P and called inside a function of a DIFFERENT file Q gets P where CL uses Q.
Pinned by `LispEvaluatorTest#evalMacroBodyInAFunctionBodyRunsInItsDefiningPackage`, `TriviaE2eTest`,
`SxqlE2eTest`.

## The REPL prompt IS the current package
`ReplBuffer.prompt` asks `LispEvaluator.currentPackageName()` BEFORE EVERY LINE. Both REPL drivers
take the prompt from that single method; the continuation line is blanked to the SAME WIDTH. Pinned
by `RontoLispCliTest#{replEchoesEveryValueOnItsOwnLine,replPromptNamesTheCurrentPackage}`; every
`doc/*/**` transcript shows the prompt except `compiling/self-hosted-repl.md`.

## Assorted standard names
- CL FUNCTIONS `user-homedir-pathname`, `copy-symbol`, `invoke-debugger`, `remove-method`,
  `compile-file`, `compile-file-pathname` (`LispPreludeLibrary` defuns, the last three signalling);
  CL MACROS `do-symbols`, `with-compilation-unit`; CL VARIABLES `*load-verbose*`/`*load-print*`
  (nil); constants `most-positive-fixnum`/`most-negative-fixnum`; CL TYPES `file-stream`,
  `synonym-stream`, `readtable`.
- `do-symbols` / `do-external-symbols` read `PackageResolver.accessibleSymbols` /
  `externalSymbols` on the interpreter -- two views of `accessibleEntries`: the PRESENT
  members (imports spelled at their home, then the own members the table holds), then
  the externals of every used package that are not present, **every symbol spelled the
  way code spells it** (bare for a `cl`/`cl-user` home, a re-export or shadowing import at
  its home), so an enumerated symbol is `eq` to what `find-symbol` answers for its name
  and a name accessible two ways is listed once. Until `.todo/917` the walk spelled cl
  names `cl:CAR` and re-exports under the re-exporting package, which is what
  `%package-spelling-normalize` existed to undo; the baked rows now carry the code
  spellings and the normalizer is an idempotent no-op for them. On the compiled backends
  they lower through the `%do-symbols-list` prelude helper over the baked table plus the
  runtime table (`LispMacroExpander.expandDoSymbols`, a cursor loop in the `dolist`
  shape with the implicit nil block). Both establish the implicit nil block now (the
  interpreter's `evalDoSymbols` installs it around its loop); `return` used to die there.
  `t` and `nil` come back as the singletons wherever a symbol is answered by name
  (`LispEvaluator.symbolOfSpelling`: `find-symbol`, `intern`, the enumerations), on the
  compiled backends too (`.kb/symbol-runtime-api.md`, "The spelling-identity model").
- **A `cl:`-qualified read-time constant**: `LispReader.readSymbol` substitutes
  `nil`/`t`/`pi`/`most-*-fixnum`/`array-*-limit`/`char-code-limit`/
  `internal-time-units-per-second`/`lambda-list-keywords` before ANY package resolution, so `cl:pi`
  reached the resolver as an ordinary reference; `unqualifyClConstant` strips a `cl:`/`cl::`
  qualifier for exactly that set (`CL_READ_TIME_CONSTANTS`). ci-spec `missing-cl-names-443`.

## Runtime tier (`.todo/741`)
`make-package` / `delete-package` / `rename-package` / `packagep` /
`package-nicknames` / `find-all-symbols` / `do-all-symbols` / `apropos` /
`apropos-list` / `package-error-package` (9 CL FUNCTIONS + the macro), and since
`.todo/917` the member-table operators `shadow` / `shadowing-import` / `unintern` (3
more CL FUNCTIONS, out of `CL_EXPORTED_ONLY`) with `export` / `unexport` / `import` /
`use-package` / `unuse-package` / `package-shadowing-symbols` / `with-package-iterator`
made table-aware. The model:

- A runtime package is created EMPTY with a use list and nicknames (upcased at creation,
  the reader-canonical rule) and grows a MEMBER TABLE ("The member table" below).
  Only runtime-tier packages rename/delete;
  read/compile-time ones signal `package-error` (the baked spellings would orphan
  otherwise) -- **and which tier a `defpackage` product lands in is decided by WHO
  resolved it**: `resolveProgram` (the compile path, where the spellings are baked)
  mints read/compile-time packages, a lone `resolve` (the interpreter, resolving one
  top-level form at a time against a LIVE registry with nothing baked) mints runtime
  ones, so an interpreted program may define a package, use it and tear it down again.
  A `defpackage` MODIFYING an existing package keeps that package's tier. Names/nicknames colliding, unknown `:use` entries, unknown
  designators likewise. Every failure is handler-case-catchable, carrying the
  offending designator (upcased keyword, nil for an empty name) in the `package`
  slot -- the `PACKAGE-ERROR` seed carries `PACKAGE` + `FORMAT-CONTROL` /
  `FORMAT-ARGUMENTS` for exactly this, and `package-error-package` is the slot
  reader beside `cell-error-name`.
- Interpreter: natives over the LIVE registry (a creation is visible to later
  top-level forms; a literal designator in the SAME form stays dynamic because a
  lone `resolve()` never folds an unknown name -- only `resolveProgram` folds,
  and only when the program cannot mutate). Compiled: prelude defuns over the
  injected `%baked-packages%` table (one entry per registered package -- names and
  small cells as strings, each symbol universe as ONE length-prefixed string
  decoded by `%split-packed`, so the quoted universe costs data-segment bytes
  instead of top-level body bytes against `.kb/wasm-function-body-size.md`) plus
  the mutable `%runtime-packages%` global. The two spell the same signals with
  the same reason strings.
- The gate is one predicate everywhere: `resolveProgram` records
  `runtimePackagesMutable()` (a `make-`/`delete-`/`rename-package` reference
  outside quoted data), the `Ctx` flag follows it, and the find-package /
  query / find-symbol / intern / status lowerings consult the runtime table only
  then -- any other program keeps the baked-only lowerings and stays
  byte-identical. The prelude support entries (`%runtime-packages%`,
  `%do-symbols-list`, `%split-packed`, `%package-symbols-where`,
  `%package-spelling-normalize`, `%baked-import-redirect`, `%baked-package-find`,
  `%runtime-package-find`) are selected by surface reference
  (`referencedBySurfaceForm`) and rooted in `LibraryDefunPruner` the same way,
  so a lowering's helper can never be missing.
- The enumeration universe (`do-symbols` lowering, `find-all-symbols`,
  `apropos-list`, `do-all-symbols` expansion, `with-package-iterator` through
  `%package-iterator-entries`) is one walk: `%package-symbols-where` over
  `%do-symbols-list` rows, deduplicated by content. The rows carry code spellings
  (`accessibleSymbols`, above), so `%package-spelling-normalize` no longer changes
  anything for them. Keywords are never listed (no keyword table). A runtime entry
  enumerates its member table plus the externals of its uses (a baked row's packed
  externals, a runtime entry's `:external` members).
- Residual divergences, all documented on the reference pages: `find-symbol` /
  `intern` over a computed designator naming a READ/COMPILE-TIME package build the
  permissive `PKG:NAME` spelling on the compiled backends (the unknown-name
  deviation's sibling; a runtime package answers from its member table instead); a
  computed package designator naming nothing signals a `package-error`;
  `symbol-package` on the compiled backends reads the qualifier off the spelling, so
  an uninterned symbol keeps its old home there; `unintern`'s name-conflict check
  runs on the interpreter only; `--no-gc` refuses the whole tier (no conses).

### The member table (`.todo/917`)

**A runtime package records its members, and a symbol is STILL its spelling.** The
decision the item opened -- (a) a member table beside the spelling model, or (b) a real
intern table with symbol identity everywhere -- was taken from the measurement below
and the cost of (b), which was rejected: symbol identity would touch the reader, `eq`,
every baked spelling on the JVM and both WASM backends (a WASM symbol is a string-table
OFFSET, `.kb/symbol-runtime-api.md`) and every printer table, to move the ONE test in
the chapter that (a) cannot (`import.5`, an uninterned symbol gaining a home) plus the
identity-after-`unintern` corner. `.kb/symbol-runtime-api.md`, "Is identity-by-name
stable?", stays the standing answer; re-evaluate only when a consumer needs symbol
objects.

The table IS `LispPackage.symbols` (the own present names; a mutable concurrent set
the canonical constructor makes its own, so `intern`'s write is O(1)) plus
`LispPackage.imports` (the present names homed elsewhere). Who writes it, all on the
interpreter's live registry and mirrored for the compiled backends' runtime entries:

- `intern` (`PackageResolver.internSpelling(name, record=true)` -> `recordInterned`):
  the home package of the spelling answered OWNS the member unless it already provides
  it. Only the runtime `intern` writes; **a symbol merely READ under a package is not
  recorded, deliberately** -- recording every name the resolver mints would grow every
  package's enumeration (and the baked tables) with every local variable ever read, and
  the chapter's tests that would notice (`find-symbol.11`, the `cl-user` iterator
  cases) need `in-package`, which the ANSI driver skips.
- `export` (`exportSymbols`): a symbol homed ELSEWHERE must be accessible in the target
  (imported, or inherited -- an inherited one becomes an import redirect to its home as
  it is exported); a symbol homed in the target is taken on its spelling alone (it may
  be a read-time symbol the table never saw). Two conflicts signal: a different
  accessible symbol of that name, and a USER of the target with its own symbol of that
  name present and not shadowing. `unexport` leaves an inherited symbol alone (SBCL).
- `import` / `shadowing-import` (`importInto`): an import redirect to the symbol's home
  (a bare symbol's home is `cl-user`, or `cl` for a standard name -- the old code skipped
  unqualified names); a different PRESENT symbol of that name is a conflict for `import`
  and is uninterned by `shadowing-import`, whose names also join `shadows`.
- `shadow` (`shadowSymbols`): present -> marked; absent -> a fresh own member, marked.
- `unintern` (`uninternSymbol`): removes own/import/external/shadow; an own symbol is
  TOMBSTONED (`PackageRegistry.markUnhomed`), which is what makes `symbol-package`
  answer nil and `find-symbol` stop answering it as the package's own until an `intern`
  of the name `rehome`s it (CL would mint a distinct symbol -- the documented
  deviation). A shadowing symbol hiding two different inherited symbols refuses
  (`package-error`).
- **A runtime-minted member is verbatim and never a reader-case mismatch.** The
  resolver's case-fold retries (`resolveQualified` / `resolveUnqualified`: an upcased
  source spelling reaching a lower-kebab wit-import member, `.kb/wit.md`) used to see
  only DECLARED members; with `intern` writing the owned set they would fold a later
  `Abc` or `ABC` onto a recorded `abc` -- every case-sensitive Scheme symbol lives in one
  package, and `SchemeSpecE2eTest`'s `|A:b|` came back as `a:b`. Two guards: the runtime
  `intern` resolves with `exactCase` set (no retry at all), and the retries skip members
  `PackageRegistry.isRecorded` (`intern` and `shadow` mark what they mint; `unintern`
  unmarks). Pinned by `LispEvaluatorTest#internRecordsAMemberOfARuntimePackage`.
- The literal-consumption path (`tryConsumeExport` / `tryConsumeImport` /
  `consumeDefsectionExports`) spells a bare quoted symbol the way the READER did --
  `internSpellingOnly` under the current package -- before handing it to the same
  methods; a keyword, `#:` or string designator names the target's own symbol (the
  permissive reading the name-based export always had). Every semantic failure is a
  `RuntimePackageException`, which the natives turn into a catchable `package-error`
  (`signalPackageError`).

Who reads it -- ONE lookup, `PackageResolver.accessible(pkg, name, definedProbe)`
(`Accessible(spelling, status)`), so `find-symbol`, its status, the enumerations and the
conflict checks cannot disagree: keyword; `cl` (every standard name, exported-only ones
included, plus what was interned into it); then a PRESENT symbol (`presentIn`: import
redirect at its home, own member, the interpreter's `definedInImage` probe -- a
definition is an interning); then INHERITED, the first used package in use order that
exports the name (`inheritedFrom`: cl bare, others through `usedExport`'s redirect).
`cl-user` is honest now: a standard name is `:inherited` (the exported-only ones too),
a recorded or defined name `:internal`, anything else nil -- it used to "provide every
name". `memberSpelling` / `memberStatus` are thin views of it. `accessibleEntries` is
the same rule run over the whole package (the `do-symbols` / `with-package-iterator`
universe). `symbolPackageName` reads the home off the spelling (`isClMemberName`, so
`t`, `nil` and `find-method` are `cl`'s) minus the tombstones.

On the COMPILED backends the `%runtime-packages%` entry is
`(name use nicknames members shadows)`, members a list of `(NAME SYMBOL STATUS)`
triples; the `find-symbol` / `intern` / `%find-symbol-status` lowerings route a
designator that names a runtime entry to `%runtime-member-find` / `-intern` /
`-status` (`LispMacroExpander.runtimeMemberLookup`, only under
`runtimePackagesMutable`), `export` and friends to `%runtime-package-op` (a baked
package answers `t` unchanged, `unintern` nil), and `%do-symbols-list` /
`%package-iterator-entries` walk the members. A runtime package's own symbols spell
`PKG::NAME` there, as the interpreter spells a `make-package` product's (its declared
external set is empty and pinned). `intern`'s second value is the status from BEFORE
the intern on every backend: `lowerMvProducer` binds it first for `intern`, the
interpreter's `publishSecondValue(..., secondFirst)` computes it first for `#'intern`.
`with-package-iterator` is a real iterator everywhere (`expandWithPackageIterator`: the
triples collected up front, an `flet` popping a cons-cell cursor and answering CL's four
values; no symbol type is a `program-error`). Two consumers of the consumed
`defpackage`'s new value had to learn it: `NoGcWasmCompiler.isConsumedPackageResidue`
drops a bare top-level keyword as it dropped the quoted name, and the REPL echoes
`:APP`. And `package-shadowing-symbols` joining `BAKED_PACKAGE_TABLE_USERS` exposed a
latent WASM gate gap -- the baked table plus a symbol builder in one program tripped
`fdlibm trig reached without its tables placed` -- fixed in the pre-scan
(`.kb/transcendentals.md`).

**Measured (2026-09-20, interpreter, suite `ca06bd9`, `ansi-test/measure.sh packages`):
275 -> 410 of 499 (55.1% -> 82.2%), errors 51 -> 33, fail 173 -> 56, lost forms 12 -> 12.
By test NAME: 135 fixed, 0 regressed** -- `use-package` 23, `unintern` 18,
`with-package-iterator` 16, `intern` 16, `shadowing-import` 13, `find-symbol` 13,
`shadow` 12, `find-all-symbols` 7 (the `t`/`nil` singletons), `defpackage` 6 (the
package keyword value), `import` 3, `export` 2, `unexport` 2, `do-symbols` 2,
`do-external-symbols` 1, `do-all-symbols` 1. What is left (89) and why, none of it
this table: 31 package IDENTITY (`package-name` 11 / `delete-package` 11 /
`rename-package` 9 -- a deleted package object answering nil, `eq` across a rename;
unmodellable while a package IS its keyword); 15 `defpackage` clause validation and
catchable clause errors (`.todo/923`, closed: see below); 12 `import` (10 need the driver's skipped
`in-package` -- the tests compare against `cl-test`'s home -- plus `import.5`'s
uninterned-symbol home and `import.error.4`'s restarts); 7 `def-macro-test` rows
(`macro-function` arity, `.todo/922`); 6 `in-package` as a function; 6 `%READ-EVAL`
/ `*universe*` driver artefacts; 4 restarts (`make-package.error`); 4 `go` / special
declaration scope inside `do-symbols` bodies; `find-symbol.4` (no keyword table),
`find-symbol.11` (read-time recording, above), `find-all-symbols.error.2` (the optional
package argument is a documented extension).

**Clause validation measured (2026-09-22, interpreter, suite `ca06bd9`, `measure.sh packages`):
416 -> 430 of 499 (83.4% -> 86.2%), fail 50 -> 38, errors 33 -> 31, lost forms 12 -> 12. By
test NAME: 14 fixed (`defpackage.13`-`.26`), 0 regressed.** (The 416 baseline is develop's on
that day; 410 above is the count at `.todo/917`'s close.) Premise correction: the item's plan put
the conversion in the nested-`defpackage` seam only, but `signals-error` and
`handle-non-abort-restart` run the form through `(eval 'FORM)`, which reaches the TOP-LEVEL
directive path -- the `eval` native had to route a `defpackage` datum to the same seam.
`defpackage.26` was not validation but `:intern` of an inherited name (above).

## Tests
`PackageResolverTest` (the `::` cases, the defpackage clause/error cases, the json.lisp fixed-point
pin, the `*package*` runtime-variable cases, the runtime-tier create/delete/rename/gate/baked-table
cases), `LispEvaluatorTest#{packageDefaultsToClUser,packageVarIsReadWhenTheFormRunsNotWhenItIsResolved,setqOfPackageVarSwitchesTheCurrentPackage,withStandardIoSyntaxBindsPackageToClUser,runtimeMakeDeleteRenamePackage,runtimePackageFailuresSignalCatchablePackageErrors,runtimePackageEnumeration}`,
`JvmLispCompilerTest#{compileAndRunPackageVarIsReadWhenTheFormRuns,compileAndRunRuntimePackageApi}`,
`WasmLispCompilerIntegrationTest#{packageVarIsReadWhenTheFormRuns,runtimePackageApi}`,
`PackageResolverTest#{unusePackageDirectiveNarrowsTheUseList,unusePackageAcceptsADesignatorListATargetAndRejectsUnknownNames,unusePackageWithAComputedDesignatorOrABadArityStaysARuntimeCall,defpackageNestedIsLeftVerbatimForTheRuntimeTier,defpackageInternClauseOwnsTheNamesWithoutExportingThem,defpackageDesignatorsAcceptCharacters,interpreterDefpackageProductsJoinTheRuntimeTier,defpackageClauseViolationsAreHardErrorsAtTopLevel}`,
`LispEvaluatorTest#{runtimeUnusePackageNarrowsTheUseList,nonTopLevelDefpackageRegistersARuntimePackage,defpackageClauseViolationsSignalProgramError,defpackageNicknameAndPackageErrorsArePackageErrors,defpackageImportOfAMissingSymbolOffersAContinueRestartThatInternsIt,defpackageInternOfAnInheritedNameFindsTheInheritedSymbol}`,
the member table: `LispEvaluatorTest#{internRecordsAMemberOfARuntimePackage,usePackageInheritsARuntimePackagesExports,shadowMintsAPresentSymbolAheadOfTheInheritedOne,shadowingImportDisplacesThePresentSymbol,uninternRemovesAMemberAndUnhomesTheSymbol,exportAndImportCheckAccessibilityAndConflicts,withPackageIteratorWalksTheMemberTable,doSymbolsSpellsARedirectAtItsHomeAndAClNameBare,findSymbolAnswersTheStandardSymbolsThroughAUsePackage,defpackageAnswersThePackageKeyword}`,
`JvmLispCompilerTest#compileAndRunRuntimePackageMemberTable`,
`WasmLispCompilerIntegrationTest#runtimePackageMemberTable`, ci-spec
`defpackage-use-export`, `packages-cl-user-default-uses-cl-and-the`, `runtime-package-api`,
`runtime-package-member-table`, `unuse-package`. Limitations: README.
