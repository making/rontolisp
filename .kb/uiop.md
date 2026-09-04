# uiop: the sub-package bundle, the inventory, and `not-implemented-error`

**Invariant: no `uiop` name may reach a caller as `The function UIOP:X is undefined`.**
uiop 3.3.7 exports 429 symbols; the implemented subset grows, every other name signals
`uiop:not-implemented-error` naming the operation, identically on all four backends.
Coverage target is DATA (`uiop-exports.txt`), not a Java literal; `UiopCoverageTest` is the
gate. Supersedes the uiop paragraphs in `.kb/asdf.md`.

## The 15 sub-packages

`uiop` IS `uiop/driver`, a `:use-reexport` of `uiop/package`,
`uiop/package-local-nicknames`, `uiop/package*`, `uiop/utility`, `uiop/version`, `uiop/os`,
`uiop/pathname`, `uiop/filesystem`, `uiop/stream`, `uiop/image`, `uiop/launch-program`,
`uiop/run-program`, `uiop/lisp-build`, `uiop/configuration`, `uiop/backward-driver`. Either
spelling may be named (`(:import-from :uiop/image :print-condition-backtrace)` is a READ
error if the package is absent).

`PackageRegistry` registers all 15 from `UiopExports` via the `closer-common-lisp`
mechanism: each sub-package OWNS/EXPORTS its own inventory rows; a member a second
sub-package also exports is an IMPORT REDIRECT to the home one; `uiop` owns nothing --
all 429 externals are redirects.

**The HOME spelling is canonical.** `PackageResolver` rewrites `uiop:getenv` to
`UIOP/OS:GETENV` -- the name a definition carries, a switch label matches, an error message
shows. `UiopExports.qualified` composes it; `UiopExports.denotes(pkg, member, X)` recognizes
BOTH spellings, for passes running either side of resolution (backend dispatch gate: after;
`GenericDispatchNarrowing`: before). `LispNames`' hard-coded qualified names
(`UIOP_GETENV`, `UIOP_SYMBOL_CALL`, `UIOP_IF_LET_QUALIFIED`,
`UIOP_WITH_DEPRECATION_QUALIFIED`, `UIOP_WITH_TEMPORARY_FILE_QUALIFIED`) are switch-label
constants and cannot be computed; pinned by
`UiopCoverageTest.theHardCodedQualifiedNamesAgreeWithTheInventory`.

## The inventory: `uiop-exports.txt`

`src/main/resources/am/ik/rontolisp/uiop-exports.txt`, read by `am.ik.rontolisp.UiopExports`
(root package, so `PackageRegistry` and `eval.UiopLibrary` both use it).

- `<sub-package> TAB <symbol> TAB <kind>`, `#` comments, ONE ROW PER EXPORT. **435 rows /
  429 distinct symbols / 15 sub-packages.**
- Upstream LOAD order, alphabetical within a sub-package: the FIRST row for a symbol names
  its home, later rows are redirects.
- `kind` = upstream's DEFINITION form (`function`, `macro`, `variable`, `constant`,
  `condition`, `class`, `type`, `+`-joined when defined twice -- `not-implemented-error` is
  a condition AND the function signalling it). Needed so a stub has the right SHAPE: a
  `defun` for a `defvar` name satisfies `fboundp` and answers the wrong predicate forever.
- Regenerate only when the pinned uiop version moves: the extractor reads `define-package`
  forms from `~/.rontolisp/quicklisp/software/uiop-<version>/` with `*features*` bound to
  `(:package-local-nicknames)` only (so `use-ecl-byte-compiler-p`, `probe-posix`,
  `sb-grovel-unknown-constant-condition` are absent) and joins each symbol with the
  definition form found by scanning `defun`/`defmacro`/`defvar`/`define-condition`/... and
  `:reader`/`:accessor` slot options.

## `eval.UiopLibrary`: one home for every definition

Definitions live in `uiop-<sub-package>.lisp` resources beside the class (`package`,
`utility`, `os`, `pathname`, `filesystem`, `stream`, `image`, `lisp-build`).

- **A resource may only define names the inventory lists** (`build()` fails loudly). No
  private helpers: use `flet`/`labels`, or a `%`-prefixed PRELUDE entry
  (`LispPreludeLibrary`) -- which is also where a definition needing global state must put
  it.
- `(defun (setf NAME) ...)` counts as a definition of NAME (rontolisp's spelling of
  upstream's `defsetf`); reader and writer are one group -- selected, lazy-loaded and
  counted together.
- Anything listed and undefined gets a stub synthesized from its kind:
  `function`/`macro` -> `(defun NAME (&rest %uiop-stub-args) (uiop:not-implemented-error
  "NAME"))`; `variable`/`constant` -> `(defvar NAME nil)`; `condition` ->
  `(define-condition NAME (error) ())`; `class` -> `(defclass NAME () ())`; `type` ->
  `(deftype NAME () t)`.
- NOT stubbed (something else defines them; a stub would shadow): `JAVA_DEFINED`
  (`add-package-local-nickname`), `LispMacroExpander.hasUiopMacroExpansion` (`if-let`,
  `with-temporary-file`, `with-deprecation`, `define-package`), and the fold-carrying
  members (`file-exists-p`, `native-namestring`, `symbol-call`), which still have a Lisp
  definition so `#'uiop:file-exists-p` is a value.
- **A Java-only member has no VALUE the compile paths can materialize.** `symbol-call` left
  `JAVA_DEFINED` for that: the fold covers CALL position only, so `#'uiop:symbol-call`
  (dexador's `(apply #'uiop:symbol-call '#:pkg '#:name uri args)`) was `Cannot compile:
  UIOP/PACKAGE:SYMBOL-CALL`. It carries upstream's `(apply (find-symbol* name package)
  args)` in `uiop-package.lisp` beside the fold. Interpreter unchanged (its Java built-in
  registers eagerly and resolves first), keeping its two sharper probes (`package X does not
  exist` / `symbol Y is not present in package X`); re-evaluate if the compile paths gain a
  package-membership probe. `add-package-local-nickname` stays Java-only: consumed at
  RESOLVE time by `PackageResolver`.
- Filling a name in = add the real definition to the resource; the stub disappears and
  `UiopCoverageTest.printCoverage` moves.

**Resources are read with the TARGET backend's `Features`**: `UiopLibrary.process(program,
features)` / `LispPreludeLibrary.process(program, features)`, passed by
`RontoLispCli.compileToFile` and the playground frontend; one-argument overloads mean
`Features.INTERPRETER` (also the interpreter's lazy load). `Tables` is cached per feature
set. Reason: `featurep` evaluates against `*features*`, seeded per backend with the set the
frontend read it with (`.kb/reader-features.md`) -- a resource read with the interpreter's
set would answer `:rontolisp-interpreter` inside a wasm module. Everything per-backend in
`uiop/os` derives from `featurep`, so this is the whole per-backend story. **A test harness
compiling a uiop program must pass the same set** (`Features.WASM` in
`WasmLispCompilerIntegrationTest`).

Stub caveats: a `variable` stub is `nil`, not always upstream's default; a `constant` gets a
`defvar` (a placeholder pinned as a constant makes the real definition a redefinition); a
`condition`/`class` stub is flat where upstream has a hierarchy.

**A `macro` stub is a `defun`** so the name is `fboundp` and usable as a value, but the CALL
FORM never reaches it: `LispMacroExpander.expandUnimplementedUiopMacro` lowers it to
`not-implemented-error` with argument forms DROPPED (an unimplemented
`(uiop:with-current-directory (d) (defun f ...))` must not define `f` before signalling).
One expansion shared by the evaluator and both compilers. **TRAP: on the compile paths it
must be applied in the expression compiler's uiop branch, AHEAD of the ordinary call path**
-- the stub is a real variadic `defun`, so the call path finds it in `ctx.functions` and
compiles the arguments first, and `expandUiopStubCall` (which runs only for a name with no
defun) never gets the chance. Symptom: `(uiop:with-current-directory ("/tmp") ...)` compiled
`("/tmp")` as a CALL, `The function "/tmp" is undefined` on both compile paths while the
interpreter was correct; invisible while every test used an EMPTY spec list. Pinned by
`compileAndRunUiopUnimplementedMacroDropsItsArgumentForms` /
`uiopUnimplementedMacroDropsItsArgumentFormsCompilesAndRuns` + ci-spec, with a non-empty
spec.

## The macros: ONE dispatcher

A uiop macro cannot be a `defmacro` in a resource: `UserMacroExpander` runs BEFORE the uiop
splice, so a spliced `defmacro` is never expanded and the backends see an undefined name.
Every uiop macro with a real expansion is a Java expansion in `LispMacroExpander`, listed in
`UIOP_MACRO_EXPANSIONS` (asked by `UiopLibrary` so it does not stub over one, and by
`expandUnimplementedUiopMacro` so it does not lower one to an error).

`LispMacroExpander.expandUiopMacro(cons, unwindProtect)` is the single dispatcher, called
from `LispEvaluator.evalCons`, `JvmExprCompiler`, `WasmExprCompiler` and BOTH
`FreeVarAnalyzer` walks. `unwindProtect` is for `with-temporary-file` alone (`ctx.ehMode` on
WASM, true elsewhere). `FreeVarAnalyzer` needs the dispatcher because several of these BIND
(`if-let`/`when-let` binding lists, `while-collecting` collectors, `with-temporary-file`'s
`:stream`/`:pathname` plist) or REARRANGE (`nest`) forms the default walk reads as calls.

- `os-cond` -> a plain `cond`: upstream evaluates clause TESTS at macroexpansion time
  (needs an evaluator the compile paths lack); the OS predicates here are runtime functions
  selecting the same clause.
- `with-deprecation` / `with-upgradability` also splice at TOP LEVEL (`flattenTopLevel` via
  `isUiopDefinitionWrapper`, matching both spellings since that pass runs either side of
  resolution): they wrap definitions, and burying those in an expression stops them being
  definitions.

## `uiop/utility` decisions

- `with-upgradability` -> `progn` (no image to upgrade, no separate compile-time
  evaluation). A semantic CHOICE: a stub would make every upstream-shaped definition file
  unloadable.
- **One character type**: `(subtypep 'character 'base-char)` and the reverse both answer
  `t`, so upstream's own derivation gives `+character-types+` = `#(character)`,
  `+max-character-type-index+` = 0, `character-type-index` = 0, `+non-base-chars-exist-p+`
  = NIL; `base-string-p` = `(and)` = `t` for every string; `strings-common-element-type` =
  `'character`. A `+non-base-chars-exist-p+` true reading is self-inconsistent, and
  `array-element-type` signals on a string here so `base-string-p` cannot ask it.
- `register-hook-function` signals: it pushes onto a run-time-named variable, i.e.
  `(setf (symbol-value var) ...)`, not a place on any backend, and there is no `cl:set`.
  **Trigger**: the day that becomes a place, the body is three lines of `pushnew`.
- `uiop-debug` / `load-uiop-debug-utility` / `*uiop-debug-utility*`: the variable holds
  upstream's default form (data); the loader signals -- it needs a run-time `load` of a
  COMPUTED pathname, and `load` is a compile-time splice on every backend.
- `match-condition-p`'s STRING pattern compares against
  `simple-condition-format-control`, which answers the ALREADY FORMATTED message, so a
  pattern carrying format directives cannot match.
- `coerce-class` drops upstream's `*package*` fallback for a keyword designator
  (`PackageResolver` makes `*package*` a compile-time constant).
- `ensure-function`'s `:package` accepted and ignored (`read-from-string` reads into the
  current package everywhere).
- `timestamps<` chains from `nil` = +infinity, so a non-empty list is never "increasing" --
  upstream's answer, pinned so nobody "fixes" it.
- `frob-substrings` returns a FRESH string where upstream returns the original: upstream's
  shortcut is a `return-from` out of a `labels` function, a cross-lambda exit that would put
  every user into EH mode on WASM.

## `uiop/os` decisions (22/22)

All Lisp in `uiop-os.lisp`; **every host answer derives from ONE source, upstream's
`featurep` over `*features*`**. `architecture` = `:wasm32` under `(featurep
:rontolisp-wasm)` else `:jvm` (the ABI the ARTIFACT targets -- a class file is
CPU-independent, so the CPU is not the answer); `implementation-identifier` composes
type/version/os/architecture as upstream builds a fasl-cache directory name;
`lisp-version-string` reads `(rontolisp:version)`.

- **`os-unix-p` is `t` outright, NOT `(featurep :unix)`**: every backend presents the
  POSIX-shaped file/namestring model (`.kb/pathnames.md`), but adding `:unix` to `Features`
  would flip the `#+unix` reader branch of every library read (cl-postgres' unix-domain
  socket path, bordeaux-threads, ...). `os-macosx-p`/`os-windows-p`/`os-genera-p` keep
  upstream's derivations and answer nil. `detect-os` pushes `:os-unix` onto `*features*` and
  returns it (upstream's body minus the loop).
- **Environment READ from the host, WRITTEN to an override map.** No backend can rewrite its
  process environment, so `(setf (uiop:getenv name) value)` records into a per-program map
  `getenv` consults BEFORE the host -- one definition, four backends (rove's
  `with-local-envs` forced it; it was a HARD `expandSetf` failure, "setf does not support
  place"). A nil value is an UNSET (upstream's `(setf (getenv x) nil)`), which is why the
  store's reader answers the whole ENTRY, not its cdr: "present and nil" != "absent".
  - `getenv` is no longer `JAVA_DEFINED`; the primitive is `rontolisp::%host-getenv`
    (`LispNames.HOST_GETENV`): `Environment`'s `System.getenv`, `JvmGetenvCompiler`,
    `WasmGetenvCompiler`'s Preview 1 environ scan, and `environment.lisp`'s wit-imported
    `wasi:cli/environment` defun under `--component`, which `EnvironmentLibrary` keys on.
    **Its trigger is the PRIMITIVE, so it must run AFTER the uiop splice** that introduces
    the reference (the CLI order already had it last; test harnesses were moved to match).
  - the override store is two PRELUDE entries (`%getenv-override`, `%getenv-override-set`),
    each carrying its own `(defvar %getenv-overrides nil)` like the `%symbol-plists` family
    -- a uiop resource may not define a private global, and a VARIABLE reference is not a
    lazy-load trigger on the interpreter.
  - writer = `(defun (setf uiop/os:getenv) ...)` keyed under `UIOP/OS:GETENV`. A program
    whose FIRST touch is the write needs a third interpreter lazy-load trigger:
    `LispEvaluator.ensureUiopSetfPlaceLoaded`, before `expandSetf` reads the place registry.
- `hostname` -> nil (upstream's answer where no `#+` clause names the implementation; no
  backend has a host-identity primitive). **Trigger**: a `machine-instance` built-in makes
  it one line.
- **`getcwd` real where the host has a working directory, signals where it does not; `chdir`
  signals everywhere.** `%host-getcwd` (`LispNames.HOST_GETCWD`) answers `user.dir` on
  interpreter/JVM (`JvmGetcwdCompiler`) and NIL on both WASM backends (a WASI program has
  preopened directories, no current one); ONE shared Lisp definition turns that nil into
  `not-implemented-error`, so the divergence is a VALUE, not a second code path. `chdir` has
  no primitive (Java reads `user.dir` once at startup; WASI has no `chdir`). **Trigger**: a
  host that lets the process move makes `chdir` real and removes `getcwd`'s signalling arm.
  `uiop/filesystem`'s `with-current-directory` sits on top and INHERITS this -- it must not
  invent a second decision.
- **The `.lnk` pair signals, naming the primitive it needs**: `read-little-endian` and
  `read-null-terminated-string` are real (portable `read-byte`, pinned over flexi-streams'
  in-memory octet stream), but `parse-windows-shortcut` / `parse-file-location-info` need
  `file-position`, nil for every file stream here (`.kb/read-load-streams.md`). **Trigger**:
  the day `file-position` works on a binary file stream, both are upstream's bodies verbatim.

Pins: `evalUiopOs*` (three in `LispEvaluatorTest`, incl. setf-place-first lazy load),
`JvmLispCompilerTest.compileAndRunUiopOsHostIdentityAndGetenvOverride` /
`.compileAndRunUiopOsOctetReaders`,
`WasmLispCompilerIntegrationTest.uiopOsHostIdentityAndGetenvOverrideCompileAndRun` (also
pins the override WINNING over a `--env` host value) / `.uiopOsOctetReadersCompileAndRun`,
ci-spec `uiop-os-host-identity` (architecture and getcwd are the declared per-backend
difference). Docs: `reference/uiop/os.md`.

## `uiop/pathname` decisions (50/50)

All Lisp in `uiop-pathname.lisp` over the flat-namestring model of `.kb/pathnames.md`
(`%pathname-split` / `%path-ns` / `pathname` / `namestring` / `wild-pathname-p` /
`translate-pathname`), so upstream's component-wise care collapses onto namestring
computation.

- **Logical pathnames follow the CL half's commitment** (no logical host definable, no
  `logical-pathname-translations`, `logical-pathname` always signals,
  `translate-logical-pathname` = identity): `logical-pathname-p` -> nil for everything,
  `physical-pathname-p` = `pathnamep`, `physicalize-pathname` = coercing identity,
  `make-pathname-logical` signals. `make-pathname-component-logical` IS real (`:unspecific`
  -> nil, else identity).
- **The `*wild-*` constants are namestring literals**: `*wild*` = `"*"`, `*wild-file*` =
  `#P"*.*"`, `*wild-inferiors*` = `#P"**/"`, `*wild-path*` = `#P"**/*.*"` -- the two
  wildcards `%wild-match` reads, not upstream's `:wild` keywords.
- **`ensure-pathname` signals DIRECTLY on the default error path** (`%ens-err`: nil/t/'error
  -> a direct `(error ...)`, else `call-function`). A funcalled `#'error` wrapper is a raw
  TRAP on WASM where a direct call is a catchable signal, and
  `(handler-case (ensure-pathname ...) (error () ...))` must catch on all four. Lite
  otherwise: report `Invalid pathname ~S: ~A` (no `~?` chain), `:want-logical` always fails,
  `:resolve-symlinks`/`:truenamize` ignored, `:truename` answers `probe-file`.
- `ensure-absolute-pathname` keeps its documented divergence (a relative path with no
  absolute default is answered as itself, not an error): rontolisp absolutizes nowhere.
  `get-pathname-defaults` (home `uiop/filesystem`, defined in `uiop-filesystem.lisp`) reads
  `*default-pathname-defaults*`, retiring the old `""` Java built-in, the
  `expandUiopStubCall` fold and `PackageRegistry`'s hand-added internal symbol.
- **`with-pathname-defaults` and `with-enough-pathname`** live in `LispMacroExpander`
  (`UIOP_MACRO_EXPANSIONS`). Their `MACRO_EXPANSION_CALLEES` rows show the table also
  carries a VARIABLE: the no-defaults arm binds `uiop:*nil-pathname*`, and selection splices
  a defvar exactly like a defun. `with-enough-pathname`'s `:pathname` defaults to the SPEC
  VARIABLE itself; the `*default-pathname-defaults*` rebinding both emit is a real dynamic
  `let` on all four.
- `split-name-type` and `split-unix-namestring-directory-components` return MULTIPLE VALUES
  through the spill channel (`.kb/multiple-values.md`); `parse-unix-namestring` consumes
  them across the function boundary, pinned compiled.
- `cli/CompileTimePathnameFolder` folds `subpathname` over literal arguments (the
  `merge-pathnames*` precedent), mirroring the Lisp definition exactly -- including the
  bare-filename fast path where `"."` is a NAME, and declining an absolute STRING subpath
  (at run time that arm is a `:want-relative` error a fold must not fold away).

Pins: `evalUiopPathname*` / `evalUiopSplitNameType*` / `evalUiopWildPathnames*` /
`evalUiopEnsurePathname*` in `LispEvaluatorTest`,
`JvmLispCompilerTest.compileAndRunUiopPathnameAlgebra`,
`WasmLispCompilerIntegrationTest.uiopPathnameAlgebraCompileAndRuns`, ci-spec
`uiop-pathname-algebra`. Docs: `reference/uiop/pathname.md`.

`find-symbol*` / `find-package*` (`uiop-package.lisp`) landed with the utility work:
`find-standard-case-symbol`, `coerce-class` and `symbol-test-to-feature-expression` are all
written over `find-symbol*`. Compiled-backend status answer: `.kb/symbol-runtime-api.md`.

## `uiop/image` decisions (30/30)

All Lisp in `uiop-image.lisp` except the exit PRIMITIVE.

**`quit` is the host's exit on all four backends through ONE definition** over `%host-exit`
(`LispNames.HOST_EXIT`). It finishes `*standard-output*` and `*error-output*` first
(upstream's obligation; keeps a buffered program's last lines) and masks the code to eight
bits (what a POSIX host does and what `wasi:cli/exit`'s `u8` accepts), so `(uiop:quit 300)`
is 44 everywhere. `die` and `shell-boolean-exit` are written over it. First-party consumer:
the `rontolisp test` runner's generated epilogue (`.kb/asdf.md`).

- interpreter raises `eval.LispExitSignal`, which ESCAPES `RontoLispCli.run` and becomes the
  process code in `main` (`runReporting` catches it ahead of the `RuntimeException` arm).
  `run` is embedded (tests, playground) and killing the calling JVM is not a call's decision
  -- the rule `JvmUncaughtHandler` follows.
- JVM emits `System.exit` (`JvmExitCompiler`) -- the ONE place a generated class ends its
  JVM, allowed because the program asked for it. Minted in the compiler, not the fixed
  `systemOps` table, so a non-quitting program emits the same bytes.
- both WASM backends run the spliced `exit.lisp` (`eval/ExitLibrary`): Preview 1 binds
  `wasi_snapshot_preview1`'s `proc_exit` through an ordinary `rontolisp:wasm-import` under
  the primitive's OWN name (`%host-exit`), so no tenth preview1 slot and no adapter entry
  point; `--component` binds `wasi:cli/exit@0.3.0`'s `exit-with-code` as an APPENDED USER
  IMPORT (the fixed block does not declare it), like `wasi:sockets` / `wasi:http`. A
  non-quitting program compiles to the same bytes on both.
- a `--no-wasi` / `--no-gc` reactor is REFUSED at compile time by name: no WASI world, no
  process of its own to end.

**`quit` neither unwinds nor is catchable, on any backend.**
`LispEvaluator.evalUnwindProtect` has an explicit `LispExitSignal` arm running NO cleanup;
`handler-case` cannot see it (the interpreter catches `LispEvalException`; the signal is not
one). Pinned by `(print :before) (unwind-protect (handler-case (uiop:quit 3) ...) (print
:cleanup))` in all three per-backend test classes: every backend prints `:BEFORE` and exits
3.

- **Backtraces are lite and stay lite**: no backend carries a Lisp-level call stack, so
  `raw-print-backtrace` prints its `:condition` and no frames, `print-backtrace` applies it,
  `print-condition-backtrace` calls that -- three members, one rendering; `:count` ignored.
- **The fatal-condition quartet is real** on `handler-bind`: `fatal-condition` is a
  `deftype` alias for `serious-condition` (a runtime `typep` and a `handler-bind` clause both
  match a deftype on all four), and `with-fatal-condition-handler` is a Java expansion with
  its `MACRO_EXPANSION_CALLEES` row. **`*lisp-interaction*` is NIL where upstream defaults to
  T** -- every backend runs a program and ends, and there is no `invoke-debugger`; that value
  makes `handle-fatal-condition` report and `die 99`, and its interactive arm names the
  missing primitive. **Trigger**: an `invoke-debugger` makes the arm one line.
- **The image hooks are REAL; only the image is not.** `dump-image`, `restore-image`,
  `create-image` signal `not-implemented-error`; the two registrars, two callers and six
  variables are ordinary list work (a library may register into a hook while it loads).
  Upstream routes both registrars through `register-hook-function`, which signals here;
  naming the variable literally is the same registration. **Trigger**: the day
  `(setf (symbol-value ...))` is a place, both bodies become upstream's one call.
- **The command-line five are ONE definition over one primitive**: `%host-argv`
  (`LispNames.HOST_ARGV`) answers `(program-name user-arg ...)` everywhere, so
  `raw-command-line-arguments` IS the primitive, `command-line-arguments` its `rest`,
  `argv0` its `first` -- no per-backend arm. rontolisp is always upstream's executable case
  because the CLI splits at the separator ITSELF (`CliOptions.arguments()`, everything after
  `--`) and a compiled artifact never sees a rontolisp option. `*command-line-arguments*` is
  SEEDED from `(command-line-arguments)` at load time (upstream fills it from
  `restore-image`, which no backend can do).
  - interpreter: the vector the CLI threads in (`LispEvaluator.setCommandLineArguments`) --
    input file as argv0, `rontolisp` itself for `-e` / `test` / the REPL, then the arguments
    after `--`. An EMBEDDED run leaves it empty.
  - JVM: `main`'s `String[]` stored into a static `_argv` field from main's OWN PROLOGUE (a
    defun reading the command line is a static method and cannot see main's locals);
    `_argv()` prepends the CLASS NAME. A `jvm-export` library runs its top level in
    `<clinit>` before any main could store one; the null field answers nil.
  - **Preview 1's `args_sizes_get` / `args_get` are APPENDED USER IMPORTS**, not fixed slots
    (`WasmArgvRuntimeBuilder`, called through `PLACEHOLDER_FUNC_BASE + ordinal` like
    `--host-random`'s entropy import): the eleven index-pinned preview1 slots do not grow, no
    `--no-wasi` stub slot appears, the `--component` adapter's export list is untouched. The
    `_argv` helper is a FIXED index (`FUNC_ARGV`, after `FUNC_WRITE_PACKED`, reusing
    `TYPE_READ_LINE`'s `() -> (ref null eq)`), with a nil-answering stub body.
  - `--component` binds `wasi:cli/environment@0.3.0`'s `get-arguments` in `environment.lisp`
    beside `get-environment` -- which DID need the fixed import block to declare it
    (`core.wat` + `regen.sh` + `FIXED_BLOCK_IFACES`, then `regen-wit.sh` and
    `WasiWitDefinitions`; the block is pruned per INTERFACE, so every component importing the
    environment interface declares both members, checked by `WitOracleE2eTest` against
    `wasm-tools`). `EnvironmentLibrary` splices per NAME, so a getenv-only program binds only
    `get-environment`.
  - a `--no-wasi` reactor answers nil (no WASI world; entered through exported functions) --
    the value-not-a-code-path rule `%host-getcwd` follows.

Pins: `LispEvaluatorTest.evalUiopImageTheCommandLine*`,
`JvmLispCompilerTest.compileAndRunUiopImageTheCommandLine` (a child JVM, the only place a
real command line exists),
`WasmLispCompilerIntegrationTest.uiopImageTheCommandLineCompilesAndRuns`,
`CliOptionsTest.everythingAfterTheSeparatorBelongsToTheProgram`, ci-spec
`uiop-image-command-line` (the only test anywhere that argument PASSING agrees across the
four launchers, `CiSpecE2eTest.PROGRAM_ARGUMENTS`); the `evalUiopImage*` block of
`LispEvaluatorTest` (five tests, incl. the exit code and the no-cleanup rule),
`JvmLispCompilerTest.compileAndRunUiopImageHooksBacktracesAndTheImageItself` /
`.compileAndRunUiopImageQuitEndsTheProcessWithItsCode` (quitting cases in a CHILD JVM),
`WasmLispCompilerIntegrationTest.uiopImageHooksBacktracesAndTheImageItselfCompileAndRun` /
`.uiopImageQuitEndsTheProcessWithItsCode`, ci-spec `uiop-image-hooks-and-backtraces` --
which carries the family MINUS the exit half (the driver concatenates cases into one program
and a `quit` would end the run). Docs: `reference/uiop/image.md`.

## Selection, not pruning

`UiopLibrary.process` prepends only the definitions the program reaches, to a fixpoint on a
`PackageResolver.resolveProgram` copy (so `uiop:name` matches the home symbol it denotes).

**`MACRO_EXPANSION_CALLEES` is the surface-form rule** (mirroring
`LispPreludeLibrary.referencedBySurfaceForm`): a uiop MACRO's expansion runs inside the
expression compilers, long after this pass, so the names it introduces never occur in the
program this pass sees. Without an entry the compiled program says `The function
UIOP/UTILITY:X is undefined` at run time while the interpreter works -- invisible until
someone runs the artifact. Only the DIRECT callee is listed; the fixpoint pulls in the rest.

| surface macro | direct callee(s) |
|---|---|
| `with-temporary-file` | `ensure-directory-pathname`, `default-temporary-directory`, `delete-file-if-exists` (through the prelude's `%temp-file-name`) |
| `with-muffled-conditions` | `call-with-muffled-conditions` |
| `uiop-debug` | `load-uiop-debug-utility` |
| `latest-timestamp-f` | `latest-timestamp` |

**uiop is NOT in `LibraryDefunPruner`'s prunable set** (the usocket precedent): selecting up
front is the same saving one step earlier and avoids a full resolution pass over 429
definitions. Revisit only if a SELECTED definition is provably unreachable.

**`LispPreludeLibrary.process` CALLS `UiopLibrary.process` first** rather than sitting beside
it: the two are mutually dependent (a uiop body calls
`namestring`/`pathname`/`merge-pathnames`/`directory`/`%dir-namestring`; the prelude's
`%temp-file-name` calls uiop back), so they are one pass with a fixed order. Re-running is a
no-op (`UiopLibraryTest.aSecondRunSplicesNothingMore`).

Interpreter lazy-loads on first resolution (`LispEvaluator.loadUiopDefinition`), from the
function and the variable lookup. Two extras go in, both because **a CLASS cannot be lazy the
way a function can**:

- the whole `UiopLibrary.closureOf(name)` CLOSURE, not just that name -- `style-warn` signals
  `(make-condition 'uiop:simple-style-warning ...)`, and a quoted condition name is not a
  function resolution;
- every uiop condition and class (`UiopLibrary.conditionAndClassNames`, 19 rows) on the first
  touch of the condition system -- `ensureConditionReportRuntimeLoaded` calls
  `ensureUiopConditionClassesLoaded`. A handler's type test is built from the class tags known
  when the `handler-bind` was EXPANDED, so a class registered while the body runs is invisible
  to it. Symptom: `(handler-bind ((warning #'muffle-warning)) (uiop:style-warn "x"))` muffled
  on the JVM and both WASM backends, printed on the interpreter. Registering on first
  condition-system use confines the cost and adds no divergence: a program can only observe a
  class it NAMES, and naming it makes the compile path splice its definition (`process`
  collects quoted symbols).

Residual, NOT uiop-specific: a type test is baked at expansion time, so a condition class
first registered inside an already-entered `handler-bind` body still misses. Reproduces with
a plain `eval`'d `define-condition`.

## Acceptance criterion

**Every uiop function either runs on all four backends or signals `not-implemented-error`
identically on all four.** `uiop:merge-pathnames*` was the one violation (a Java built-in on
the interpreter only, so a non-literal call was `The function UIOP:MERGE-PATHNAMES* is
undefined` on the JVM and both WASM backends); it is Lisp in `uiop-pathname.lisp` over
`cl:merge-pathnames` now, as are `file-exists-p` and `native-namestring`.
`expandUiopStubCall` kept only its real folds and lost its error arm.

## Documentation shape

`doc/{en,ja}/reference/uiop.md`: the sub-package model, a coverage table over the 15, the
implemented-member table, and what an unimplemented member signals.
`reference/functions.md` keeps a pointer only. **A sub-package that fills up moves to
`reference/uiop/<sub-package>.md`** (`uiop/utility` first at 61 members, then
`uiop/pathname`, `uiop/os`): a page, a `subpages:` entry under `reference/uiop.md` in every
language tree's `nav.yaml`, parent keeps the coverage row (a link) and one sentence. These
are `subpages:`, NOT sidebar rows of Language Reference (`.kb/documentation-site.md`).
Per-operator detail pages only for names user programs call. **When a member MOVES to a
sub-package page its row leaves the parent's implemented-member table.**

## Tests

- `UiopCoverageTest` -- the gate: every listed symbol external in `uiop` AND its
  sub-package; every listed symbol defined (`fboundp` for function/macro, `boundp` for
  variable, a registered type otherwise); hard-coded `LispNames` spellings agree with the
  inventory; an unimplemented member signals naming the operation; an unimplemented MACRO
  does not evaluate its forms; an implemented macro is never ALSO stubbed;
  `with-upgradability`'s top-level splice; the printed per-sub-package coverage.
- `UiopLibraryTest` -- selection: both spellings select the one definition, the fixpoint, the
  stub dragging in the condition it signals, the four `MACRO_EXPANSION_CALLEES` surface
  rules, idempotence, the already-defined guard, and that the prelude pass drives this one.
- Behaviour: the `evalUiop*` block of `LispEvaluatorTest`;
  `JvmLispCompilerTest.compileAndRunUiop*`; the `uiop*CompileAndRun` /
  `uiopWithUpgradability*` cases of `WasmLispCompilerIntegrationTest` for the four with real
  codegen shape (`strcat`, `string-prefix-p`, `nest`, `while-collecting`); ci-spec
  `uiop-utility-helpers`.

## Deliberate extras (`uiop` owns them; the inventory does not list them)

- `uiop:namestring` -- upstream only INHERITS CL's through `(:use :uiop/common-lisp)`, so
  `uiop:namestring` would not read there. Kept external and imported from `cl`.
- `uiop:when-let` / `uiop:when-let*` -- alexandria's names, not uiop's (real uiop exports
  `if-let` only). Kept because programs already spell them.
