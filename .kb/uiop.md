# uiop: the sub-package bundle, the inventory, and `not-implemented-error`

**Invariant: no `uiop` name may reach a caller as `The function UIOP:X is
undefined`.** uiop 3.3.7 exports 429 symbols; rontolisp implements a growing
subset and every remaining one signals `uiop:not-implemented-error` naming the
operation, identically on all four backends. The coverage target is DATA
(`uiop-exports.txt`), not a Java literal, and `UiopCoverageTest` is the gate
every uiop item reports to.

This file supersedes the uiop paragraphs in `.kb/asdf.md`, which describe the
pre-bundle "mostly a package stub" arrangement.

## The 15 sub-packages

Upstream's `uiop` IS `uiop/driver`: a `:use-reexport` of 15 sub-packages
(`uiop/package`, `uiop/package-local-nicknames`, `uiop/package*`,
`uiop/utility`, `uiop/version`, `uiop/os`, `uiop/pathname`, `uiop/filesystem`,
`uiop/stream`, `uiop/image`, `uiop/launch-program`, `uiop/run-program`,
`uiop/lisp-build`, `uiop/configuration`, `uiop/backward-driver`). A library may
name either spelling -- `lack-middleware-backtrace` writes
`(:import-from :uiop/image :print-condition-backtrace)`, which is a READ error
when the package is absent, and `uiop/image` was registered ad hoc for exactly
that before the bundle existed.

`PackageRegistry` registers all 15 from `UiopExports`, using the
`closer-common-lisp` mechanism rather than inventing a `:use-reexport` notion:

- each sub-package OWNS the members its own inventory rows list and EXPORTS them;
- a member a second sub-package also exports is an IMPORT REDIRECT to the home
  one (upstream's `uiop/backward-driver` re-exports five `uiop/configuration`
  names, and `*output-translation-function*` is exported by both
  `uiop/pathname` and `uiop/lisp-build`);
- `uiop` itself owns nothing from the inventory: every one of its 429 externals
  is an import redirect to the home sub-package.

**The consequence that drives everything else: the HOME spelling is canonical.**
`PackageResolver` rewrites a `uiop:getenv` occurrence to `UIOP/OS:GETENV`, so
that is the name a definition of it must carry, the name a switch label must
match, and the name that appears in an error message. `UiopExports.qualified`
composes it and `UiopExports.denotes(pkg, member, X)` recognizes BOTH spellings
-- a pass that runs on either side of resolution needs the second (the backends'
dispatch gate runs after, `GenericDispatchNarrowing` before). Every hard-coded
qualified name in `LispNames` (`UIOP_GETENV`, `UIOP_SYMBOL_CALL`,
`UIOP_IF_LET_QUALIFIED`, `UIOP_WITH_DEPRECATION_QUALIFIED`,
`UIOP_WITH_TEMPORARY_FILE_QUALIFIED`) is a switch-label constant and therefore
cannot be computed; `UiopCoverageTest.theHardCodedQualifiedNamesAgreeWithTheInventory`
pins each against the table.

## The inventory: `uiop-exports.txt`

`src/main/resources/am/ik/rontolisp/uiop-exports.txt`, read by
`am.ik.rontolisp.UiopExports` (root package, so `PackageRegistry` and
`eval.UiopLibrary` can both use it without either depending on the other).

Format: `<sub-package> TAB <symbol> TAB <kind>`, `#` comments, ONE ROW PER
EXPORT. **435 rows / 429 distinct symbols / 15 sub-packages.** Rows are in
upstream LOAD order and alphabetical within a sub-package, so the FIRST row for
a symbol names its home and every later row is a redirect -- the file is
self-describing and the tables are built by reading it top to bottom.

`kind` is upstream's DEFINITION form (`function`, `macro`, `variable`,
`constant`, `condition`, `class`, `type`, `+`-joined when a name is defined
twice -- `not-implemented-error` is a condition AND the function that signals
it). It is a third column beyond what `.todo/353` specified because a stub
cannot be the right SHAPE without it: a `defun` for a name upstream defines with
`defvar` would satisfy `fboundp` and answer the wrong predicate forever.

**Regenerating it** (only when the pinned uiop version moves). The extractor
reads the `define-package` forms out of
`~/.rontolisp/quicklisp/software/uiop-<version>/` with a PORTABLE feature set --
`*features*` bound to `(:package-local-nicknames)` only, so the three
implementation-conditional exports (`use-ecl-byte-compiler-p` on clasp/ecl,
`probe-posix` on mcl, `sb-grovel-unknown-constant-condition` on sbcl) are absent
-- and joins each symbol with the definition form found by scanning the same
files for `defun`/`defmacro`/`defvar`/`define-condition`/... and for
`:reader`/`:accessor` slot options. `.todo/353` quoted 434 externals; the real
figures are the two above (that count summed the per-sub-package export lists
without deduplicating the six names two sub-packages both export).

## `eval.UiopLibrary`: one home for every definition

Real definitions live in `uiop-<sub-package>.lisp` resources next to the class
(currently `package`, `utility`, `pathname`, `filesystem`, `stream`, `image`,
`lisp-build`), in
canonical shape. **A resource may only define names the inventory lists** --
`build()` fails loudly otherwise -- so there are no private helpers: a body that
wants one uses `flet`/`labels`. Everything the inventory lists that no resource
defines gets a stub SYNTHESIZED from its kind:

| kind | stub |
|---|---|
| `function`, `macro` | `(defun NAME (&rest %uiop-stub-args) (uiop:not-implemented-error "NAME"))` |
| `variable`, `constant` | `(defvar NAME nil)` |
| `condition` | `(define-condition NAME (error) ())` |
| `class` | `(defclass NAME () ())` |
| `type` | `(deftype NAME () t)` |

Three groups are deliberately NOT stubbed, because something else defines them
and a stub would shadow it: `JAVA_DEFINED` (`getenv`, `symbol-call`,
`add-package-local-nickname` -- Java built-ins the compilers lower or wrap),
`LispMacroExpander.hasUiopMacroExpansion` (`if-let`, `with-temporary-file`,
`with-deprecation`, `define-package`), and -- separately -- the members that
ALSO carry a fold (`file-exists-p`, `native-namestring`), which do have a Lisp
definition here so `#'uiop:file-exists-p` is a value like any other.

**Filling a name in is a one-line change**: add the real definition to the
matching `.lisp` resource and the stub disappears on its own, because a name the
resources define is never stubbed. `UiopCoverageTest.printCoverage` then moves.

Stub caveats, deliberate and documented rather than silent:

- a `variable` stub is bound to `nil`, which for most of them (`*image-dumped-p*`,
  `*lisp-interaction*`, ...) is also upstream's default but for some is not --
  the item that implements the sub-package sets the real value;
- a `constant` gets a `defvar`, not a `defconstant`: the value is a placeholder,
  and pinning a placeholder as a constant only makes the real definition a
  redefinition;
- a `condition`/`class` stub is flat (`(error)` as the only superclass) where
  upstream has a hierarchy (`compile-file-error` inherits `compile-condition`);
- a `macro` stub is a `defun` so the name is `fboundp` and usable as a value,
  but the CALL FORM never reaches it: `LispMacroExpander.expandUnimplementedUiopMacro`
  lowers it to `not-implemented-error` with its argument forms DROPPED. A macro
  that does nothing must not evaluate what it was handed -- an unimplemented
  `(uiop:with-current-directory (d) (defun f ...))` would otherwise define `f`
  before signalling. The evaluator and both compilers share that one expansion,
  which is what makes the four backends agree.
  **On the compile paths it must be applied in the expression compiler's uiop
  branch, AHEAD of the ordinary call path**, precisely because the stub is a real
  variadic `defun`: the call path finds it in `ctx.functions` and compiles the
  argument forms before the call, so `expandUiopStubCall` (which only runs for a
  name with no defun at all) never gets the chance. Found 2026-08-14 in todo-354:
  `(uiop:with-current-directory ("/tmp") ...)` compiled `("/tmp")` as a CALL and
  died with `The function "/tmp" is undefined` on both compile paths while the
  interpreter signalled correctly. It was invisible until then because every
  test used a macro with an EMPTY spec list, whose arguments are the nil literal.
  Pinned by `compileAndRunUiopUnimplementedMacroDropsItsArgumentForms` /
  `uiopUnimplementedMacroDropsItsArgumentFormsCompilesAndRuns` and the ci-spec
  case, all with a non-empty spec.

## The macros: ONE dispatcher, not four switch statements

A uiop macro cannot be a `defmacro` in a resource. The compile path runs
`UserMacroExpander` BEFORE the uiop splice, so a `defmacro` (or a
`define-modify-macro`) spliced afterwards is never expanded and the backends,
which have no macro support of their own, see a call of an undefined name. Every
uiop macro with a real expansion is therefore a Java expansion in
`LispMacroExpander`, listed in `UIOP_MACRO_EXPANSIONS` (which is also what
`UiopLibrary` asks so it does not stub over one, and what
`expandUnimplementedUiopMacro` asks so it does not lower one to an error).

**`LispMacroExpander.expandUiopMacro(cons, unwindProtect)` is the single
dispatcher**, called from `LispEvaluator.evalCons`, `JvmExprCompiler`,
`WasmExprCompiler` and BOTH `FreeVarAnalyzer` walks. It replaced four parallel
switch statements that had to be kept in step by review; a macro added to the
table now reaches every consumer at once. `unwindProtect` exists for
`with-temporary-file` alone (`ctx.ehMode` on WASM, true everywhere else).
`FreeVarAnalyzer` needs it because several of these BIND (`if-let`/`when-let`'s
binding list, `while-collecting`'s collectors, `with-temporary-file`'s
`:stream`/`:pathname` plist) or REARRANGE (`nest`) forms the default walk would
read as ordinary calls.

`with-deprecation` and `with-upgradability` additionally splice at TOP LEVEL
(`flattenTopLevel`, via `isUiopDefinitionWrapper`, which matches both spellings
because that pass runs on either side of package resolution): both wrap
definitions, and burying those in an expression stops them being definitions at
all.

## `uiop/utility`'s decisions (todo-354)

- **`with-upgradability` -> `progn`.** Upstream wraps every one of its
  definitions in it so ASDF can redefine itself inside a running image
  (compile/load/run evaluation plus `notinline`). rontolisp has no image to
  upgrade and no separate compile-time evaluation to schedule, so `progn` is the
  whole meaning. A semantic CHOICE, not an omission -- leaving it a stub would
  make every upstream-shaped definition file unloadable.
- **One character type.** `(subtypep 'character 'base-char)` and
  `(subtypep 'base-char 'character)` both answer `t` here, so running UPSTREAM'S
  OWN derivation gives `+character-types+` = `#(character)`,
  `+max-character-type-index+` = 0, `character-type-index` = 0 and
  `+non-base-chars-exist-p+` = `(plusp 0)` = NIL. `base-string-p` is then
  upstream's `(and)` = `t` for every string and `strings-common-element-type` is
  the constant `'character`. **`.todo/354` proposed `+non-base-chars-exist-p+`
  true; that reading is self-inconsistent** -- it would make `base-string-p`
  false for every string while the common element type stayed `character`. Note
  `array-element-type` signals on a string here, so `base-string-p` cannot ask it
  anyway.
- **`register-hook-function` signals.** It pushes onto a variable named at RUN
  time, i.e. `(setf (symbol-value var) ...)`, which is not a setf place on any
  backend and has no `cl:set` either. The definition is real and the message
  names the missing primitive; the alternative (dropping the hook silently) would
  let an image-hook caller believe it registered something. **Re-evaluation
  trigger: the day `(setf (symbol-value ...))` becomes a place, this body is
  three lines of `pushnew`.**
- **`uiop-debug` / `load-uiop-debug-utility` / `*uiop-debug-utility*`.** The
  variable holds upstream's default form (it is data); the loader signals,
  because loading it needs a run-time `load` of a COMPUTED pathname and `load` is
  a compile-time splice on every backend.
- **`match-condition-p`'s STRING pattern** compares against
  `simple-condition-format-control`, which here answers the ALREADY FORMATTED
  message (rontolisp builds it at signal time). A pattern carrying format
  directives cannot match; one without them still does.
- **`coerce-class` drops upstream's `*package*` fallback** for a keyword
  designator. `PackageResolver` turns `*package*` into a compile-time constant,
  so it has no run-time value a resource can read -- and upstream calls that arm
  backward compatibility anyway.
- **`ensure-function`'s `:package` is accepted and ignored**: `read-from-string`
  reads into the current package on every backend, with no run-time `*package*`
  rebinding to hang it on.
- `timestamps<` chains from `nil` = +infinity, so a non-empty list is never
  "increasing". Upstream's own answer, kept rather than corrected, and pinned so
  nobody "fixes" it.
- `frob-substrings` returns a FRESH string where upstream returns the original
  object when nothing was frobbed: upstream's identity shortcut is a
  `return-from` out of a `labels` function, i.e. a cross-lambda exit, which would
  put every program using it into EH mode on WASM.

## `uiop/pathname`'s decisions (todo-357: 50/50)

The whole sub-package is Lisp source in `uiop-pathname.lisp`, written over the
flat-namestring model of `.kb/pathnames.md` (`%pathname-split` / `%path-ns` /
`pathname` / `namestring` / `wild-pathname-p` / `translate-pathname`), so
upstream's component-wise care collapses onto namestring computation and one
definition serves all four backends.

- **Logical pathnames follow the CL half's commitment** (`.kb/pathnames.md`: no
  logical host can be defined, no `logical-pathname-translations` table exists,
  `logical-pathname` always signals, `translate-logical-pathname` is the
  identity). So `logical-pathname-p` answers nil for everything,
  `physical-pathname-p` is `pathnamep`, `physicalize-pathname` is the coercing
  identity, and `make-pathname-logical` signals `not-implemented-error` naming
  the missing model -- answering a physical pathname that claims to be logical
  would be a lie. `make-pathname-component-logical` IS real (`:unspecific` ->
  nil, else identity): it is pure component surgery. Re-evaluate only if a
  host/translation table is ever added.
- **The `*wild-*` constants are namestring literals** (`*wild*` is the string
  `"*"`, `*wild-file*` is `#P"*.*"`, `*wild-inferiors*` is `#P"**/"`,
  `*wild-path*` is `#P"**/*.*"`), the two wildcards `%wild-match` reads, not
  upstream's `:wild` keywords -- `make-pathname` here renders a directory
  `:wild` as `*` anyway, and a keyword-valued constant would make
  `(wilden p)`'s merge meaningless over a flat namestring.
- **`ensure-pathname` signals DIRECTLY on the default error path** (`%ens-err`
  branches: nil/t/'error -> a direct `(error ...)`, anything else ->
  `call-function`). A funcalled `#'error` wrapper is a raw TRAP on the WASM
  backends where a direct call is a catchable signal, and
  `(handler-case (ensure-pathname ...) (error () ...))` must catch on all four.
  Lite otherwise, documented on the doc page: the report is
  `Invalid pathname ~S: ~A` (no `~?` chain), `:want-logical` always fails,
  `:resolve-symlinks`/`:truenamize` are accepted and ignored, `:truename`
  answers `probe-file`.
- **`ensure-absolute-pathname` keeps its documented divergence** (a relative
  path with no absolute default is answered as itself, not an error): rontolisp
  absolutizes nowhere, and the value's job is file IDENTITY (rove's
  file-to-suite map). `get-pathname-defaults` (home `uiop/filesystem`, defined
  in `uiop-filesystem.lisp` because the export is homed there) reads
  `*default-pathname-defaults*` for the same reason -- it RETIRED the
  pre-`.todo/036` `""` Java built-in in `LispEvaluator` and the
  `expandUiopStubCall` fold, plus `PackageRegistry`'s hand-added internal
  symbol (the inventory row covers it now).
- **Two macros, `with-pathname-defaults` and `with-enough-pathname`**, live in
  `LispMacroExpander` (`UIOP_MACRO_EXPANSIONS`) like every uiop macro. Their
  `MACRO_EXPANSION_CALLEES` rows show the table also carries a VARIABLE: the
  no-defaults arm of `with-pathname-defaults` binds `uiop:*nil-pathname*`, and
  selection splices a defvar exactly like a defun. `with-enough-pathname`'s
  `:pathname` defaults to the SPEC VARIABLE itself (upstream's rebinding
  shorthand), and the `*default-pathname-defaults*` rebinding both expansions
  emit is a real dynamic `let` on all four backends.
- **`split-name-type` and `split-unix-namestring-directory-components` return
  their MULTIPLE VALUES through the spill channel** (`.kb/multiple-values.md`);
  `parse-unix-namestring` consumes them across the function boundary and the
  four-backend tests pin that this works compiled.
- **`cli/CompileTimePathnameFolder` folds `subpathname`** over literal
  arguments (the `merge-pathnames*` precedent), mirroring the Lisp definition
  exactly -- including the bare-filename fast path where `"."` is a NAME, and
  declining an absolute STRING subpath because at run time that arm is a
  `:want-relative` error a fold must not fold away.

Pinned by the `evalUiopPathname*` / `evalUiopSplitNameType*` /
`evalUiopWildPathnames*` / `evalUiopEnsurePathname*` block of
`LispEvaluatorTest`, `JvmLispCompilerTest.compileAndRunUiopPathnameAlgebra`,
`WasmLispCompilerIntegrationTest.uiopPathnameAlgebraCompileAndRuns` and the
`uiop-pathname-algebra` ci-spec case (all four backends). Docs:
`reference/uiop/pathname.md` (the sub-package page) plus detail pages for the
names libraries actually call.

`find-symbol*` / `find-package*` (`uiop-package.lisp`) landed with todo-354
rather than with `.todo/361`: `find-standard-case-symbol`, `coerce-class` and
`symbol-test-to-feature-expression` are all written over `find-symbol*`, and
routing them around it would leave three copies of "look a name up in a package,
error or not". Its compiled-backend status answer is the one
`.kb/symbol-runtime-api.md` describes (`.todo/254`).

## Selection, not pruning

`UiopLibrary.process` prepends only the definitions the program reaches,
computed to a fixpoint on a `PackageResolver.resolveProgram` copy (so a
`uiop:name` occurrence is matched as the home symbol it denotes).

**`MACRO_EXPANSION_CALLEES` is the surface-form rule**, mirroring
`LispPreludeLibrary.referencedBySurfaceForm`: a uiop MACRO's expansion runs
inside the expression compilers, long after this pass, so the names it introduces
never occur in the program this pass sees. Without an entry the compiled program
says `The function UIOP/UTILITY:X is undefined` at run time while the interpreter
(which lazy-loads on resolution) works -- a four-backend divergence that costs
nothing to introduce and is invisible until someone runs the compiled artifact.
Only the DIRECT callee is listed; the fixpoint pulls in the rest.

| surface macro | direct callee(s) |
|---|---|
| `with-temporary-file` | `ensure-directory-pathname`, `default-temporary-directory`, `delete-file-if-exists` (through the prelude's `%temp-file-name`) |
| `with-muffled-conditions` | `call-with-muffled-conditions` |
| `uiop-debug` | `load-uiop-debug-utility` |
| `latest-timestamp-f` | `latest-timestamp` |

**uiop is NOT in `LibraryDefunPruner`'s prunable set** (the usocket precedent).
`.todo/353` proposed splicing all 429 and letting the pruner shake them out;
selecting up front is the same saving one step earlier, and it avoids paying a
full resolution pass over 429 definitions in every uiop-using program. Revisit
only if a case appears where a SELECTED definition is provably unreachable.

**`LispPreludeLibrary.process` CALLS `UiopLibrary.process` first** rather than
sitting beside it in the pipeline. The two are mutually dependent -- a uiop body
calls `namestring`/`pathname`/`merge-pathnames`/`directory`/`%dir-namestring`
here, and the prelude's `%temp-file-name` calls uiop back -- so they are one
pass with a fixed order, and a pipeline that ran only the prelude would
reintroduce exactly the "undefined function" this library exists to abolish.
Re-running it is a no-op: a definition the program already carries is not
spliced again (`UiopLibraryTest.aSecondRunSplicesNothingMore`).

The interpreter lazy-loads on first resolution
(`LispEvaluator.loadUiopDefinition`), reachable from both the function and the
variable lookup. Two things go in around the name asked for, and both exist
because **a CLASS cannot be lazy the way a function can**:

- the whole `UiopLibrary.closureOf(name)` CLOSURE, not just that name -- the same
  set `process` splices. `style-warn` signals
  `(make-condition 'uiop:simple-style-warning ...)`, and a quoted condition name
  is not a function resolution, so nothing would ever have triggered the class's
  own load;
- every uiop condition and class (`UiopLibrary.conditionAndClassNames`, 19 rows)
  on the first touch of the condition system at all --
  `ensureConditionReportRuntimeLoaded` calls
  `ensureUiopConditionClassesLoaded`. A handler's type test is built from the
  class tags known when the `handler-bind` was EXPANDED, so a class registered
  while the body runs is invisible to the handler meant to catch it, and a
  program that only NAMES a uiop condition never resolves a uiop function at
  all. Measured symptom:
  `(handler-bind ((warning #'muffle-warning)) (uiop:style-warn "x"))` muffled on
  the JVM and both WASM backends and printed on the interpreter. Registering
  them on first condition-system use rather than in the constructor confines the
  cost to programs that have conditions, and adds no divergence of its own: a
  program can only observe a class it NAMES, and naming it is what makes the
  compile path splice its definition too (`process` collects quoted symbols).

The residual, and it is NOT uiop-specific: a type test is baked at expansion
time, so a condition class first registered inside an already-entered
`handler-bind` body still misses. It reproduces with a plain `eval`'d
`define-condition` and belongs to the condition system, not here.

## What moved, and why `merge-pathnames*` was the tell

Before this, the 23 working members were split across `LispPreludeLibrary`
string bodies, `PackageRegistry` tables, Java built-ins in `LispEvaluator`, and
`expandUiopStubCall`, whose default arm was "every other uiop call is an error".
`uiop:merge-pathnames*` was the visible cost: a Java built-in on the interpreter
only, so a call with non-literal arguments was
`The function UIOP:MERGE-PATHNAMES* is undefined` on the JVM and both WASM
backends. It is Lisp source now (`uiop-pathname.lisp`, over `cl:merge-pathnames`,
whose rontolisp definition already implements the same rule), and so are
`file-exists-p` and `native-namestring`, which used to be Java-side for the same
reason. `expandUiopStubCall` kept only its real folds and lost the error arm.

**Every uiop function either runs on all four backends or signals
`not-implemented-error` identically on all four.** That is the acceptance
criterion for items 354-365; `merge-pathnames*` was the one violation and it is
gone.

## Documentation shape

`doc/{en,ja}/reference/uiop.md` is the model page: the sub-package model, a
coverage table over the 15, the implemented-member table for everything still
small enough to sit there, and what an unimplemented member signals.
`reference/functions.md` keeps a pointer only.

**A sub-package that fills up moves to `reference/uiop/<sub-package>.md`** --
`.todo/353`'s proposal arrived at when it pays. `uiop/utility` was the first
(todo-354, 61 members at once): `reference/uiop/utility.md`, its own `nav.yaml`
entry in every language tree, and the parent page keeps the coverage row (now a
link) and one sentence. Per-operator detail pages stay for names a user program
actually calls, not for all 429.

## Tests

- `UiopCoverageTest` -- the gate: every listed symbol external in `uiop` AND in
  its row's sub-package; every listed symbol defined (`fboundp` for a function
  or macro, `boundp` for a variable, a registered type otherwise); the
  hard-coded `LispNames` spellings agree with the inventory; the unimplemented
  member signals naming the operation; the unimplemented MACRO does not evaluate
  its forms; an implemented macro is never ALSO stubbed; `with-upgradability`'s
  top-level splice; and the printed per-sub-package coverage.
- `UiopLibraryTest` -- selection: both spellings select the one definition, the
  fixpoint, the stub dragging in the condition it signals, the four
  `MACRO_EXPANSION_CALLEES` surface rules, idempotence, the already-defined
  guard, and that the prelude pass drives this one.
- Behaviour: the `evalUiop*` block of `LispEvaluatorTest` (strings, the character
  quartet, timestamps, `access-at` + function designators, lists/plists/hashes,
  the macros, the condition helpers, and the two that name what is missing);
  `JvmLispCompilerTest.compileAndRunUiop*` and the `uiop*CompileAndRun` /
  `uiopWithUpgradability*` cases of `WasmLispCompilerIntegrationTest` for the
  four with real codegen shape (`strcat`, `string-prefix-p`, `nest`,
  `while-collecting`) plus the splice-selection they depend on; and the
  `uiop-utility-helpers` ci-spec case end to end on all four.

## Deliberate extras (`uiop` owns them; the inventory does not list them)

- `uiop:namestring` -- upstream only INHERITS CL's through
  `(:use :uiop/common-lisp)`, so `uiop:namestring` would not read there. Kept
  external and imported from `cl`, so both spellings name the one prelude
  function.
- `uiop:when-let` / `uiop:when-let*` -- alexandria's names, not uiop's (real
  uiop exports `if-let` only). Kept because programs already spell them.

`uiop::get-pathname-defaults` used to be a third extra (a hand-added internal
answering the literal `""`); todo-357 retired it -- the inventory's
`UIOP/FILESYSTEM` row covers the name and the definition reads
`*default-pathname-defaults*` (see "`uiop/pathname`'s decisions" above).
