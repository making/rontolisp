# Packages

Three built-in packages: `cl`, `cl-user` (default, uses `cl`), `rontolisp` (does NOT use `cl`).
One read/compile-time pass, `PackageResolver` (root `am.ik.rontolisp`), runs before the evaluator
and both compilers and rewrites every form into a canonical shape.

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
- `cl` exports `CL_EXTERNALS` (= `CL_SYMBOLS` minus the `%`-prefixed `CL_INTERNALS`; car/cdr
  compositions via `isCarCdrComposition`); `cl-user` exports nothing; `rontolisp`/`java` export
  everything registered.
- `pkg::anything` interns permissively; an unregistered member is internal. The JVM method-name
  mangler maps each `:` to `$colon`.
- `PackageRegistry.CL_SYMBOLS` = union of `CL_SPECIAL_FORMS`/`CL_MACROS`/`CL_FUNCTIONS`/
  `CL_VARIABLES`/`CL_INTERNALS`/`CL_TYPES`/`CL_CONDITION_TYPES` (single source of truth).
  `CL_CONDITION_TYPES` is `Set.copyOf(ClosRegistry.CONDITION_CLASS_NAMES)`, so a condition class is
  a `cl` symbol by construction -- required, because a RUNTIME `(typep c ty)` matches the
  registry's PLAIN class name by spelling. Condition names are external and non-callable.

## `defpackage`
A literal, top-level directive like `in-package`. `PackageResolver.resolveDefpackage` registers a
`LispPackage` (exports = owned + external) and replaces the form with a quoted package symbol; it
does NOT switch the current package. It is in `CL_SPECIAL_FORMS`. Designators: keywords, bare
symbols, strings, `#:name` (stripped in `designator`); `:documentation`/`:size` ignored.
- `(:shadow name...)` -> `LispPackage.shadows`; `resolveUnqualified` checks `current.shadows(name)`
  BEFORE the `isClSymbol` branch, and `evalCons` dispatches on the FULL resolved name, so a
  shadowed `pkg::defconstant` reaches the user macro, not the special form.
- `(:shadowing-import-from PKG name...)` is recorded as an IMPORT (shares `collectImportFrom` with
  `:import-from`, shadowing entries merged LAST). **The imports map is the FIRST thing
  `resolveUnqualified` consults** -- before the shadow set, the cl table and the use list -- which
  is CL's always-wins precedence. Pinned by `shadowingImportFromWinsOverTheUseList`.
- **A `defpackage` over an EXISTING package MODIFIES it** (CLHS 11.1.2.1): use list, exports, owned
  symbols, imports and shadows unioned; an existing `:nicknames` entry is a re-declaration.
  Required because rontolisp PRE-SEEDS its shim libraries' packages (`.kb/cffi.md`). A name that is
  another package's NICKNAME stays a hard error.
- Hard errors: `:use`/`:import-from`/`:shadowing-import-from` of a nonexistent package;
  `:nicknames` colliding with a DIFFERENT package/nickname; any other clause; a non-top-level
  `defpackage`. No `:use` clause = empty use list (SBCL-like).

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
non-literal call stays a runtime call (interpreter only). ci-spec
`uiop-add-package-local-nickname`.

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
is consumed by `PackageResolver.resolve` and listed in `UserMacroExpander.isPackageDirective` (the
macro pass tracks the same state and keeps the form verbatim). Each takes LITERAL designators; a
computed call falls through to an interpreter-only runtime function using the SAME resolver.

| directive | resolver entry | notes |
|---|---|---|
| `use-package` (`LispNames.USE_PACKAGE`) | `usePackage` | replaced by `T`; only EXTERNAL symbols inherited; using a package in itself is an error |
| `export` / `unexport` | `exportSymbols` | export also records the same re-export redirect the `:export` clause does |
| `import` | `importSymbols` | same `imports` redirect (member -> `trueHome`) as `:import-from`; the argument keeps its QUALIFIER; an UNQUALIFIED argument is a no-op, as in CL |
| `uiop:add-package-local-nickname` | `registerLocalNickname` | see Nicknames |

All are CL FUNCTIONS, hence usable as function values. ci-spec `use-package`,
`export-and-unexport`.

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
keyword, so all three answer lists of keywords. `package-shadowing-symbols` is a
`LispPreludeLibrary` defun answering nil (runtime `shadow`/`shadowing-import`/`unintern` are
documented non-goals). All five are CL FUNCTIONS.

**Divergence**: the baked table is frozen at compile time, so a package a compiled program creates
later is invisible there -- the same freeze `find-package` has. A COMPUTED `(find-package x)`
becomes an `assoc` over `runtimePackageTable()` / `PackageRegistry.designatorTable()` (canonical
names plus every nickname, each also uppercase), read AFTER `resolveProgram`
(`.kb/symbol-runtime-api.md`). ci-spec `package-registry-queries`.

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
- `do-symbols` is interpreter-only like `do-external-symbols` and reads
  `PackageResolver.accessibleSymbols` (own names plus the externals of every used package, each
  canonicalized against the OWNING package, so a name accessible two ways is listed once).
- **A `cl:`-qualified read-time constant**: `LispReader.readSymbol` substitutes
  `nil`/`t`/`pi`/`most-*-fixnum`/`array-*-limit`/`char-code-limit`/
  `internal-time-units-per-second`/`lambda-list-keywords` before ANY package resolution, so `cl:pi`
  reached the resolver as an ordinary reference; `unqualifyClConstant` strips a `cl:`/`cl::`
  qualifier for exactly that set (`CL_READ_TIME_CONSTANTS`). ci-spec `missing-cl-names-443`.

## Tests
`PackageResolverTest` (the `::` cases, the defpackage clause/error cases, the json.lisp fixed-point
pin, the `*package*` runtime-variable cases), `LispEvaluatorTest#{packageDefaultsToClUser,packageVarIsReadWhenTheFormRunsNotWhenItIsResolved,setqOfPackageVarSwitchesTheCurrentPackage,withStandardIoSyntaxBindsPackageToClUser}`,
`JvmLispCompilerTest#compileAndRunPackageVarIsReadWhenTheFormRuns`,
`WasmLispCompilerIntegrationTest#packageVarIsReadWhenTheFormRuns`, ci-spec
`defpackage-use-export`, `packages-cl-user-default-uses-cl-and-the`. Limitations: README.
