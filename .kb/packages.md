# Packages

Three built-in packages: `cl`, `cl-user` (default, uses `cl`), `rontolisp` (does NOT use `cl`).
One read/compile-time pass, `PackageResolver` (root `am.ik.rontolisp`), runs before the evaluator
and both compilers and rewrites every form into a canonical shape. Two tiers: the read/compile-time
tier (built-ins plus every `defpackage` the COMPILE path resolves -- static, immutable at run time)
and the runtime tier (`make-package` products and every `defpackage` the INTERPRETER resolves -- a
live table on every backend; "Runtime tier" below).

**Canonical shape**: bare names for `cl`/`cl-user` symbols; `pkg:name` external, `pkg::name`
internal -- canonical forms re-resolve to themselves. `*package*` stays the bare cl variable, read at
RUN time. `(in-package P)` is consumed and replaced by `(setq *package* :P)`.

`LispEvaluator.evalTopLevel` takes a literal `progn`/`eval-when` holding an `in-package` member by
member (`membersAroundInPackage`), so later members resolve in the selected package, as on the
compile paths (SBCL reads the whole form first).

Hard errors (`LispPackageException`): a single-colon reference to a non-external member, an unknown
package. Adding a package is a registry change, not a resolver change; the registry is
per-resolver-instance and the backends only see canonical strings, so no per-backend codegen exists.

## A package that does not read a standard name as `cl`'s
**`car` in a package that does not use `cl` and inherits no symbol of that name is that package's
own symbol, in every position** (`resolveUnqualified`, ANSI). Keeping an error for "code positions"
was rejected: the resolver runs ahead of the macro expansion that tells a call from a binding, a
`case` key or a slot, and a reference ahead of the package's own `(defun car ...)` is legal. LOOP
compares keywords by name (`loopKeyword`), as CL does. Pinned by ci-spec
`a-standard-name-read-in-a-package-using-no-cl-is-its-own` (SBCL agrees),
`PackageResolverTest#aStandardNameReadInAPackageUsingNoClIsItsOwnWhereverItStands`.

**The report keeps the advice.** `PackageRegistry.standardNameAdvice` answers ` (use CL:CAR)` for an
internal `PKG::CAR` of an implemented `cl` name (not for an exported one). Appended by
`CompileWarnings.warnUndefinedFunction` and the free-variable refusals; the interpreter prints it
under an uncaught report and at the REPL (`LispEvalException.advice`, `UncaughtReport.adviceLine`).
The condition's text is untouched: the JVM landing pad recovers the class from it and `princ` is
pinned across backends. Exception: a misspelled `let` inside a `lambda` reports `Cannot capture
variable: NOCL::X` (`FreeVarAnalyzer` walks it as a call). Pinned by
`RontoLispCliStreamsTest#{aReferenceToAnUndefinedFunctionWarnsAtTheReferenceOnEveryBackend,anUndefinedStandardNameIsReportedWithTheSpellingToUse}`.

Consequences that must hold:
- `resolveFunction`'s last resort (a shared image function spelled from another package) never
  answers a name `cl` owns.
- **`eval` and `compile` re-read their datum** in the current package; a canonical bare `CAR` is not
  re-readable under a package that uses no `cl`, so `PackageResolver.resolveDatum` leaves a bare
  symbol alone and still takes directives (a bare `while` re-reads: a definition may claim it).
  `PackageResolverTest#aDatumKeepsItsBareSymbolsAndItsDirectivesTakeEffect`.
- **Raw-spelling gates must accept `cl:defmacro`** etc. (the only spelling such a package has):
  `UserMacroExpander.expand`'s gate, `flattenTopLevel`, `LoadInliner`'s `load`/`require`/`provide`,
  the load-context gate all ask `LispMacroExpander.clOperatorSpelling`.
  `UserMacroExpanderTest#aDefinitionSpelledClActivatesThePass`.
- **What the macro pass emits is re-read by the backend's resolver**: folded `deftype`s, deftype
  instances and both macro tables go through `requalifyShadowedClNames`; the load-context `setq` is
  spelled `cl:`. `UserMacroExpanderTest#aMacrosFormsSpelledClAreExpandedAndEmittedForTheCompilersRead`.
- **A pass after the resolver that writes operators into source forms** uses the reader's own
  symbols (`ReaderSymbols`, left alone by identity, like backquote's): Gray streams' `format`
  rewrite ([gray-streams.md](gray-streams.md)), the `make-load-form` substitution
  ([make-load-form.md](make-load-form.md)).
- **A test for a standard function the program defines matches the canonical name**
  (`LispMacroExpander.definesFunction`): a package's own `compile` must not drop `cl:compile`.
  ci-spec `a-package-of-its-own-compile-keeps-cls`.

**`quote` is the one name that stays `cl`'s** in such a package (a `:shadow` still wins): the passes
ahead of the resolver spell it bare wherever they run. Cost: a package's own function `quote`. The
directives likewise go by member name: a bare `(in-package :x)` works there, where SBCL calls an
undefined function.

## T and NIL in a package that does not read them as cl's
**A bare `t`/`nil` token reads as any other name does**: `cl`'s constant where `cl`'s symbol is
accessible (uses `cl` without shadowing/importing another, imports `cl`'s, or uses a package
re-exporting it), else the package's own or a used package's (`coalton:nil`). `()`, `cl:nil`,
`cl:t` stay `cl`'s; `pkg::nil` is what `pkg` has (`cl-user::nil` is nil). SBCL 2.2.9 agrees.
- **The reader decides, asking where the read happens** (`LispReader`'s `clTOrNil`,
  `Forms.readingTOrNil`, `readPrefixIn`): a token it does not fold stays a bare symbol `NIL`/`T`,
  which `PackageResolver.tOrNilIn` resolves (neither name is in `isClSymbol`, so the resolver needs
  this arm). Folding where `cl`'s is certain stays the rule because the passes ahead of the resolvers
  test raw nils (`.asd` data, `FeaturePushes`, top-level scans).
- **Who answers**: interpreter source reads ask the live resolver (`readsClTOrNil`: `loadFile`,
  `evalSource`, `SourceSession.attach`, SWANK text). The compile paths read a form before earlier
  forms ran, so `LoadInliner.Reading` follows the literal top-level package directives spliced so
  far into its own resolver (`followReadPackageDirective`). A package it has no definition of (made
  by a macro, a `#.` clause, or using such a package) reads `cl`'s -- so a macro-made package using
  no `cl` reads its own `nil` interpreted and `cl`'s compiled. Scans and data reads always fold.
- **Run-time reads ask too**: interpreter via `readSpelling` (`internSpellingExact`); compiled via
  `%read-home`, which answers the symbol spelled `NIL` for nil (`_rdHome`, the WASM reader).
- **A `let` binding `cl`'s `t` or `nil` is refused** for every backend
  (`LispMacroExpander.letBindingList`: `T names a constant, and cannot be bound`); a package's own
  `t` binds.
- Quicklisp census (256 systems): every no-`cl` package with bare `t`/`nil` gets `cl`'s through an
  import or a re-exporting package, or is coalton's (read through eclector).
  `ContextlE2eTest` covers the re-export case.

Pinned by ci-spec `a-standard-name-read-in-a-package-using-no-cl-is-its-own`,
`a-run-time-read-interns-in-the-current-package`,
`PackageResolverTest#{aStandardNameReadInAPackageUsingNoClIsItsOwnWhereverItStands,theCompilePathsReadKnowsTheLiteralPackagesAheadOfAToken}`,
`LoadInlinerTest#loadSystemDoesNotLeakTheCurrentPackageToTheCallerOnJvm`,
`LispMacroExpanderTest#aLetBindingTOrNilIsRefusedForEveryBackend`.

## A package defining a name of `cl`'s implementation
`PackageRegistry.CL_INTERNALS` (names lowerings emit) read as `cl`'s in every package, since an
expansion may land anywhere. A program defining one (cxml's `%error`) would otherwise call the
operator: `resolveProgram` first lists top-level `defun`/`defmacro`/`defgeneric`/`defmethod` of such
names per `in-package` (`definedClInternals`), and there the name is the package's own. Census: of
245 internals, cxml's `%error` is the only one defined across 226 Quicklisp systems.
Deliberate gaps: **the interpreter** (form by form, cannot see ahead; the `defun` replaces the global,
safe because only compile-path lowerings emit the operator) and **`cl-user`** (canonical shape is
bare, indistinguishable from the prelude's own definitions; census has none).
ci-spec `a-package-defining-an-implementation-internal-name-keeps-its-own-function`.

## External vs internal (`:` / `::`)
- `cl` exports `CL_EXTERNALS` (= `CL_SYMBOLS` minus `CL_INTERNALS`, plus `CL_EXPORTED_ONLY` and the
  28 car/cdr compositions `CL_COMPOSITIONS`); `cl-user` exports nothing; `rontolisp`/`java` export
  everything registered.
- `pkg::anything` interns permissively. The JVM mangler maps `:` to `$colon`.
- `CL_SYMBOLS` is the union of the category sets (single source of truth). `CL_CONDITION_TYPES` is
  `Set.copyOf(ClosRegistry.CONDITION_CLASS_NAMES)` -- required: a runtime `(typep c ty)` matches the
  registry's plain class name by spelling.

## The `cl` external list is the STANDARD's list, not the implemented one
CLHS 11.1.2.1 fixes `cl`'s externals at the **978 standard names** whether implemented or not. The
unimplemented ones are `PackageRegistry.CL_EXPORTED_ONLY` (plain strings); a name that gains an
implementation moves into its category. The declaration identifiers (`CL_OPTIMIZE_NAMES`,
`CL_DECLARATION_NAMES`) are out of it because libraries look them up as `cl` symbols
(serapeum's `policy-quality`, parse-declarations' EQL methods on `ftype`).

**Exported is not the same question as owned.** `CL_EXPORTED_ONLY` joins `externals`/`symbols` but
NOT `CL_SYMBOLS`, which `isClSymbol` -- every RESOLUTION decision -- reads. So a bare `find-method`
still interns in the current package, `(defun find-method ...)` skips the redefinition guard
(`.kb/lisp2-namespaces.md`), `LibraryDefunPruner` does not see it (`.kb/library-defun-pruning.md`),
and `(find-method x)` signals `undefined-function`. "Does `cl` ANSWER for this name?" is
`isClMemberName`, asked only by the symbol API: `PackageResolver.memberSpelling`/`memberStatus` and
the compile paths' literal `find-symbol` fold (`expandFindSymbolInPackage`/`clPackageStatus`) --
these must move together or interpreter and compilers disagree. `fboundp`/`macro-function`/
`special-operator-p` answer nil. `cl:find-method` resolves (the symbol exists).

`boole-and` .. `boole-orc2` are the standard spellings (CLHS 12.1.4); `boole` dispatches by `eql`
against the constants by NAME, so the value assignment is free. ci-spec `boole`.

**REPL history variables** (`*` .. `+++`, `LispNames.REPL_VARIABLES`) are `CL_VARIABLES`. Nil
outside a REPL: the interpreter binds them in `Environment.createGlobal`; a compiled read of one never
assigned compiles to nil (`compileSymbolRef`, ahead of the `--dynamic` arm). ci-spec
`repl-history-variables-are-nil-outside-a-repl`.

**`while` is an extra `cl` external** (ANSI `no-extra-symbols-exported-from-common-lisp` fails on
it): rontolisp's own loop primitive lives where built-in operators live; moving it would break bare
`(while ...)` in `cl-user` -- a language change. Accepted. `PackageRegistryTest` pins the external
set against `src/test/resources/cl-standard-symbol-names.txt` in BOTH directions, listing `while`.

**But `cl`'s `while` YIELDS** (`PackageRegistry.isClExtensionName`): a package's own `while`
(`:export` does not redirect it to `cl`) and one inherited from a used package win in
`resolveUnqualified`, `accessibleIn`, `accessibleEntries` (parenscript, iterate, `for` export it;
parenscript, arnesi, fset `defmacro` it).
**A definition claims it too** (`PackageResolver.claimDefinedClExtensionName`): an unqualified
`(defmacro while ...)`/`(defun while ...)` outside `cl`/`cl-user` makes the package own the name from
that form on (fset's and arnesi's unexported macro). A reference read before the definition stays
`cl`'s (SBCL: the package's symbol throughout). The compile path's expander consumes the `defmacro`,
so `UserMacroExpander` leaves `(%own-symbol "WHILE")` (`LispNames.OWN_SYMBOL`) for `resolve` to
consume as the same claim; an emitted expansion re-spells the expander's `cl:while`
(`requalifyShadowedClNames` via `currentPackageRereadsClName`), quoted data included. Pinned by
`PackageResolverTest#exportOfAStandardNameReExportsTheClSymbol`,
`UserMacroExpanderTest#anExpansionKeepsClsWhileAfterThePackageTakesTheName`, ci-spec
`a-package-own-definition-of-while-wins-over-cl`.

## `defpackage`
A literal top-level directive. `PackageResolver.resolveDefpackage` registers a `LispPackage` and
replaces the form with the package KEYWORD (`:NAME`, what `make-package`/`find-package` answer, so
the three are `eq`); it does NOT switch packages. Designators: keywords, bare symbols, strings,
`#:name`, a QUALIFIED symbol (`LispSymbol.memberName`: `(:shadow cl:let)` names `LET`), `nil`/`t`,
CHARACTERS (`#\H`, CLHS glossary). `:documentation`/`:size` ignored.
- `(:intern ...)`: owned, not exported -- except a name a used package exports, which stays
  inherited (CLHS: `:intern` after `:use`, "found or created"), whatever the clause order.
  `pkg::name` of an inherited name IS the inherited symbol (`resolveQualified`). ci-spec
  `find-symbol-of-an-inherited-name`.
- `(:shadow ...)`: checked BEFORE `isClSymbol` in `resolveUnqualified`; `evalCons` dispatches on the
  full resolved name, so a shadowed `pkg::defconstant` reaches the user macro.
- `(:shadowing-import-from ...)`: an IMPORT (shared `collectImportFrom`, merged LAST) whose names also
  join `shadows`. **Imports are the FIRST thing `resolveUnqualified` consults** (CL's always-wins).
  Pinned by `shadowingImportFromWinsOverTheUseList`.
- **Over an EXISTING package it MODIFIES** (CLHS 11.1.2.1): everything unioned, except local
  nicknames. Required: the shim libraries' packages are PRE-SEEDED (`.kb/cffi.md`). A name that is
  another package's nickname is a hard error.
- Hard errors: `:use`/`:import-from`/`:shadowing-import-from` of a nonexistent package; `:nicknames`
  colliding with a different package; unknown clauses. No `:use` = empty use list (SBCL).
- **Clause validation (SBCL-checked)**: `validateDefpackageShape` runs on the form before any lookup
  (SBCL does it at macroexpansion): known options, `:size`/`:documentation` once with one argument,
  `:shadow`/`:shadowing-import-from`/`:import-from`/`:intern` names pairwise disjoint, `:intern`
  vs `:export` too. Failures are `DefpackageException` (a `LispPackageException`) typed
  `PROGRAM_ERROR` or `PACKAGE_ERROR` (slot = the package being defined, as SBCL).
- **A missing `:import-from` member is refused only for a SEALED source** (`PackageRegistry.sealed`:
  made by `defpackage`/`make-package`, never read into since). Once source minted a name in it
  (`PackageResolver.mint`; skipped under `exactCase` and for printer/helper probes) the check is off,
  because backends' resolvers record no reads and even a recording reader misses derived names and
  pruned forms. Built-in / pre-seeded packages are never sealed. A pruned third-party `defstruct`
  leaves `%struct-definition` so the check stays off for its slot names
  (.kb/library-defun-pruning.md); a name only the macro-time evaluator read arrives via `%read-symbols`
  ("The compile image's packages").
- **At run time failures are conditions**: a nested `defpackage` and `(eval '(defpackage ...))` both go
  through `LispEvaluator.registerRuntimeDefpackage` (the `eval` native must route there, or it takes
  the top-level hard-error path -- ANSI's `signals-error` uses `eval`). The missing-symbol case offers
  a `continue` restart that interns and retries (SBCL). A program's own TOP-LEVEL `defpackage` stays a
  hard error; compiled backends refuse non-top-level ones, so conditions are interpreter-only.
- **A NON-top-level `defpackage` is left VERBATIM** by `resolveCons` and registers when it runs
  (`rareOperatorExpansion` -> `resolve`), joining the runtime tier (ANSI's `set-up-packages`).
  `Jvm`/`WasmExprCompiler` refuse it where they meet it.

## `define-package` (uiop / mgl-pax variant)
A literal top-level `(uiop:define-package ...)`/`(mgl-pax:define-package ...)` -- qualifier REQUIRED
(`isDefinePackageOperator`; bare `define-package` is a user symbol) -- is consumed like `defpackage`
plus variant clauses (`resolveDefpackage(cons, true)`): `:use-reexport`, `:mix` (imports, earlier
package wins, no use-list entry), `:reexport`, `:mix-reexport`. A mix clause naming the package being
defined is skipped (uiop creates before mixing; generic-cl.sequence mixes itself). `:recycle`,
`:unintern` and redefinition tolerance are errors. `mgl-pax:defsection` additively exports its
`(SYMBOL LOCATIVE)` entries (`consumeDefsectionExports`; trivial-utf-8's only export mechanism).
Pinned by `definePackageIsConsumedLikeDefpackage`, `definePackageUseReexportUsesAndReexports`,
`aBareDefinePackageIsNotTheVariant`, `paxDefsectionExportsItsEntries`.

## Nicknames
Static `BUILTIN_NICKNAMES` seed: `common-lisp`, `common-lisp-user`, `rl`, `la`, `quicklisp`; seeded
names are reserved; user `:nicknames` are instance-only.

**`splitQualified` normalizes a built-in nickname in the package part** (`canonicalBuiltinName`)
because the pre-resolution passes match `qn.pkg()` against canonical literals before the resolver
runs: the library splice scanners, `LoadInliner`'s `ql:quickload`, `HttpHandlerInliner`, the
`wasm-import`/`wit-import`/`wit-export` matchers, `LispMacroExpander.isAsyncSugarHead`. Pinned by
`JvmLispCompilerTest.compileAndRunBuiltinNicknamesTriggerLibrarySplice`.

**The standard packages' names are swapped only at the surface**: canonical stays `CL`/`CL-USER`
(package value `:CL`, every pass matches the literal), but `package-name` answers `"COMMON-LISP"` and
`package-nicknames` `("CL")` (CLHS 11.1.2, SBCL) -- `PackageResolver.standardPackageNicknames` on
the interpreter, prelude defuns elsewhere. `runtimePackageNicknames` keeps the registry view for
SWANK's prompt; a package row keeps `COMMON-LISP` as a designator, so
`uiop:package-definition-form`'s `(:USE "COMMON-LISP")` reads back.

## Package-local nicknames
SBCL's extension, on all four backends. `PackageRegistry.localNicknames`: per package, nickname to
target, compared verbatim. Follows SBCL 2.2.9: the refusals (`CL`, `COMMON-LISP`, `KEYWORD`, the
package's own name, a second target) and their texts, a target read as a global name, a `defpackage`
over the package REPLACING them (the one non-merging clause), rename/delete following them. With
several nicknames for one home, the first in nickname order prints (SBCL: unspecified).

**Reading.** `PackageResolver.resolve` starts with `readLocalNicknames`: every qualified token whose
RAW qualifier (before `splitQualified`'s built-in normalization) is a nickname of the current
package becomes a token of the target; nothing downstream asks the table. A datum (`resolveDatum`)
and implementation forms (`probing`) are not tokens. Run-time reads: `readSpelling` /
`%read-home`. Every designator goes through `findPackageName` (current package's table first);
`findGlobalPackageName` for what is already a package name.

**The second read, and why a spelling carries a mark.** The compile path re-reads canonical
expansions in the same package, where `ALEXANDRIA:FOO` from another package's macro would read
through a nickname. So `emittedSpelling` (`requalifyShadowedClNames`) prefixes
`PackageRegistry.GLOBAL_QUALIFIER` (a private-use character no reader produces) to a qualifier that
is a nickname there, and `readLocalNicknames` does the same when a target is itself a nickname --
a read is idempotent. Rejected: two nickname tables per pass, or brackets every pass must step
over. `emittedSpelling` also keeps a `cl-user` symbol `CL-USER::HELPER` in an expansion emitted in
another package. ci-spec `an-expansion-keeps-a-cl-user-symbol-read-in-another-package`.

**The first pass of the pipeline** (`eval/LocalNicknameRead`, first in `CompileFrontend.expand`):
library scanners recognize a library by package spelling, so `(:local-nicknames (:l :linalg))` must
reach them as `LINALG:` and `(:ffi :mine)` must not read as `ffi:`. It follows the program top-level
form by form with its own resolver. A nickname only a macro expansion defines reaches the resolvers
alone (scanners miss it).

**Run time on the compile paths.** Forms are all read before any runs, so `resolveProgram` leaves an
entry where a literal directive stood and `LispMacroExpander.injectLocalNicknames` adds the
`%local-nicknames%` state the program builds as it runs, read under `*package*` by every
designator-taking definition, the printer and every run-time read via `%read-home`
(`.kb/read-load-streams.md`, "A symbol token reads in the current package"). A program asking nothing
drops the entries. A designator
literal some package has as a nickname (`collectLocalNicknameNames`) does not fold (`find-package`
stays a call; `intern`/`find-symbol`/`package-use-list`/`package-used-by-list` branch on
`%local-nickname-find`, `localNicknameDesignatorSite`). A computed add un-folds every literal
designator, so ci-spec has none (`compileAndRunUiopRemovePackageLocalNickname` and its wasm twin pin
it). The interpreter folds no `find-package`. `--no-gc` drops the entries.

**Residual.** A package value IS its name's keyword, so a package object named like a nickname of the
current package reads as the nickname (`(:local-nicknames (:t1 :t2) (:t2 :t1))`:
`(package-name (find-package "T1"))` is `"T1"`, SBCL `"T2"`). Re-evaluate with package objects
(`.kb/symbol-runtime-api.md`). A literal NESTED add is the computed one on the compile paths. A
nickname in `cl-user` makes it non-pristine for good (`clUserNicknamed`, the printer's slow path), so
the corpus keeps its nicknames elsewhere.

Pinned by ci-spec `uiop-add-package-local-nickname`, `package-local-nicknames-read-in-the-package-that-has-them`,
`package-local-nickname-of-a-library-spliced-by-name`, `uiop-package-surgery`,
`PackageResolverTest#{aLocalNicknameReadsInThePackageThatHasIt,aReadThroughLocalNicknamesReadsTheSameAgainAndAResolvedSymbolIsSpelledToReadAsItself,aPackageCannotHaveEveryLocalNickname,aLiteralLocalNicknameDirectiveIsConsumedAndNamesThePackageThatHasIt,aCompiledProgramLeavesEntriesForItsNicknamesAndFoldsNoDesignatorThatMayBeOne}`,
`UserMacroExpanderTest#anExpansionKeepsItsSymbolsInAPackageThatReadsThemAsOthers`. cluffer loads and
edits a buffer on all four backends.

## `in-package` that is not a top-level form
Switches when it runs, on every backend. Interpreter: `rareOperatorExpansion` -> `resolve`
(catchable `package-error`). Compile paths: `resolveCons` leaves `(setq *package* (or (find-package
"P") (%package-error ...)))` and changes nothing that reads, as compile-file does. A package
directive a macro expands to directly is top level, member by member
(`UserMacroExpander.prognMembersWithPackageDirective`). ci-spec `a-package-directive-a-macro-expands-to`.

## Resolution order and imports
- `resolveUnqualified` order: `current.imports()`, shadows, the `cl` table, `current.owns()`, use
  list. In a package without `cl`, a `cl`-table name tries the use list before being the package's
  own -- except `CL_INTERNALS` (`isClInternal`), `cl`'s in every package because expansions inject
  them anywhere.
- **Use-list visibility checks `exports`, not `owns`.** Conflicts: first-wins in `:use` order (CL
  signals). `resolveQualified` redirects through `imports` after the externality check. `#:g1` passes
  through unresolved.
- **Only the eight `lambda-list-keywords` are `cl`'s** (also bare in a no-`cl` package; `:shadow`
  wins). Any other `&name` is an ordinary symbol (`'&pt` and `(intern "&PT")` are one symbol):
  `LispSymbol.isLambdaListKeyword` decides, not a `&` prefix. ci-spec
  `an-ampersand-name-is-an-ordinary-symbol-of-the-current-package`.
- **An `:export` of an INHERITED name re-exports the used package's symbol**: recorded as an
  `imports` entry (`cl` first when used, then the use list), standard names included (skipping them
  made `pkg:car` an undefined `PKG:CAR`). Exception: a name an EXISTING package already owns stays its
  own (pre-seeded packages owning `sin`, `read`, `close`). `while` stays the package's own (above). A
  hand-written `(:use cl) (:export "READ")` binding its own `READ` must `:shadow` it, as in CL.
  Pinned by `ReExportedStandardNameFixture` (four suites), ci-spec `export-of-an-inherited-standard-name`,
  `PackageResolverTest#{exportOfAStandardNameReExportsTheClSymbol,aDefpackageOverAPackageThatOwnsAStandardNameKeepsItsOwnSymbol,aStandardNameInAPackageWithoutClResolvesThroughItsUseList}`.
- **The recorded entry points at the TRUE home** (`trueHome`): follows the source's own import (one
  hop suffices by induction) and the source's USE list (CL imports any accessible symbol). Same for
  `:import-from`. `PackageResolverTest#{reExportOfARedirectedMemberRecordsItsTrueHome,importFromARedirectedMemberRecordsItsTrueHome}`.

## Quoted data resolves against the current package
`resolveCons` sends every `(quote DATUM)` through `resolveQuotedData` (CL's reader interning in
`*package*`): backquote templates, quoted symbols, quoted data tables naming functions. A literal in
code position (`#S(...)`, `#(...)`, `#nA(...)`) is data too. One walk, `LispTrees.rewriteDatumSymbols`
(also `readThrough`): `#n=` sharing and circularity survive; an unchanged datum comes back as the
same object; only nodes with a path to a changed symbol are copied, and `PackageResolver` keeps the
copies per form/program (`holdDatumCopies`) so a label is one object across two quotes. Pinned by
ci-spec `a-literal-holds-symbols-of-the-current-package`,
`LispTreesTest#rewriteDatumSymbolsKeepsTheShapeOfTheDatum`.

Trap: the walk cannot tell a `#.` value from source (spliced before resolution), so a bare spelling in
it reads as the current package's (`'#.(vector 'cl-user::foo)` under `P` holds `P::FOO`). An array
`#.` built with fill pointer/displacement/`:adjustable` is left alone. A real intern table removes
the question.

A `cl` symbol quoted in a package without `cl` is the package's own; a quoted `*package*` is the
SYMBOL. ONE exemption (`inHostFacingData`): a `wasm-import`/`wasm-export` directive's options, while
its quoted NAME is resolved like a defun name (`resolveWasmDirective`, `.kb/wasm-import.md`).

## Directives consumed at read/compile time
Their effect is consulted by `resolveUnqualified` during the walk, so a runtime-only effect would be
invisible to the forms it affects -- entirely on the compiled backends. Each is consumed by
`PackageResolver.resolve` and (except the two uiop nickname calls) listed in
`UserMacroExpander.isPackageDirective`. LITERAL designators only; a computed call falls through to an
interpreter-only runtime function using the SAME resolver.

| directive | resolver entry | notes |
|---|---|---|
| `use-package` | `usePackage` | replaced by `T`; only externals inherited; using a package in itself is an error |
| `unuse-package` | `unusePackage` | replaced by `T`; unusing what is not used is a no-op; implied uses go with their implier |
| `export` / `unexport` | `exportSymbols` | export records the same re-export redirect as `:export` |
| `import` / `shadowing-import` | `importInto` | `imports` redirect to `trueHome`; argument keeps its QUALIFIER; UNQUALIFIED is a no-op as in CL. `isPackageDirective` takes only a `shadowing-import` with quoted symbols and a literal package. sento's `(eval-when (:compile-toplevel) (shadowing-import ...))` needs the backends' resolver to see it |
| `uiop:add-package-local-nickname` | `addLocalNickname` | replaced by the package, or an entry in a compiled program |
| `uiop:remove-package-local-nickname` | `removeLocalNickname` | replaced by `T`/`NIL`, or an entry |

All are CL FUNCTIONS. ci-spec `use-package`, `unuse-package`, `export-and-unexport`.

**On the COMPILED backends the use-list pair has no runtime form**: a computed `use-package` has
nothing left to do, and `unuse-package` must match. Both lower through
`LispMacroExpander.expandRuntimeExport` with `export`/`unexport`/`import` (evaluate args, answer `t`).

**A directive changes ACCESSIBILITY, never IDENTITY; the SPELLING is the DECLARED external set.** A
symbol IS its spelling, so deciding the colon from the live set made `export` re-key symbols under
existing definitions (`The function SPIKE:MY-FN is undefined`). `PackageResolver.declaredExternals`
captures a package's external set the first time `export`/`unexport` touches it; `spellsExternal`
(behind `canonical`, `spellsAsExternal`) reads that, `isExternal` (single-colon check, use-list
visibility) reads the live set. Consequence: a LATE-exported symbol prints `PKG::NAME`
(`.kb/pretty-printer.md`). ci-spec `export-after-the-definitions`.

**A COMPUTED `export` never reaches the resolver**: only a literal one folds (`tryConsumeExport`). The
runtime designator route works (`.kb/symbol-runtime-api.md`). Not widened: the widening would accept
any `pkg:x` and swallow typos. One the compile image RUNS reaches the compilers as the image's package
state ("The compile image's packages"). A library computing exports from literal arguments gets them
via a compiler macro (`.kb/asdf.md`, `eval/ClReexportForms`). `'()` names no symbol
(`PackageResolverTest#importOfAnUnqualifiedSymbolIsANoOp`).

**A `progn` a macro expands into is top level member by member** (CLHS 3.2.3.1) when it holds a
package directive (`UserMacroExpander.Session.accept`): cl-annot's `@export`. Any other expanded
`progn` stays whole. ci-spec `export-and-unexport`.

## Registry queries
`list-all-packages`, `package-use-list`, `package-used-by-list` share
`PackageResolver.runtimePackageUseTable()`: the interpreter reads the live registry; the compile
paths bake it into `Ctx.packageUseTable` and lower in `LispMacroExpander.expandPackageQuery` (constant
for a literal designator, else an `assoc` keyed by the `find-package` name). Answers are keyword lists.
`package-shadowing-symbols`: name order; a native (`PackageResolver.shadowingSymbols`) or a prelude
defun over the baked row / runtime entry. When the program can create packages the lowerings union
`%runtime-packages%` in; a literal `package-used-by-list` then stays a call.

**Divergence**: the baked table is frozen at compile time, like `find-package`. A COMPUTED
`(find-package x)` becomes an `assoc` over `runtimePackageTable()` / `PackageRegistry.designatorTable()`
(names plus nicknames, each also uppercase), read AFTER `resolveProgram`. ci-spec `package-registry-queries`.

**One `%find-package` defun per program** (`LispMacroExpander.injectFindPackageHelper`, after
`injectBakedPackageTable`) when a lookup survives resolution; every site calls it
(`expandRuntimeFindPackage`). Inline, each site built its own quoted table (a quote datum is memoized
per identity). Pinned by `aComputedFindPackageSiteDoesNotCarryItsOwnCopyOfThePackageTable` in
`JvmLispCompilerTest` and `WasmLispCompilerTest`.

**There is no package introspection, deliberately.** The `rontolisp:list-functions`/`list-macros`/
`list-special-forms` listings were never complete and every name added to a category broke four
pinned expectations plus the docs. Classification is visible only via `#'name`,
`special-operator-p`, `macro-function` and the reference docs. **Do not bring the listings back.**

## Pre-seeded shim packages with redirects
- **`closer-common-lisp` (`c2cl`) is a FLAT RE-EXPORT package**: `cl` externals overlaid with
  `CLOSER_MOP_EXTERNALS`, every member an `imports` redirect to its home, nothing owned. **Using it
  implies using `cl`** (`PackageResolver.withImpliedUses`: cl visibility is judged by a DIRECT use).
  **The use-list loops redirect through a used package's `imports`** (`usedExport`), else any
  re-exported member inherited through `:use` takes the re-exporter's spelling. Pinned by
  `PackageResolverTest#{closerCommonLispQualifiedMembersResolveToTheirHomePackages,usingCloserCommonLispMakesClAndCloserMopVisible,useListReExportResolvesToTheHomePackage}`.
- **`closer-mop` re-exports `cl` names** (`CLOSER_MOP_CL_REEXPORTS`, upstream's list) as redirects to
  `cl`.
- **babel** records its babel-encodings members as redirects: `babel:X` and `babel-encodings:X` are
  one symbol (`PackageResolverTest#babelSpellingsOfTheBabelEncodingsMembersResolveToTheirHome`).

## The prelude splice selects by SYMBOL, not by member name
`LispPreludeLibrary.process` selects on a `new PackageResolver().resolveProgram(program)` copy;
member-name matching took alexandria's own `alist-hash-table` for a program definition of
`rl:alist-hash-table`. Deliberate asymmetries: entry-to-entry fixpoint edges stay member-matched
(prelude sources are resolver fixed points); a program throwing `LispPackageException` falls back to
member matching. Same shape as `LibraryDefunPruner` (`.kb/library-defun-pruning.md`). Pinned by
`LispPreludeLibraryTest`. The interpreter's lazy load (`LispPreludeLibrary.isPreludeFunction`)
matches the same way (`definedName`): member-matched, a shadowing `pkg::macroexpand-1` loaded the
prelude's signalling stand-in over the interpreter's. ci-spec `a-package-of-its-own-compile-keeps-cls`.

## UserMacroExpander resolves through its own evaluator's resolver
`UserMacroExpander.expand` resolves every top-level form via `macroEval.resolvePackages` before
matching, so a `defmacro` under `(in-package P)` registers its canonical name.
`in-package`/`defpackage` update the macro evaluator's state but are kept VERBATIM for the compilers.
**A form the walk did not change keeps its ORIGINAL spelling** (compared by `print()`): a canonical
form is not always re-resolvable (a `cl:` symbol canonicalizes bare, which a no-`cl` package reads as
its own). Expansions emitted there are re-spelled `cl:` (`requalifyShadowedClNames`).

## `load`/`load-system` scope `*package*`
`PackageResolver` has a package stack (`pushPackage`/`popPackage`). Interpreter: `LispEvaluator.loadFile`
pushes/pops in `finally`. Compile path: `LoadInliner.spliceFile` brackets a spliced file with
`(%push-package)`/`(%pop-package)` (`LispNames.PUSH_PACKAGE`/`POP_PACKAGE`) ONLY when it has a
top-level `in-package` (`selectsAPackage`), so plain files splice byte-identically. Files are read a
form at a time ([readtables.md](readtables.md)), so the push goes in front of the FIRST `in-package`
(`PackageBracket`); a leaf shim's forms keep the old placement. Tests: `LispEvaluatorAsdfTest`,
`LoadInlinerTest`.

## `*package*` is a DYNAMIC variable -- two faces, kept in step
CL reads `*package*` when a form RUNS; folding a value-position `*package*` is wrong inside any defun
that outlives its file.
- **Value**: the package KEYWORD (`:CL-USER`), so `eq` with `find-package`, `package-name`, `eq`
  hashing, `(typep * 'package)` and printing work. `#.*package*` splices the keyword.
- **Resolver**: a value-position `*package*` resolves through the generic cl-symbol path; `(in-package
  P)` leaves `(setq *package* :P)` (`packageAssignment`), `%pop-package` the same for the saved one.
  Top-level forms run in resolution order, so the states agree at every top-level point.
- **Interpreter**: the variable IS the resolver's current package -- ONE cell, so `intern`, `read`,
  lazy macro expansion and the program's value cannot disagree. A top-level `(setq *package* ...)` makes the interpreter resolve the
  NEXT form there (compile paths resolve the file up front -- documented divergence). It is the one
  special the interpreter does NOT thread-scope.
- **Compile paths**: `LispMacroExpander.injectMvSpillGlobal` prepends `(defvar *package* :cl-user)`
  only when the program READS it (anything but the top-level `setq` shape, or `with-standard-io-syntax`)
  (`.kb/dynamic-special-variables.md`). A program that only switches drops its assignments and stays
  byte-identical -- unless its PRINTER observes it (`.kb/pretty-printer.md`).
- **`with-standard-io-syntax`** binds `*package*` to `:cl-user` (`.kb/pretty-printer.md`,
  "`with-standard-io-syntax`").

**A user macro called inside a FUNCTION BODY expands with the package that body was READ in**
(`LispLambda.readPackage`, `Environment.lexicalPackage` inherited down the scope chain, swapped in
`expandMacroCall`), as CL expands while compiling that file. A macro body's own text is its defining
package's (`UserMacro.definitionPackage`). Outside every body (and for `eval` from a function,
`macroexpand`) the current package. Both halves are load-bearing (let-plus's `&structure` in
cl-colors; fast-http vs trivia's top-level `lispn:define-namespace`). Walks run at code-run time take
the same package (`expandAllAtSite`: `macrolet`, `flet`, `symbol-macrolet`, `with-slots`).
**The swap only reaches what is homed DURING the expansion**: `intern` and every interpreter runtime
read (`read-from-string`, `read`, a readtable view) via `LispReader.symbolHome`
(`Environment.setReadSymbolHome` -> `PackageResolver.readSpelling`). Reader-synthesized symbols
(`ReaderSymbols`, [defmacro-backquote.md](defmacro-backquote.md)) and `#.` values are not homed; a
token with an unknown package qualifier keeps its spelling. Compiled: `.kb/read-load-streams.md`, "A
symbol token reads in the current package". Pinned by
`LispEvaluatorTest#evalMacroCallInAFunctionBodyExpandsInThePackageTheBodyWasReadIn`, ci-spec
`expansion-time-read-homes-in-the-macro-package`,
`LispEvaluatorTest#loadEvaluatesFileAndReusesDefinitions`, `TriviaE2eTest`, `SxqlE2eTest`.

## The REPL prompt IS the current package
`ReplBuffer.prompt` asks `LispEvaluator.currentPackageName()` before every line, for both REPL
drivers; the continuation line is blanked to the same width. Pinned by
`RontoLispCliTest#{replEchoesEveryValueOnItsOwnLine,replPromptNamesTheCurrentPackage}`; every
`doc/*/**` transcript shows the prompt except `compiling/self-hosted-repl.md`.

## Assorted standard names
- `invoke-debugger` calls `*debugger-hook*` under a nil binding; an uncaught error does not, so a
  hook cannot observe one.
- `do-symbols` / `do-external-symbols` (interpreter: views of `accessibleEntries`; compiled:
  `%do-symbols-list`) spell **every symbol as code spells it**, so an enumerated symbol is `eq` to
  `find-symbol`'s answer and a doubly accessible name is listed once. `t`/`nil` come back as the singletons wherever a symbol is
  answered by name (`LispEvaluator.symbolOfSpelling`; compiled: `.kb/symbol-runtime-api.md`, "The
  spelling-identity model").
- **A `cl:`-qualified read-time constant**: `LispReader.readSymbol` substitutes `nil`/`t`/`pi`/... before
  resolution, so `unqualifyClConstant` strips a `cl:`/`cl::` qualifier for exactly that set
  (`CL_READ_TIME_CONSTANTS`). ci-spec `missing-cl-names`.

## Runtime tier
- A runtime package is created EMPTY (use list, nicknames upcased) and grows a member table. Only
  runtime-tier packages rename/delete; read/compile-time ones signal `package-error` (baked spellings
  would orphan). **The tier a `defpackage` product lands in is decided by WHO resolved it**:
  `resolveProgram` (compile path, spellings baked) mints read/compile-time packages, a lone `resolve`
  (interpreter, live registry) mints runtime ones. A modifying `defpackage` keeps the tier; a package
  the compile image made by code is read/compile-time ("The compile image's packages"). Failures are
  catchable, the offending designator in the `package` slot (`package-error-package`).
- Interpreter: natives over the live registry (a lone `resolve()` never folds an unknown name; only
  `resolveProgram` folds, and only when the program cannot mutate). Compiled: prelude defuns over the
  injected `%baked-packages%` (each symbol universe ONE length-prefixed string decoded by
  `%split-packed`, so it costs data-segment bytes, not top-level body bytes --
  `.kb/wasm-function-body-size.md`) plus the mutable `%runtime-packages%`. Same signals and reason
  strings.
- **One gate predicate**: `runtimePackagesMutable()` (a `make-`/`delete-`/`rename-package` reference
  outside quoted data AND outside a `defpackage` -- an exported `make-package` is just a name), the
  `Ctx` flag follows it, and lowerings consult the runtime table only then; other programs stay
  byte-identical. The support entries (`%runtime-packages%`, `%do-symbols-list`, `%split-packed`,
  `%package-symbols-where`, `%package-spelling-normalize`, `%baked-import-redirect`,
  `%baked-package-find`, `%runtime-package-find`) are selected by surface reference
  (`referencedBySurfaceForm`) and rooted in `LibraryDefunPruner` the same way. That includes
  `string<` (the sorting lowerings and the `#'list-all-packages` wrapper), selected by a mutation
  reference.
- **`%baked-packages%` is injected when a form READS it** (`needsBakedPackageTable`, on the spliced,
  pruned program), never by an operator-name list (which missed `shadow`/`shadowing-import`/
  `unintern`, reaching it through `%runtime-package-op` -> ... -> `%baked-package-find`). An unknown
  designator is `package-error` `OP: no such package: X` on every backend. Pinned by
  `LonePackageOperationFixture` (interpreter / JVM / WASM) and ci-spec `lone-*` cases.
- The enumeration universe (`do-symbols` lowering, `find-all-symbols`, `apropos-list`,
  `do-all-symbols`, `with-package-iterator` via `%package-iterator-entries`) is one walk:
  `%package-symbols-where` over `%do-symbols-list` rows. Keywords are never listed (no keyword table).
  A runtime entry enumerates its members plus its uses' externals. The walk interns names the program
  never spells, so the funcall-dispatch gate reads the table's spellings whenever the program defines
  `%split-packed` (`RuntimeNameProducers.packageWalkSpellings`,
  `.kb/optimize-dead-code-elimination.md`). **Both dedups are `equal` hash tables, never a scan**
  (scans made a walk quadratic in the corpus): pinned by source shape,
  `LispPreludeLibraryTest#thePackageWalksDeduplicateThroughATableRatherThanAScan`. What a walk costs
  now: splitting/interning rows (linear) and one `assoc` per distinct row in `%baked-import-redirect`.
- Residual divergences (documented on the reference pages): compiled `find-symbol`/`intern` over a
  computed designator naming a read/compile-time package BUILD a spelling for a name the registry
  cannot place (`PKG::NAME` program package, `PKG:NAME` pre-seeded); `symbol-package` compiled reads
  the home off the spelling (`%symbol-home` plus the standard-name table), so an uninterned symbol
  keeps its old home; `unintern`'s name-conflict check is interpreter-only; `--no-gc` refuses the tier.

### Compiled lookups answer through the registry

A 2-argument `find-symbol`/`intern` into a read/compile-time package must answer the symbol the
registry reaches (inherited, imported, re-exported), not a build homed in the asking package:
- **Literal name and package**: folded to `PackageResolver.bakedAccessible` whenever it differs from
  the build, status too (`expandFindSymbolStatus`).
- **Computed name or designator**: `LispMacroExpander.injectBakedAccess` prepends `%baked-access%`
  rows (`PackageResolver.bakedAccessRows`) with `%baked-access`/`%baked-access-status`, and rewrites
  `%symbol-in-package` to read them first. A row: present entries differing from the build, non-`cl`
  uses, and exports of used packages at their true home; decoded into an `equal` hash table on first
  lookup. A literal-designator site ships its package's row; a computed designator (or
  `uiop:symbol-call`) ships every PROGRAM package's, NOT a pre-seeded shim's (uiop alone re-exports
  hundreds) -- computed lookups in shims keep the shim spelling. The package-walk normalizer is
  exempt. The status half takes a keyword designator as is.
- **The build is the internal symbol in a package the program defines**: `permissiveAnswer` is
  `PKG::NAME :internal` unless `PackageRegistry.isBuiltinPackageName` (shims keep `PKG:NAME
  :external`), so a run-time `(intern "DATA" :p)` is `eq` to `p::data`. Literal names fold exactly
  (`BakedSymbolAccess.programPackage`); a computed name in a literal package builds `PKG::` when the
  row travels or the package answers nothing (`internalBuild`); a computed designator asks
  `(%program-package-p K)` and builds under `K`'s own name (nicknames land on the same symbol).
  `PackageRegistryTest#everySeededPackageIsABuiltinPackageName` keeps the builtin list complete.
  Cost: a computed lookup carries every program package's exports (hello-clack +17 KB, table 4.4 ->
  20.7 KB). Restricting to exports the program spells was rejected: an unreferenced export would print
  `P::X`. Pinned by ci-spec `intern-of-an-internal-name-is-the-read-symbol`,
  `LispMacroExpanderTest#aComputedDesignatorWithAComputedNameCarriesTheClUsers`.
- Standard names travel as their own layer -- next section.

A program with no served row is byte-identical. A computed lookup into a package WITHOUT a row, in a
program with rows, pays an extra probe. Pinned by `InheritedFindSymbolFixture` (four suites), ci-spec
`find-symbol-of-an-inherited-name`,
`LispMacroExpanderTest#{injectBakedAccessLeavesAProgramNoRowServesUnchanged,injectBakedAccessServesAComputedLookupInAPackageThatUsesAnother,thePackageWalkNormalizerAloneCarriesNoRows}`.

### The standard names at run time

A standard name reached through `cl` under a computed name or designator must answer `cl`'s symbol
(consumer: `(funcall (intern (string-upcase op) :my-pkg) ...)` dispatch). One table serves
`symbol-package`, the lookups and their status:
- **`%standard-name-p`** (`LispMacroExpander.standardNameDefinition`, prelude entry or injected by
  `injectBakedAccess`): `PackageRegistry.standardNames` (`CL_EXTERNALS`, `while` included), sorted and
  front-coded into one ASCII string (mark `(code-char (+ 96 K))` for K shared characters), decoded into
  an `equal` hash table on first call. The global holds the string: a literal in the body was a fresh
  string per call on WASM (`.kb/string-index-cost.md`).
- **A front-coded walk instead of a table was rejected**: allocation-free but reads many characters
  per lookup and was no smaller; only a toy without hash-table runtime would gain.
- **Gate**: `injectBakedAccess`'s site scan (`AccessSites`) enables standard mode for a site whose name
  can be standard (computed, or a constant spelling -- `(string '#:run)` cannot) and whose package can
  use `cl` (a literal one that does, a computed designator, the operators as function objects,
  `uiop:symbol-call`, a one-argument direct producer of a multiple-value consumer). Otherwise
  byte-identical.
- **Layered after the rows**: `bakedAccessRows(true)` keeps `cl` out of use lists but lists every name
  answering differently without it (shadowing members, imports into no-`cl` packages, re-exports).
  Order: row, then `(%standard-name-p n)` if the package uses `cl`, then the build (`cl-user`'s value
  half skips the table). Computed designators read `%cl-users%` (shims excluded). A package using only
  `cl` needs no row.
- **`symbol-package`** is `%symbol-home` (spelling qualifier, `:cl-user` for bare) refined by the
  table; the package walks call `%symbol-home` only.
- **The 28 compositions are `cl` members** (`CL_COMPOSITIONS`, generated, not in `CL_SYMBOLS`;
  resolution recognizes them by `LispNames.isCarCdrComposition`): `cl` lists 979 externals (978 +
  `while`) on every backend, checked against SBCL (`CarCdrUniverseFixture`). Packed into every
  cl-using package's `accessible` row and `cl`'s `externals` row; generating them per walk was
  rejected (every universe reader would need it plus a "reaches `cl`" test, and the registry would
  keep two answers).

Cost: a toy without hash tables/`concatenate` pays their runtime with its first computed lookup or
`symbol-package` of a read symbol. Pinned by `StandardNameLookupFixture` (four suites), ci-spec
`find-symbol-of-a-computed-standard-name`,
`LispMacroExpanderTest#{theStandardNameTableAnswersExactlyTheNamesClExports,injectBakedAccessCarriesTheStandardNamesForAComputedNameInAPackageThatUsesCl,aComputedDesignatorWithAComputedNameCarriesTheClUsers,aOneArgumentLookupWhoseStatusIsReadCarriesTheStandardNames,injectBakedAccessLeavesAProgramNoRowServesUnchanged}`.

### What a program read

A name a compiled program only read (quoted symbol, variable, macro-body name) answers every
enumeration (`find-all-symbols`, `do-symbols`, `do-all-symbols`, `apropos-list`,
`with-package-iterator`, loop's `being the symbols`, a literal `(find-symbol "NAME")`), as on the
interpreter and SBCL. `find-symbol` with a package argument builds the spelling and answers any name
(`.kb/symbol-runtime-api.md`).

**Who knows the names: the macro-time evaluator's reader, not the compilers' resolver.** It records
reads ("The member table"), reads `defmacro` bodies the backends never see, and reads spliced libraries
as implementation source (unrecorded). `UserMacroExpander.expand` appends
`(%read-symbols (PACKAGE MEMBER...)...)` (`PackageResolver.readSymbolsMarker`); the compilers'
resolver records those members, so `accessibleEntriesOf`, the baked rows and the literal fold need no
change. Rejected: the compilers' resolver recording its own reads (takes in the prelude, misses macro
bodies); a separate resolver over the raw program (stopped by macro-defined packages).

**Only in a program that can ask** (`LispPreludeLibrary.asksWhatWasRead`): it spells `do-symbols`,
`do-all-symbols`, `find-all-symbols`, `apropos`, `apropos-list`, `with-package-iterator`,
`package-definition-form`, a loop `being` clause, or holds `(find-symbol "NAME")`; such a program runs
the macro pass even without macros. `do-external-symbols` does not count (a read name is no external).
A walk the scan cannot see answers as before. Cost: hello-clack + one `find-all-symbols` +1.7-1.9 %;
a toy pays 13.7 KB for its first name (`cl-user`'s row stops sharing a data-pool string).

**Divergence**: the compiled table holds the whole program from the start, so `(find-all-symbols "X")`
before the first `'x` answers `(X)` where the interpreter and SBCL's source `load` answer `NIL` (SBCL's
`compile-file` + fasl answers `(X)`).

Trap: a universe's packed length counts CHARACTERS (the decoder's `subseq` does), not UTF-16 units --
read names can hold non-BMP characters. Pinned by ci-spec
`an-enumeration-sees-a-symbol-the-program-only-read`,
`UserMacroExpanderTest#aProgramAskingWhatAPackageHoldsCarriesWhatItRead`.

### The member table

**A runtime package records its members, and a symbol is STILL its spelling.** A real intern table
with symbol identity was rejected: it would touch the reader, `eq`, every baked spelling on JVM and
both WASM backends (a WASM symbol is a string-table OFFSET, `.kb/symbol-runtime-api.md`) and every
printer table, to win only ANSI `import.5` (an uninterned symbol gaining a home) and identity after
`unintern`. `.kb/symbol-runtime-api.md`, "Is identity-by-name stable?" is the standing answer;
re-evaluate only when a consumer needs symbol objects.

The table IS `LispPackage.symbols` (own present names, a mutable concurrent set, O(1) `intern`) plus
`LispPackage.imports`. Writers (interpreter's live registry, mirrored in the compiled runtime entries):
- `intern` (`internSpelling(name, record=true)` -> `recordInterned`): the home of the answered spelling
  OWNS the member unless it already provides it.
- **The reader of a live image**: a `recordingReadSymbols()` resolver (`LispEvaluator`'s -- interpreter
  AND the compile paths' macro-time evaluator) records every name it MINTS for source
  (`PackageResolver.mint`, `markRecorded`), form at a time, as CL's reader interns. Consumer: plump's
  macro-time `find-symbol` dispatch. Not recorded: an exported-only standard name read by a cl-using
  package; exact-case probes; `printsBare` (it must not UNSEAL by printing); implementation helper
  forms (`resolveHelper`: `LispEvaluator.evalHelper`, SWANK's inspector -- they leaked a lambda's `X`
  into the current package, visible in `SwankServerTest`); libraries `CompileFrontend` splices ahead
  of the program (`implementationPrefix`, `resolveUnrecorded`: a spliced lexical `OK` shadowed rove's
  `ok` after `(use-package :rove)`; ci-spec `use-package` pins it). `use-package` has no conflict
  check, so a name read before the use stays the target's own where CL signals.
  **The backends' whole-program resolvers record nothing**: their registry becomes the baked tables, so
  recording would grow every walking/computed-lookup program by every name read, and macro-time reads
  would still be missing. A compiled run-time lookup of a read-only name keeps the build answer
  (`PKG::NAME :internal`, which is the read member's answer; only its existence differs); macro-time
  records arrive in a program that asks ("What a program read"). Pinned by ci-spec
  `macro-time-find-symbol-sees-a-symbol-only-read` (all four backends),
  `LispEvaluatorTest#findSymbolSeesSymbolsReadOrDefinedInsideAUserPackage`.
- `export` (`exportSymbols`): a symbol homed elsewhere must be accessible (an inherited one becomes an
  import redirect); one homed in the target is taken on its spelling (may be a read-time symbol the
  table never saw). Conflicts: a different accessible symbol of that name; a USER of the target with
  its own present, non-shadowing symbol. `unexport` leaves an inherited symbol alone (SBCL).
- `import`/`shadowing-import` (`importInto`): redirect to the home (bare: `cl-user`, or `cl` for a
  standard name); a different PRESENT symbol conflicts for `import`, is uninterned by
  `shadowing-import` (names join `shadows`). A symbol its home UNINTERNED redirects to that home
  directly, not via `trueHome` (whose table may hold the displacing symbol): parenscript's
  `re-export-symbols`. It prints `PS-DOM1-SYMBOLS::CLASS-NAME` where SBCL prints `#:CLASS-NAME`.
- `shadow` (`shadowSymbols`): present -> marked; absent -> fresh own member, marked.
- `unintern` (`uninternSymbol`): removes own/import/external/shadow; an own symbol is TOMBSTONED
  (`PackageRegistry.markUnhomed`: `symbol-package` nil, `find-symbol` stops answering it) until an
  `intern` `rehome`s it (CL mints a distinct symbol -- documented deviation). A shadowing symbol
  hiding two different inherited symbols refuses.
- **A runtime-minted member is verbatim and never a reader-case mismatch.** The resolver's case-fold
  retries (`.kb/wit.md`) would fold `Abc`/`ABC` onto a recorded `abc` (Scheme's `|A:b|` in
  `SchemeSpecE2eTest`). Guards: runtime `intern` resolves with `exactCase`; retries skip
  `PackageRegistry.isRecorded` members. `LispEvaluatorTest#internRecordsAMemberOfARuntimePackage`.
- Literal consumption (`tryConsumeExport`/`tryConsumeImport`/`consumeDefsectionExports`) spells a bare
  quoted symbol as the READER did (`internSpellingOnly`); keyword/`#:`/string designators name the
  target's own symbol. Failures are `RuntimePackageException` -> catchable `package-error`
  (`signalPackageError`).

Readers -- ONE lookup, `PackageResolver.accessible(pkg, name, definedProbe)`, so `find-symbol`, its
status, the enumerations and conflict checks cannot disagree: keyword; `cl`; PRESENT (`presentIn`:
import redirect, own member, the interpreter's `definedInImage` probe -- a definition is an interning);
INHERITED (`inheritedFrom`, use order). `cl-user` answers `:inherited` for standard names, `:internal`
for recorded/defined names, else nil. `memberSpelling`/`memberStatus` are views; `accessibleEntries`
is the same rule over the package. `symbolPackageName` reads the home off the spelling
(`isClMemberName`) minus tombstones.

Compiled: lookups on a runtime entry route through `LispMacroExpander.runtimeMemberLookup` (only
under `runtimePackagesMutable`); a runtime package's own symbols spell `PKG::NAME`, as on the
interpreter. `intern`'s second value is the status BEFORE the intern on every backend
(`lowerMvProducer`; interpreter `publishSecondValue(..., secondFirst)`).
`NoGcWasmCompiler.isConsumedPackageResidue` drops a consumed `defpackage`'s bare keyword. The baked
table plus a symbol builder in one WASM program needs the fdlibm tables placed
(`.kb/transcendentals.md`).

ANSI packages chapter (interpreter, suite `ca06bd9`), failures left that the member table cannot fix:
package IDENTITY (a deleted or renamed package object -- unmodellable while a package IS its
keyword); `import.5`; `import` rows needing the driver's skipped `in-package`; restarts
(`import.error.4`, `make-package.error`); `find-symbol.4` (no keyword table);
`find-all-symbols.error.2` (documented extension); driver artefacts.

## The compile image's packages
**Decision: what code the compile paths' macro-time evaluator ran did to its packages joins the
compile-time tier, where the code ran.** That evaluator is the compile image (SBCL's compile-file
image); the compilers' resolver learns packages only from consumed directives, so `make-package`, a
nested `defpackage`, or a computed `export`/`import`/`use-package`/`shadow` never reached it.
Consumers: static-dispatch's `(defvar ... (make-package ...))` whose expansions `gentemp` in it;
cl-environments' computed re-export of every `cl` external (generic-cl's `:mix` packages).
- **Mechanism**: `UserMacroExpander.Session` watches the macro-time registry's records
  (`PackageRegistry.modifications`). Changes during a top-level form's directive resolution
  (`resolveTopLevel`) the compilers' resolver makes itself; anything else was code. Each such package
  goes, as the image holds it then, into an `(%image-packages ROW...)` marker in front of the NEXT
  emitted form, so the form whose expansion made it can name it; `applyImagePackages` takes the row
  over, `spellsExternal` included.
- **The program's `make-package` of it answers it once**: its `%baked-packages%` entry carries a
  trailing `t`; the prelude's `make-package` of that name drops the flag and answers it; a second call
  signals. (In SBCL the fresh image's load runs the library's own `make-package` first.)
- **A directive the resolver cannot consume runs at compile time** where the position runs forms
  (`runUnconsumedDirective`: computed `export` in a compile-time `eval-when`, or a spliced system's
  top level). A compile-time `eval-when` opens the macro pass by itself (`expand`'s gate).
- **Rejected**: keeping the package runtime-tier (every baked table would need a third kind of
  package, resolvable but not baked, and the run-time package would lack its compiled symbols'
  members); one marker of every package's FINAL state (a change would take effect before the form that
  made it, and a package a later `defpackage` uses would not exist yet).
- **Divergences**: `find-package` answers an image package before the program's `make-package` ran
  (SBCL: nil); `delete-package`/`rename-package` refuse it. Member-table-only changes (`intern`,
  `gentemp`) are not handed over (enumerating programs get them via `%read-symbols`). Image deletes/
  renames by code and code-made local nicknames are not handed over.
- Measured: `(ql:quickload :generic-cl)` works on all four backends (`generic-cl:+`, `=`, `map`,
  iterators, lazy-sequence `remove`).
- **What the image READ goes the same way, where an import asks for it**. A consumed `defmacro`, a
  macro body or a compile-time-only member is never read by the compilers' resolver, so a package
  minting nothing else stays sealed there and `(:import-from :p #:hidden*)` failed `no symbol named`
  (serapeum over `serapeum/iter::collecting*`). The image's resolver, made
  `notingImportedReadSymbols`, notes at `collectImportFrom` each member a clause took from an
  image-made package that does not declare it, and `emit` puts them in a `%read-symbols` marker ahead
  of the directive (`importedReadSymbolsMarker`). **Rejected**: a trace per consumed definition
  (`%own-symbol`'s way -- misses macro-body/compile-time interns, needs one per definition kind);
  handing the seal over (re-runs a passed check, leaves the member out).
- Pinned by ci-spec `a-package-the-compile-image-made-or-changed`,
  `UserMacroExpanderTest#whatTheCompileImageDidToThePackagesReachesTheCompilersResolver`.

## Tests
`PackageResolverTest` (the `::` cases, defpackage clause/error cases, the json.lisp fixed-point pin,
the `*package*` runtime-variable cases, the runtime-tier create/delete/rename/gate/baked-table cases),
`LispEvaluatorTest#{packageDefaultsToClUser,packageVarIsReadWhenTheFormRunsNotWhenItIsResolved,setqOfPackageVarSwitchesTheCurrentPackage,withStandardIoSyntaxBindsTheStandardTable,runtimeMakeDeleteRenamePackage,runtimePackageFailuresSignalCatchablePackageErrors,runtimePackageEnumeration}`,
`JvmLispCompilerTest#{compileAndRunPackageVarIsReadWhenTheFormRuns,compileAndRunRuntimePackageApi}`,
`WasmLispCompilerIntegrationTest#{packageVarIsReadWhenTheFormRuns,runtimePackageApi}`,
`PackageResolverTest#{unusePackageDirectiveNarrowsTheUseList,unusePackageAcceptsADesignatorListATargetAndRejectsUnknownNames,unusePackageWithAComputedDesignatorOrABadArityStaysARuntimeCall,defpackageNestedIsLeftVerbatimForTheRuntimeTier,defpackageInternClauseOwnsTheNamesWithoutExportingThem,defpackageDesignatorsAcceptCharacters,interpreterDefpackageProductsJoinTheRuntimeTier,defpackageClauseViolationsAreHardErrorsAtTopLevel}`,
`LispEvaluatorTest#{runtimeUnusePackageNarrowsTheUseList,nonTopLevelDefpackageRegistersARuntimePackage,defpackageClauseViolationsSignalProgramError,defpackageNicknameAndPackageErrorsArePackageErrors,defpackageImportOfAMissingSymbolOffersAContinueRestartThatInternsIt,defpackageInternOfAnInheritedNameFindsTheInheritedSymbol}`,
the member table: `LispEvaluatorTest#{internRecordsAMemberOfARuntimePackage,usePackageInheritsARuntimePackagesExports,shadowMintsAPresentSymbolAheadOfTheInheritedOne,shadowingImportDisplacesThePresentSymbol,uninternRemovesAMemberAndUnhomesTheSymbol,exportAndImportCheckAccessibilityAndConflicts,withPackageIteratorWalksTheMemberTable,doSymbolsSpellsARedirectAtItsHomeAndAClNameBare,findSymbolAnswersTheStandardSymbolsThroughAUsePackage,defpackageAnswersThePackageKeyword}`,
`JvmLispCompilerTest#compileAndRunRuntimePackageMemberTable`,
`WasmLispCompilerIntegrationTest#runtimePackageMemberTable`, one package operation alone:
`LispEvaluatorTest#lonePackageOperation`, `JvmLispCompilerTest#compileAndRunALonePackageOperation`,
`WasmLispCompilerIntegrationTest#lonePackageOperation`, ci-spec
`defpackage-use-export`, `packages-cl-user-default-uses-cl-and-the`, `runtime-package-api`,
`runtime-package-member-table`, `unuse-package`. Limitations: README.
