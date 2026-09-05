# uiop: the sub-package bundle, the inventory, and `not-implemented-error`

**Invariant: no `uiop` name may reach a caller as `The function UIOP:X is undefined`.** uiop 3.3.7
exports 429 symbols; the implemented subset grows, every other name signals
`uiop:not-implemented-error` naming the operation, identically on all four backends. Coverage
target is DATA (`uiop-exports.txt`), not a Java literal; `UiopCoverageTest` is the gate. Supersedes
the uiop paragraphs in `.kb/asdf.md`.

**Acceptance criterion: every uiop function either runs on all four backends or signals
`not-implemented-error` identically on all four.** `merge-pathnames*` was the one violation (a
Java built-in on the interpreter only); it, `file-exists-p` and `native-namestring` are Lisp now,
and `expandUiopStubCall` kept only its real folds.

## The 15 sub-packages
`uiop` IS `uiop/driver`, a `:use-reexport` of `uiop/package`, `uiop/package-local-nicknames`,
`uiop/package*`, `uiop/utility`, `uiop/version`, `uiop/os`, `uiop/pathname`, `uiop/filesystem`,
`uiop/stream`, `uiop/image`, `uiop/launch-program`, `uiop/run-program`, `uiop/lisp-build`,
`uiop/configuration`, `uiop/backward-driver`. Either spelling may be named (naming an absent
package is a READ error). `PackageRegistry` registers all 15 from `UiopExports` via the
`closer-common-lisp` mechanism: each sub-package OWNS its own inventory rows, a second exporter is
an IMPORT REDIRECT, `uiop` owns nothing.

**The HOME spelling is canonical**: `PackageResolver` rewrites `uiop:getenv` to `UIOP/OS:GETENV`.
`UiopExports.qualified` composes it; `UiopExports.denotes(pkg, member, X)` recognizes BOTH
spellings, for passes running either side of resolution. `LispNames`' hard-coded qualified names
(`UIOP_GETENV`, `UIOP_SYMBOL_CALL`, `UIOP_IF_LET_QUALIFIED`, `UIOP_WITH_DEPRECATION_QUALIFIED`,
`UIOP_WITH_TEMPORARY_FILE_QUALIFIED`) are switch labels and cannot be computed; pinned by
`UiopCoverageTest.theHardCodedQualifiedNamesAgreeWithTheInventory`.

## The inventory: `uiop-exports.txt`
`src/main/resources/am/ik/rontolisp/uiop-exports.txt`, read by `am.ik.rontolisp.UiopExports` (root
package, so `PackageRegistry` and `eval.UiopLibrary` both use it).

- `<sub-package> TAB <symbol> TAB <kind>`, `#` comments. **435 rows / 429 distinct symbols / 15
  sub-packages.** Upstream LOAD order, alphabetical within a sub-package: the FIRST row for a
  symbol names its home.
- `kind` = upstream's DEFINITION form (`function`, `macro`, `variable`, `constant`, `condition`,
  `class`, `type`, `+`-joined when defined twice). Needed so a stub has the right SHAPE: a `defun`
  for a `defvar` name satisfies `fboundp` and answers the wrong predicate forever.
- Regenerate only when the pinned uiop version moves: the extractor reads `define-package` forms
  from `~/.rontolisp/quicklisp/software/uiop-<version>/` with `*features*` bound to
  `(:package-local-nicknames)` only.

## `eval.UiopLibrary`: one home for every definition
Definitions live in `uiop-<sub-package>.lisp` beside the class (`package`, `utility`, `os`,
`pathname`, `filesystem`, `stream`, `image`, `lisp-build`).

- **A resource may only define names the inventory lists** (`build()` fails loudly). No private
  helpers: `flet`/`labels`, or a `%`-prefixed PRELUDE entry — also where a definition needing
  global state must put it. `(defun (setf NAME) ...)` counts as a definition of NAME; reader and
  writer are one group.
- Anything listed and undefined gets a stub from its kind: `function`/`macro` -> a variadic defun
  signalling; `variable`/`constant` -> `(defvar NAME nil)`; `condition` ->
  `(define-condition NAME (error) ())`; `class` -> `(defclass NAME () ())`; `type` ->
  `(deftype NAME () t)`. Caveats: a `variable` stub is `nil`, not upstream's default; a `constant`
  gets a `defvar`; a `condition`/`class` stub is flat.
- NOT stubbed (something else defines them): `JAVA_DEFINED` (`add-package-local-nickname`, consumed
  at RESOLVE time by `PackageResolver`), `LispMacroExpander.hasUiopMacroExpansion` (`if-let`,
  `with-temporary-file`, `with-deprecation`, `define-package`), and the fold-carrying members
  (`file-exists-p`, `native-namestring`, `symbol-call`), which keep a Lisp definition so `#'name`
  is a value. **A Java-only member has no VALUE the compile paths can materialize** — that is why
  `symbol-call` left `JAVA_DEFINED`.
- **Resources are read with the TARGET backend's `Features`**: `UiopLibrary.process(program,
  features)` / `LispPreludeLibrary.process(program, features)`; one-argument overloads mean
  `Features.INTERPRETER`. `featurep` evaluates against `*features*` (`.kb/reader-features.md`) —
  everything per-backend in `uiop/os` derives from it. **A test harness compiling a uiop program
  must pass the same set** (`Features.WASM` in `WasmLispCompilerIntegrationTest`).
- **A `macro` stub is a `defun`** so the name is `fboundp`, but the CALL FORM never reaches it:
  `LispMacroExpander.expandUnimplementedUiopMacro` lowers it with argument forms DROPPED. **TRAP:
  on the compile paths it must be applied in the expression compiler's uiop branch, AHEAD of the
  ordinary call path** — the stub is a real variadic defun, so the call path finds it in
  `ctx.functions` and compiles the arguments first. Symptom:
  `(uiop:with-current-directory ("/tmp") ...)` compiled `("/tmp")` as a CALL on both compile paths
  while the interpreter was correct; invisible while every test used an EMPTY spec list. Pinned by
  `compileAndRunUiopUnimplementedMacroDropsItsArgumentForms` /
  `uiopUnimplementedMacroDropsItsArgumentFormsCompilesAndRuns` + ci-spec, with a non-empty spec.

## The macros: ONE dispatcher
A uiop macro cannot be a `defmacro` in a resource — `UserMacroExpander` runs BEFORE the uiop
splice. Every uiop macro with a real expansion is a Java expansion in `LispMacroExpander`, listed in
`UIOP_MACRO_EXPANSIONS`. `LispMacroExpander.expandUiopMacro(cons, unwindProtect)` is the single
dispatcher, called from `LispEvaluator.evalCons`, `JvmExprCompiler`, `WasmExprCompiler` and BOTH
`FreeVarAnalyzer` walks (several of these BIND or REARRANGE forms the default walk reads as calls).
`unwindProtect` is for `with-temporary-file` alone (`ctx.ehMode` on WASM, true elsewhere).

- `os-cond` -> a plain `cond` (upstream evaluates clause TESTS at macroexpansion time).
- `with-deprecation` / `with-upgradability` also splice at TOP LEVEL (`flattenTopLevel` via
  `isUiopDefinitionWrapper`, matching both spellings): they wrap definitions.

## Per-sub-package verdicts
`uiop/utility` — `with-upgradability` -> `progn`; one character type (`+character-types+` =
`#(character)`, `+non-base-chars-exist-p+` NIL, `base-string-p` always t);
`register-hook-function` signals (needs `(setf (symbol-value var) ...)` to be a place — the day it
is, the body is three `pushnew` lines); `uiop-debug`/`load-uiop-debug-utility` signal (need a
run-time `load` of a computed pathname); `match-condition-p`'s STRING pattern compares against the
ALREADY FORMATTED message; `coerce-class` drops upstream's `*package*` fallback;
`ensure-function`'s `:package` ignored; `timestamps<` chains from `nil` = +infinity (upstream's
answer, pinned so nobody "fixes" it); `frob-substrings` returns a FRESH string (upstream's
`return-from` out of `labels` would put every user into EH mode on WASM).

`uiop/os` (22/22, all Lisp in `uiop-os.lisp`) — **every host answer derives from ONE source,
upstream's `featurep`**. `architecture` = `:wasm32` / `:jvm` (the ABI the ARTIFACT targets).
**`os-unix-p` is `t` outright, NOT `(featurep :unix)`** — adding `:unix` to `Features` would flip
the `#+unix` branch of every library read (`.kb/pathnames.md`); `os-macosx-p`/`os-windows-p`/
`os-genera-p` nil. **Environment READ from the host, WRITTEN to an override map** consulted BEFORE
the host: primitive `rontolisp::%host-getenv` (`LispNames.HOST_GETENV`; `Environment`,
`JvmGetenvCompiler`, `WasmGetenvCompiler`'s Preview 1 environ scan, `environment.lisp`'s
wit-imported `wasi:cli/environment` under `--component`, keyed by `EnvironmentLibrary`) —
**its trigger is the PRIMITIVE, so it must run AFTER the uiop splice**. The store is two PRELUDE
entries (`%getenv-override`, `%getenv-override-set`) each carrying its own defvar; a nil value is
an UNSET, which is why the reader answers the whole ENTRY, not its cdr. Writer =
`(defun (setf uiop/os:getenv) ...)`; a program whose FIRST touch is the write needs
`LispEvaluator.ensureUiopSetfPlaceLoaded`. `hostname` -> nil (trigger: a `machine-instance`
built-in). `getcwd` real where the host has a working directory, signals where it does not
(`%host-getcwd` answers `user.dir` / NIL, and ONE shared Lisp definition turns that nil into the
error — the divergence is a VALUE, not a second code path); `chdir` signals everywhere;
`with-current-directory` INHERITS this and must not invent a second decision. The `.lnk` pair
(`parse-windows-shortcut`, `parse-file-location-info`) signals naming `file-position`
(`.kb/read-load-streams.md`); `read-little-endian` / `read-null-terminated-string` are real.

`uiop/pathname` (50/50, `uiop-pathname.lisp` over the flat-namestring model of
`.kb/pathnames.md`) — logical pathnames follow the CL half's commitment (`logical-pathname-p` nil,
`physical-pathname-p` = `pathnamep`, `make-pathname-logical` signals,
`make-pathname-component-logical` real). The `*wild-*` constants are namestring literals (`"*"`,
`#P"*.*"`, `#P"**/"`, `#P"**/*.*"`), the two wildcards `%wild-match` reads. **`ensure-pathname`
signals DIRECTLY on the default error path** (`%ens-err`) — a funcalled `#'error` wrapper is a raw
TRAP on WASM where a direct call is catchable; lite otherwise (`:want-logical` always fails,
`:resolve-symlinks`/`:truenamize` ignored, `:truename` = `probe-file`).
`ensure-absolute-pathname` keeps its documented divergence. `with-pathname-defaults` and
`with-enough-pathname` live in `LispMacroExpander`; their `MACRO_EXPANSION_CALLEES` rows show the
table also carries a VARIABLE (`uiop:*nil-pathname*`). `split-name-type` and
`split-unix-namestring-directory-components` return MULTIPLE VALUES through the spill channel
(`.kb/multiple-values.md`). `cli/CompileTimePathnameFolder` folds `subpathname` over literal
arguments, mirroring the Lisp definition exactly — including the bare-filename fast path and
declining an absolute STRING subpath. `find-symbol*`/`find-package*` (`uiop-package.lisp`) underpin
`find-standard-case-symbol`, `coerce-class`, `symbol-test-to-feature-expression`
(`.kb/symbol-runtime-api.md`).

`uiop/image` (30/30, `uiop-image.lisp` except the exit PRIMITIVE) — **`quit` is the host's exit on
all four backends through ONE definition** over `%host-exit` (`LispNames.HOST_EXIT`): finishes
`*standard-output*`/`*error-output*` first and masks the code to eight bits, so `(uiop:quit 300)`
is 44 everywhere; `die` and `shell-boolean-exit` are written over it. Interpreter raises
`eval.LispExitSignal`, which escapes `RontoLispCli.run` and becomes the process code in `main`
(`run` is embedded, and killing the calling JVM is not a call's decision); JVM emits `System.exit`
(`JvmExitCompiler`, minted in the compiler, not the fixed `systemOps` table); both WASM backends
splice `exit.lisp` (`eval/ExitLibrary`) — Preview 1 binds `proc_exit` under the primitive's OWN
name so no tenth slot appears, `--component` binds `wasi:cli/exit@0.3.0` as an APPENDED USER
IMPORT; a `--no-wasi`/`--no-gc` reactor is REFUSED by name. **`quit` neither unwinds nor is
catchable** (`LispEvaluator.evalUnwindProtect` has an explicit `LispExitSignal` arm running NO
cleanup). Backtraces are lite and stay lite (three members, one rendering, `:count` ignored). The
fatal-condition quartet is real on `handler-bind`; **`*lisp-interaction*` is NIL where upstream
defaults to T** (trigger: an `invoke-debugger`). The image hooks are REAL; `dump-image`,
`restore-image`, `create-image` signal. The command-line five are ONE definition over `%host-argv`
(`LispNames.HOST_ARGV`) answering `(program-name user-arg ...)`, so `raw-command-line-arguments` IS
the primitive; rontolisp is always upstream's executable case because `CliOptions.arguments()`
splits at `--`. Interpreter: `LispEvaluator.setCommandLineArguments`. JVM: a static `_argv` field
stored from main's OWN PROLOGUE (a defun is a static method and cannot see main's locals);
`_argv()` prepends the CLASS NAME; a `jvm-export` library's null field answers nil. **Preview 1's
`args_sizes_get`/`args_get` are APPENDED USER IMPORTS** (`WasmArgvRuntimeBuilder`, via
`PLACEHOLDER_FUNC_BASE + ordinal`), while the `_argv` helper is a FIXED index (`FUNC_ARGV`, after
`FUNC_WRITE_PACKED`, reusing `TYPE_READ_LINE`'s signature). `--component` binds
`wasi:cli/environment@0.3.0`'s `get-arguments`, which DID need the fixed import block (`core.wat` +
`regen.sh` + `FIXED_BLOCK_IFACES`, then `regen-wit.sh` and `WasiWitDefinitions`; the block is
pruned per INTERFACE, checked by `WitOracleE2eTest`). A `--no-wasi` reactor answers nil.

## Selection, not pruning
`UiopLibrary.process` prepends only the definitions the program reaches, to a fixpoint on a
`PackageResolver.resolveProgram` copy. **`MACRO_EXPANSION_CALLEES` is the surface-form rule**
(mirroring `LispPreludeLibrary.referencedBySurfaceForm`): a uiop macro's expansion runs inside the
expression compilers, long after this pass, so without an entry the compiled program says
`The function UIOP/UTILITY:X is undefined` at run time while the interpreter works. Only the DIRECT
callee is listed; the fixpoint pulls the rest.

| surface macro | direct callee(s) |
|---|---|
| `with-temporary-file` | `ensure-directory-pathname`, `default-temporary-directory`, `delete-file-if-exists` (through the prelude's `%temp-file-name`) |
| `with-muffled-conditions` | `call-with-muffled-conditions` |
| `uiop-debug` | `load-uiop-debug-utility` |
| `latest-timestamp-f` | `latest-timestamp` |

**uiop is NOT in `LibraryDefunPruner`'s prunable set.** **`LispPreludeLibrary.process` CALLS
`UiopLibrary.process` first** rather than sitting beside it: the two are mutually dependent, so
they are one pass with a fixed order. Re-running is a no-op
(`UiopLibraryTest.aSecondRunSplicesNothingMore`).

Interpreter lazy-loads on first resolution (`LispEvaluator.loadUiopDefinition`), from the function
and the variable lookup. Two extras go in, both because **a CLASS cannot be lazy the way a function
can**: the whole `UiopLibrary.closureOf(name)` CLOSURE (a quoted condition name is not a function
resolution), and every uiop condition and class (`UiopLibrary.conditionAndClassNames`, 19 rows) on
the first touch of the condition system (`ensureConditionReportRuntimeLoaded` ->
`ensureUiopConditionClassesLoaded`). A handler's type test is built from the class tags known when
the `handler-bind` was EXPANDED. Symptom:
`(handler-bind ((warning #'muffle-warning)) (uiop:style-warn "x"))` muffled on the JVM and both
WASM backends, printed on the interpreter. Residual, NOT uiop-specific: a condition class first
registered inside an already-entered `handler-bind` body still misses.

## Documentation shape
`doc/{en,ja}/reference/uiop.md`: the sub-package model, a coverage table over the 15, the
implemented-member table, and what an unimplemented member signals. `reference/functions.md` keeps
a pointer only. **A sub-package that fills up moves to `reference/uiop/<sub-package>.md`**
(`utility` at 61 members, then `pathname`, `os`, `image`): a page, a `subpages:` entry under
`reference/uiop.md` in every language tree's `nav.yaml`, parent keeps the coverage row and one
sentence. These are `subpages:`, NOT sidebar rows of Language Reference
(`.kb/documentation-site.md`). **When a member MOVES its row leaves the parent's table.**

## Deliberate extras (`uiop` owns them; the inventory does not list them)
`uiop:namestring` (upstream only inherits CL's, so the spelling would not read); `uiop:when-let` /
`uiop:when-let*` (alexandria's names — real uiop exports `if-let` only), kept because programs
already spell them.

## Tests
- `UiopCoverageTest` — the gate: every listed symbol external in `uiop` AND its sub-package and
  defined for its kind; hard-coded `LispNames` spellings; an unimplemented member signals naming
  the operation; an unimplemented MACRO does not evaluate its forms; an implemented macro is never
  ALSO stubbed; `with-upgradability`'s top-level splice; printed per-sub-package coverage.
- `UiopLibraryTest` — selection: both spellings, the fixpoint, a stub dragging in the condition it
  signals, the four `MACRO_EXPANSION_CALLEES` rules, idempotence, the already-defined guard.
- Behaviour: the `evalUiop*` block of `LispEvaluatorTest`; `JvmLispCompilerTest.compileAndRunUiop*`
  (incl. `compileAndRunUiopImageTheCommandLine` and the quitting cases, in a CHILD JVM); the
  `uiop*CompileAndRun` / `uiopWithUpgradability*` cases of `WasmLispCompilerIntegrationTest`;
  `CliOptionsTest.everythingAfterTheSeparatorBelongsToTheProgram`; ci-spec `uiop-utility-helpers`,
  `uiop-os-host-identity`, `uiop-pathname-algebra`, `uiop-image-command-line` (the only test
  anywhere that argument PASSING agrees across the four launchers,
  `CiSpecE2eTest.PROGRAM_ARGUMENTS`), `uiop-image-hooks-and-backtraces` (the family MINUS the exit
  half — the driver concatenates cases into one program and a `quit` would end the run).
