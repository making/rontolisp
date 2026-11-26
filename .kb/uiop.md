# uiop: the sub-package bundle, the inventory, and `not-implemented-error`

**Invariant: no `uiop` name may reach a caller as `The function UIOP:X is undefined`.** uiop 3.3.7
exports 429 symbols; every name not implemented signals `uiop:not-implemented-error` naming the
operation, identically on all four backends. The coverage target is DATA (`uiop-exports.txt`), not a
Java literal; `UiopCoverageTest` is the gate. Supersedes the uiop paragraphs in `.kb/asdf.md`.

**Acceptance criterion: every uiop function either runs on all four backends or signals
`not-implemented-error` identically on all four** (so no Java-only built-in on the interpreter).
**The one exception is a host capability WASI lacks**, decided like `ffi:`: `run-program` runs on
the JVM family and signals with that reason on both WASM backends ("uiop/launch-program and
uiop/run-program"). Re-evaluate on a WASI process proposal.

## The 15 sub-packages
`uiop` IS `uiop/driver`, a `:use-reexport` of the 15 sub-packages. Either spelling may be named
(naming an absent package is a READ error). `PackageRegistry` registers all 15 from `UiopExports`
via the `closer-common-lisp` mechanism: each sub-package OWNS its own inventory rows, a second
exporter is an IMPORT REDIRECT, `uiop` owns nothing.

**The HOME spelling is canonical**: `PackageResolver` rewrites `uiop:getenv` to `UIOP/OS:GETENV`.
`UiopExports.denotes(pkg, member, X)` recognizes BOTH spellings, for passes running either side of
resolution. `LispNames`' hard-coded qualified names are switch labels and cannot be computed;
pinned by `UiopCoverageTest.theHardCodedQualifiedNamesAgreeWithTheInventory`.

## The inventory: `uiop-exports.txt`
`src/main/resources/am/ik/rontolisp/uiop-exports.txt`, read by `am.ik.rontolisp.UiopExports` (root
package, so `PackageRegistry` and `eval.UiopLibrary` both use it).

- Upstream LOAD order: the FIRST row for a symbol names its home.
- `kind` = upstream's DEFINITION form, so a stub has the right SHAPE: a `defun` for a `defvar` name
  satisfies `fboundp` and answers the wrong predicate forever.
- Regenerate only when the pinned uiop version moves: the extractor reads `define-package` forms
  from `~/.rontolisp/quicklisp/software/uiop-<version>/` with `*features*` bound to
  `(:package-local-nicknames)` only.

## `eval.UiopLibrary`: one home for every definition
Definitions live in `uiop-<sub-package>.lisp` beside the class.

- **A resource may only define names the inventory lists** (`build()` fails loudly). No private
  helpers: `flet`/`labels`, or a `%`-prefixed PRELUDE entry -- also where a definition needing
  global state must put it. `(defun (setf NAME) ...)` counts as a definition of NAME.
- Anything listed and undefined gets a stub from its kind. Caveats: a `variable` stub is `nil`, not
  upstream's default; a `constant` gets a `defvar`; a `condition`/`class` stub is flat.
- **A Java-only member has no VALUE the compile paths can materialize**: that is why
  `symbol-call` and the nickname pair are Lisp over internal primitives, with a literal top-level
  call consumed at RESOLVE time as well (`.kb/packages.md`, "Package-local nicknames").
- **Resources are read with the TARGET backend's `Features`** (`UiopLibrary.process(program,
  features)`; one-argument overloads mean `Features.INTERPRETER`); everything per-backend in
  `uiop/os` derives from `featurep` (`.kb/reader-features.md`). **A test harness compiling a uiop
  program must pass the same set** (`Features.WASM` in `WasmLispCompilerIntegrationTest`).
- **A `macro` stub is a `defun`** so the name is `fboundp`, but the CALL FORM never reaches it:
  `LispMacroExpander.expandUnimplementedUiopMacro` lowers it with argument forms DROPPED. **TRAP:
  on the compile paths it must be applied in the expression compiler's uiop branch, AHEAD of the
  ordinary call path** -- else the call path finds the stub in `ctx.functions` and compiles the
  arguments (`(uiop:with-current-directory ("/tmp") ...)` compiled `("/tmp")` as a CALL; invisible
  with an EMPTY spec list). Pinned by `compileAndRunUiopUnimplementedMacroDropsItsArgumentForms` /
  `uiopUnimplementedMacroDropsItsArgumentFormsCompilesAndRuns` + ci-spec, with a non-empty spec.

## The macros: ONE dispatcher
A uiop macro cannot be a `defmacro` in a resource -- `UserMacroExpander` runs BEFORE the uiop
splice. Every uiop macro with a real expansion is Java in `LispMacroExpander`
(`UIOP_MACRO_EXPANSIONS`); `expandUiopMacro` is the single dispatcher, called from the evaluator,
both expression compilers and BOTH `FreeVarAnalyzer` walks (several BIND or REARRANGE forms the
default walk reads as calls).

- `os-cond` -> a plain `cond` (upstream evaluates clause TESTS at macroexpansion time).
- `with-deprecation` INSTRUMENTS each wrapped `defun` with a one-time notification evaluating the
  `(level)` form at RUN time (no expansion-time evaluator exists here). `flattenTopLevel` EXPANDS the
  wrapper before splicing so the instrumentation reaches compiled top-level defuns. A typed warning
  is NOT routed to handlers on the compile paths (not uiop-specific), so the pinned tests catch a
  `:delete` level. `with-upgradability` splices verbatim (its expansion is `progn`).

## Per-sub-package verdicts
`uiop/version` -- `*uiop-version*` is the CONTRACT version `"3.3.7"` (a version-gating library gets
the answer matching the API it finds), not a claim of completeness. `parse-version` keeps
upstream's `&optional on-error` shape: it CALLS the handler rather than signalling, so `version<` on
garbage stays non-signalling.

`uiop/backward-driver` -- `coerce-pathname` and `version-compatible-p`; the other five are homed in
`uiop/configuration` by the inventory.

`uiop/configuration` -- upstream's bodies, which the source registry stands on
([asdf.md](asdf.md), "The search"). `get-folder-path`, `uiop-directory` and `*user-cache*`'s
computation stay stubs. `:here` without `*here-directory*` is the directory of
`*load-truename*`, since `*load-pathname*` is a spelling here and upstream wants it absolute.
`report-invalid-form` calls `error` directly: a funcalled `#'error` is a raw trap on wasm.

`uiop/utility` -- one character type (`+non-base-chars-exist-p+` NIL, `base-string-p` always t);
`register-hook-function` is upstream's body over the `symbol-value` place; `uiop-debug` /
`load-uiop-debug-utility` signal (need a run-time `load` of a computed pathname);
`match-condition-p`'s STRING pattern compares against the ALREADY FORMATTED message; `coerce-class`
drops upstream's `*package*` fallback; `ensure-function`'s `:package` ignored; `timestamps<` chains
from `nil` = +infinity (upstream's answer, pinned so nobody "fixes" it); `frob-substrings` returns a
FRESH string (upstream's `return-from` out of `labels` would put every user into EH mode on WASM).

`uiop/os` -- **every host answer derives from ONE source, `featurep`**. `architecture` = `:wasm32` /
`:jvm` (the ABI the ARTIFACT targets). **`os-unix-p` is `t` outright, NOT `(featurep :unix)`** --
adding `:unix` to `Features` would flip the `#+unix` branch of every library read
(`.kb/pathnames.md`).
- **Environment READ from the host, WRITTEN to an override map** consulted BEFORE the host, over
  `rontolisp::%host-getenv` (`LispNames.HOST_GETENV`; under `--component` a wit-imported
  `wasi:cli/environment`) -- **its trigger is the PRIMITIVE, so it must run AFTER the uiop splice**.
  The store is two PRELUDE entries; a nil value is an UNSET, which is why the reader answers the
  whole ENTRY, not its cdr. A program whose FIRST touch is the `(setf getenv)` write needs
  `LispEvaluator.ensureUiopSetfPlaceLoaded`.
- `hostname` -> nil (trigger: a `machine-instance` built-in). `getcwd` real where the host has a
  working directory (WASM: fd 3's preopen name when absolute, `.kb/pathnames.md`), and ONE shared
  Lisp definition turns a nil into the error -- the divergence is a VALUE, not a second code path.
  `chdir` signals everywhere.
- The shortcut family is real (the parsers seek a binary file stream with `file-position`). The
  upstream `*link-initial-dword*` / `*link-guid*` constants are inlined (not in the inventory, so a
  resource may not define them); the `.lnk` fixture is built at run time with `write-byte` for the
  WASM test (`WasmLispCompilerIntegrationTest#uiopOsHostIdentityAndGetenvOverrideCompileAndRun`).

`uiop/pathname` -- over the flat-namestring model of `.kb/pathnames.md`. Logical pathnames follow
the CL half (`logical-pathname-p` nil, `make-pathname-logical` signals). **`ensure-pathname` signals
DIRECTLY on the default error path** -- a funcalled `#'error` wrapper is a raw TRAP on WASM where a
direct call is catchable; `:want-logical` always fails. `ensure-absolute-pathname` keeps its
documented divergence. The `MACRO_EXPANSION_CALLEES` table also carries a VARIABLE
(`uiop:*nil-pathname*`). `split-name-type` and `split-unix-namestring-directory-components` return
MULTIPLE VALUES (`.kb/multiple-values.md`). `cli/CompileTimePathnameFolder` folds `subpathname`
over literals, mirroring the Lisp definition exactly -- including declining an absolute STRING
subpath.

`uiop/image` -- **`quit` is the host's exit on all four backends through ONE definition** over
`%host-exit` (`LispNames.HOST_EXIT`): finishes the output streams first and masks the code to eight
bits (`(uiop:quit 300)` is 44 everywhere).
- Interpreter: `eval.LispExitSignal` escapes `RontoLispCli.run` and becomes the process code in
  `main` (`run` is embedded; killing the calling JVM is not a call's decision) -- through
  `System.exit` even at status 0, else a live non-daemon thread keeps the process up
  (`cli/QuitEndsTheProcessTest`). JVM: `JvmExitCompiler`. WASM: `exit.lisp` -- Preview 1 binds
  `proc_exit` under the primitive's OWN name, `--component` `wasi:cli/exit@0.3.0` as an appended
  user import; a `--no-wasi`/`--no-gc` reactor is REFUSED by name, a Java program apart (the trap,
  `.kb/java-frontend.md`, "A server as a reactor").
- **`quit` neither unwinds nor is catchable** (`evalUnwindProtect` runs NO cleanup for
  `LispExitSignal`). Backtraces are lite and stay lite. **`*lisp-interaction*` is NIL where upstream
  defaults to T** (trigger: an `invoke-debugger`). `restore-image` is upstream's start (a program
  starts fresh, the restored case); `dump-image` wraps `%dump-image` -- the interpreter's compile of
  its image (executables only), a refusal in a compiled program ([asdf.md](asdf.md), "Building an
  executable", "Inside a running program"); `create-image` signals. `*image-restored-p*` is defined
  because deploy reads it (`UiopLibrary.INTERNALS`).
- The command-line five are ONE definition over `%host-argv` answering `(program-name user-arg
  ...)`; rontolisp is always upstream's executable case because `CliOptions.arguments()` splits at
  `--`. JVM: a static `_argv` stored from main's OWN PROLOGUE (a defun is a static method and cannot
  see main's locals); the program name is the jar the class was loaded from (a program finds its
  data beside it), else the class name. **Preview 1's `args_sizes_get`/`args_get` are APPENDED USER
  IMPORTS**, while the `_argv` helper is a FIXED index. `--component`'s `get-arguments` DID need the
  fixed import block (pruned per INTERFACE, `WitOracleE2eTest`). A `--no-wasi` reactor answers nil.
  The other front ends read the same vector (`.kb/java-frontend.md`, "Where a program starts").

`uiop/filesystem` -- the read side is Lisp over `probe-file` / `directory`: a designator
`ensure-pathname` rejects answers nil like a missing file; `directory*` passes `:resolve-symlinks
nil` unless the caller passes its own, so a link keeps its name in every uiop walk.
`parse-native-namestring` IS Unix (`os-unix-p` is t).
- The write side is real Lisp over the primitive each CL operator already bottoms out in, on all
  four backends (ci-spec `filesystem-write-create-rename-delete-and-probe`,
  `WasmLispCompilerIntegrationTest#uiopFilesystemProbeReadsAndMutations`). `delete-empty-directory`
  uses the injected `path_remove_directory` on WASM (`.kb/read-load-streams.md`).
  `delete-directory-tree` takes the portable recursive walk only (`run-program` is not on all four).
- **Symlinks resolve on all four**: `truenamize` is `%real-path`, the walk behind `truename`
  (`.kb/pathnames.md`), so `*resolve-symlinks*` defaults to t as upstream's does. `file-exists-p` /
  `directory-exists-p` split `probe-file`'s answer by directory form (`file-exists-p` of a directory
  is nil, so no compile-path fold onto `probe-file`).
- `lisp-implementation-directory` is nil (no install directory exists).
- **`with-current-directory` inherits `chdir`'s decision and invents no second one**: it runs
  `chdir` BEFORE `getcwd` so the signal names `CHDIR` on all four (`getcwd` has no answer under
  `wasmtime run --dir .`); a nil dir just runs the thunk.

`uiop/stream` -- the `call-with-*` openers use the computed-option lowering behind
`with-open-file` (upstream's own shape), so a function taking the options as arguments dispatches
onto literal opens at run time.
- `with-input` / `with-output`'s functions are NOT exported upstream, so the designator table lives
  in the `%call-with-input` / `%call-with-output` PRELUDE entries (a resource may only define
  inventory names), with `MACRO_EXPANSION_CALLEES` rows and pruner roots beside `%temp-file-name`'s
  -- without the roots the tree-shaker drops what the splice added and only the pruner-free
  harnesses pass.
- Lite, one portable shape each: `:element-type` `'character`, `:external-format` / every encoding
  `:utf-8` (`detect-encoding` reads nothing); `:if-exists` defaults to `:supersede` (upstream's
  `:error` / `:rename-and-delete` have no surface; the lowering refuses `:error` loudly).
  `safe-read-from-string` reads through `with-input-from-string` + `read` (compile-path
  `read-from-string` takes one argument). `*read-eval*` nil is honored on the interpreter while the
  compiled readers refuse `#.` unconditionally (stricter, therefore safe).
- **The slurp family and `copy-stream-to-stream` never close** (upstream does): `close` signals on
  an already-closed stream here, so the owner closes exactly once and a caller-owned stream stays
  open.
- **`call-with-temporary-file` is the real function and `with-temporary-file` upstream's wrapper**.
  `%temp-file-name` is the ONE uniqueness mechanism; it references `ensure-directory-pathname` /
  `default-temporary-directory` in its body so the fixpoint pulls them. It uses `unwind-protect`,
  forcing EH mode on WASM -- accepted. The macro's `:direction` may be a QUOTED keyword (coalton
  writes `:direction ':output`). `:suffix` default `-tmp` once a `:type` is given; an absolute prefix
  ignores the directory (cffi-toolchain names temporaries beside their file).
- The null device is `#P"/dev/null"`; null input = `with-input-from-string ""`, null output =
  `make-broadcast-stream`. `*stdin*`/`*stdout*`/`*stderr*` are the current standard streams.

`uiop/package`, `uiop/package-local-nicknames`, `uiop/package*` -- a rontolisp symbol is a string,
so the family splits: the name-level questions are real; the surgery that moves a symbol between
packages (`rehome-symbol`, the nukes, reify/unreify, `unintern*`, `shadow*`,
`shadowing-import*`, `ensure-package-unused`, `delete-package*`, `ensure-package`) signals with the
"no image to upgrade" reason -- real definitions, so the message says why. That reason is the
re-evaluation trigger: symbol identity (the deferred intern table) would unblock it.
- `intern*` of a missing package with no error answers nil; `export*`/`import*` answer what CL's own
  runtime export/import answer on the compiled backends; `symbol-shadowing-p` nil (runtime
  shadowing is a non-goal); `parse-define-package-form` is pinned against uiop 3.3.7's own
  `:uiop/package*` header and always accepts `:local-nicknames` (the machinery is always present).
- `standard-common-lisp-symbol-p` is `(eq (symbol-package s) :cl)` (`.kb/packages.md`).
  `package-definition-form` covers the DECLARED universe only (a defun-defined name is not in the
  registry), and skips an inherited member of a used package even when genuinely imported.
- **A resolve-time directive is program-global on the compiled backends**, so the baked listing
  tables answer the END state: a program that lists nicknames and then removes one sees the
  mid-program state on the interpreter and the end state compiled. The two are never mixed in one
  pinned program (ci-spec `uiop-package-surgery` adds without removing).
- `typep`/`typecase` of a quoted uiop TYPE name run `ensureUiopTypesFor` first (the loader triggers
  on function/variable resolution only).

`uiop/lisp-build` -- **the compiled-file TYPE answers nil**: there is no `compile-file`, so "is this
path a fasl?" (rove's resolve-file) answers no for a source path. The portable half is real (the
condition classes, the muffled-conditions family, `load*`, `load-from-string`, the reify pair,
`check-lisp-compile-*`). `load*` of a stream goes through `uiop/stream:eval-input` (rontolisp's
`load` cannot load from a string-input-stream). The reify pair passes a proper list's terminating
NIL through as nil, because `reify-symbol` signals. `compile-file-pathname*` answers the explicit
`output-file` merged against the input, or nil. `compile-file*` and the deferred-warnings machinery
signal (no `compile-file`, no fasl, no compilation-unit protocol); the three defensive checks
(`enable-`/`disable-deferred-warnings-check`, `reset-deferred-warnings`) are no-ops, so a library
calling them defensively does not fail. Re-evaluation trigger: a real `cl:compile-file` makes
`compile-file*` the first thing to stop signalling.

### uiop/launch-program and uiop/run-program
- **The escape family is real on all four** (`uiop-launch-program.lisp`, upstream's bodies with its
  private helpers as local functions; SBCL 2.2.9's answers, ci-spec `uiop-escape-command-lines`).
  `launch-program`, the `process-info` family and the process controls stay stubs: no backend hands
  a running child back (`.todo/363`).
- **`run-program` is real on the JVM family** over one primitive, `rontolisp::%run-program` ->
  `runtime/RontoProcess.spawn` (the JVM lowers to `RontoProcess.run`, which travels with the class;
  the web build substitutes a refusal). Arguments travel NUL-joined (the one character no C `argv`
  entry holds). Every stream spec other than a file/nil -- `t`, a stream, `:string`, `:lines`, a
  function -- is upstream's route: vomited into a temporary file before the child starts, slurped
  after it exits. A string command runs as `/bin/sh -c`. Nonzero exit -> `cerror` of
  `subprocess-error` (process slot nil: no process object outlives the call). Standard output and
  error are finished first, so what this process wrote precedes the child's. `vomit-output-stream`
  reads a pathname INTO the stream (upstream's method writes the stream to the file). WASM:
  `#+rontolisp-wasm` defines `run-program` as the signal, so no `%run-program` reference reaches a
  module. Pinned by `testsupport/RunProgramPrograms` (interpreter and JVM, SBCL's answers) and
  `WasmLispCompilerIntegrationTest#uiopRunProgramSignalsWhereNoProgramStarts`.
- **Why the JVM family and not uniform**: the cffi groveller is plain Lisp over `run-program`
  (`.kb/cffi.md`), so the interpreter -- the build image included -- needs it; a compiled class got
  it too, so the divergence is WASM's alone, the `ffi:` line.

## Selection, not pruning
`UiopLibrary.process` prepends only the definitions the program reaches, to a fixpoint on a
`PackageResolver.resolveProgram` copy. **`MACRO_EXPANSION_CALLEES` is the surface-form rule**
(mirroring `LispPreludeLibrary.referencedBySurfaceForm`): a uiop macro's expansion runs inside the
expression compilers, long after this pass, so without an entry the compiled program says `The
function UIOP/UTILITY:X is undefined` at run time while the interpreter works. Only the DIRECT callee
is listed; the fixpoint pulls the rest and applies the same rule to each selected definition's own
body. A prelude helper a definition calls must find its uiop callees named by that definition
(`run-program` names the temporary directory it hands `%temp-file-name`).

**A library form names another uiop definition HOME-QUALIFIED.** The fixpoint matches spelled symbol
names against the inventory's qualified names, so a bare spelling selects nothing (a subclass naming
its parent `compile-condition` bare failed `DEFCLASS: unknown superclass` on the compile paths while
the interpreter, which loads every uiop class up front, worked; ci-spec
`uiop-sub-packages-and-not-implemented-error`).

**uiop is NOT in `LibraryDefunPruner`'s prunable set.** **`LispPreludeLibrary.process` CALLS
`UiopLibrary.process` first**: the two are mutually dependent, so one pass with a fixed order.
Re-running is a no-op (`UiopLibraryTest.aSecondRunSplicesNothingMore`).

Interpreter lazy-loads on first function/variable resolution. **A CLASS cannot be lazy the way a
function can**: the whole `UiopLibrary.closureOf(name)` goes in (a quoted condition name is not a
function resolution), and every uiop condition and class on the first touch of the condition system
(`ensureUiopConditionClassesLoaded`), because a handler's type test is built from the class tags
known when the `handler-bind` was EXPANDED. Residual, NOT uiop-specific: a condition class first
registered inside an already-entered `handler-bind` body still misses.

## Documentation shape
`doc/{en,ja}/reference/uiop.md`: the sub-package model, a coverage table, the implemented-member
table, what an unimplemented member signals. **A sub-package that fills up moves to
`reference/uiop/<sub-package>.md`**: a page, a `subpages:` entry under `reference/uiop.md` in every
language tree's `nav.yaml` (NOT sidebar rows, `.kb/documentation-site.md`), the parent keeping the
coverage row and one sentence. **When a member MOVES its row leaves the parent's table.**

## Deliberate extras (`uiop` owns them; the inventory does not list them)
`uiop:namestring` (upstream only inherits CL's, so the spelling would not read); `uiop:when-let` /
`uiop:when-let*` (alexandria's names -- real uiop exports `if-let` only), kept because programs
already spell them.

## Tests
- `UiopCoverageTest` -- the gate: every listed symbol external in `uiop` AND its sub-package and
  defined for its kind; hard-coded `LispNames` spellings; an unimplemented member signals naming the
  operation; an unimplemented MACRO does not evaluate its forms; an implemented macro is never ALSO
  stubbed; printed per-sub-package coverage.
- `UiopLibraryTest` -- selection: both spellings, the fixpoint, a stub dragging in the condition it
  signals, the `MACRO_EXPANSION_CALLEES` rules, idempotence.
- Behaviour: the `evalUiop*` block of `LispEvaluatorTest`; `JvmLispCompilerTest.compileAndRunUiop*`
  (incl. `compileAndRunUiopImageTheCommandLine` and the quitting cases, in a CHILD JVM); the
  `uiop*CompileAndRun` / `uiopWithUpgradability*` cases of `WasmLispCompilerIntegrationTest`;
  `CliOptionsTest.everythingAfterTheSeparatorBelongsToTheProgram`; ci-spec `uiop-utility-helpers`,
  `uiop-os-host-identity`, `uiop-pathname-algebra`, `uiop-image-command-line` (the only test that
  argument PASSING agrees across the four launchers, `CiSpecE2eTest.PROGRAM_ARGUMENTS`),
  `uiop-image-hooks-and-backtraces` (MINUS the exit half -- the driver concatenates cases into one
  program and a `quit` would end the run).
