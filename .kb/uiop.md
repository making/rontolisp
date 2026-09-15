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
- `with-deprecation` INSTRUMENTS: each wrapped `defun` gains a guarded one-time
  notification that evaluates the `(level)` form at run time (no expansion-time evaluator
  exists here) and signals the class the level selects — `deprecated-function-style-warning`
  (`style-warn`) / `deprecated-function-warning` (`warn`) / `deprecated-function-error`
  (`cerror`) / `deprecated-function-should-be-deleted` (`error`) — beside a per-function
  `%dep-notified-*` flag, upstream's notify-once. `flattenTopLevel` EXPANDS the wrapper
  before splicing (`isUiopWithDeprecationWrapper` -> `expandUiopWithDeprecation` then
  flatten), so the instrumentation reaches the compiled top-level defuns; the interpreter
  expands it via the same method. A `:warning`/`:style-warning` level emits the typed
  warning identically on all four backends, but a typed warning is NOT routed to
  handlers on the compile paths (pre-existing, not uiop-specific), so the pinned tests
  catch a `:delete` level (an error, catchable everywhere). `with-upgradability` still
  splices verbatim (`isUiopWithUpgradabilityWrapper`): its expansion is just `progn`.

## Per-sub-package verdicts
`uiop/version` (15/15, `uiop-version.lisp`) — the whole sub-package. `*uiop-version*` is the
CONTRACT version `"3.3.7"` (a version-gating library gets the answer matching the API it finds),
not a claim of completeness. `parse-version` keeps upstream's `&optional on-error` shape: it CALLS
the handler with a format string for a malformed version rather than signalling, so `version<` on
garbage stays non-signalling. `version<`/`version<=` are written over `lexicographic<` /
`lexicographic<=` (`.todo/354`); the five deprecation condition classes are real
(`deprecated-function-name` is a defun reading the `name` slot via `slot-value`), and
`version-deprecation` maps a version pair to `:style-warning` / `:warning` / `:error` / `:delete`
with each start defaulting to the `next-version` of the lower level.

`uiop/backward-driver` (2/7, `uiop-backward-driver.lisp`) — `coerce-pathname` is the one-line
DEPRECATED alias of `parse-unix-namestring` (`.todo/357`), and `version-compatible-p` is the ASDF
1-to-2.32 check over `parse-version` + `lexicographic<=`. The other five (the configuration
-directory search) stay `not-implemented-error` stubs: they need `uiop/configuration`, which
nothing implements yet.

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

`uiop/filesystem` (32/32, `uiop-filesystem.lisp` except the one macro) — the read side is
Lisp over the two primitives every backend already carries: `probe-file*`/`truename*` over
`probe-file` (a designator `ensure-pathname` rejects answers nil through `ignore-errors`,
exactly like a file that is not there), `directory*` over `directory` (upstream's
per-implementation symlink keys accepted and dropped), `filter-logical-directory-results`
the passthrough it is where `logical-pathname-p` is nil, `safe-file-write-date` over
`file-write-date` with the missing-file `file-error` swallowed (nil on both WASM backends,
where the date itself is nil). `parse-native-namestring` is `parse-unix-namestring` plus
the `ensure-pathname` constraints (`os-unix-p` is t outright, so native IS Unix and the
separator `#\:`); the `getenv-*` family reads `uiop/os:getenvp` through it. **The write
side is option 2 of `.todo/358`, landed by `.todo/257`**:
`ensure-all-directories-exist`, `rename-file-overwriting-target` and
`delete-file-if-exists` are real Lisp over the one primitive each matching CL operator
already bottoms out in (`%make-directories` via `ensure-directories-exist`, `%rename-file`
via `rename-file`, `%delete-file` via `delete-file`), real on all four backends -- the
ci-spec `filesystem-write-create-rename-delete-and-probe` case and
`WasmLispCompilerIntegrationTest#uiopFilesystemProbeReadsAndMutations` pin both WASM
legs. **One deliberate remainder: removing a DIRECTORY still signals on WASM** --
preview1's `path_unlink_file` cannot remove directories (that needs the
`path_remove_directory` import, out of `.todo/257`'s scope), so
`delete-empty-directory` over an actual directory, and `delete-directory-tree` past its
file deletions, answer the honest `file-error` rather than a silent no-op.
`delete-directory-tree` takes the portable recursive walk only (no `run-program`
branch -- there is no backend that spawns one); an explicit `:validate nil` fails the
first check rather than the second, both the same `parameter-error`. **Symlinks are the
identity, and that is coverage, not a stub**: no backend resolves them (`truename`
carries the argument namestring), so `*resolve-symlinks*` defaults to nil (upstream's t
would promise what is not there) and `resolve-symlinks`/`resolve-symlinks*`/`truenamize`
coerce-and-return. `lisp-implementation-directory` is nil (no install directory exists
to name) and `lisp-implementation-pathname-p` follows it. **`with-current-directory`
inherits `chdir`'s decision and invents no second one**: `call-with-current-directory`
binds `*default-pathname-defaults*` and `chdir`s (running `chdir` BEFORE `getcwd` so the
signal names `CHDIR` on all four -- `getcwd` has no WASM answer), a nil dir just runs
the thunk; the macro is a `LispMacroExpander` expansion over it (every uiop macro is),
with the `MACRO_EXPANSION_CALLEES` row its expansion needs.

`uiop/stream` (66/66, `uiop-stream.lisp` + eight `LispMacroExpander`
expansions, `.todo/359` + `.todo/360`) -- the "give me the contents" half: the `call-with-*`
openers over the computed-option lowering behind `with-open-file` (upstream's
own shape, so a function taking the options as arguments dispatches onto
literal opens at run time), the `with-input` / `with-output` /
`input-string` / `output-string` designator table in one helper each,
`copy-stream-to-stream` / `concatenate-files` / `copy-file`,
`slurp-stream-*` / `read-file-*`, `with-safe-io-syntax` /
`call-with-safe-io-syntax` / `safe-read-from-string`, `eval-input` /
`eval-thunk` / `standard-eval-thunk`, `file-stream-p` /
`file-or-synonym-stream-p`, `finish-outputs` / `println` / `writeln` /
`format!` / `safe-format!`. The five `with-*` are Java expansions over their
functions -- except `with-input` / `with-output`, whose functions upstream
defines but does NOT export: the table lives in the `%call-with-input` /
`%call-with-output` prelude entries (a resource may only define inventory
names), called by the expansions and by the exported designator readers, with
the prelude surface-form rules beside `%temp-file-name`'s, the pathname
arms' uiop rows (plus `eval-input` / `input-string` / `output-string`'s, the
same edge through the front door) in `MACRO_EXPANSION_CALLEES`, and the two
pruner roots beside `%temp-file-name`'s -- without the last the tree-shaker
drops the entries the splice just added and only the pruner-free harnesses
pass. Lite, one portable shape each: `:element-type` defaults to `'character` and `:external-format`
to `:utf-8` (part 2's `*default-stream-element-type*` / `*default-encoding*` / `*utf-8-external-format*`
are those values, real); `:if-exists` defaults to `:supersede` and `concatenate-files`
spells it (upstream's `:error` / `:rename-and-delete` have no surface -- the
lowering refuses `:error` loudly); `slurp-stream-forms` reads with `read`
(no `read-preserving-whitespace` exists); `safe-read-from-string` reads
through `with-input-from-string` + `read` (compile-path `read-from-string`
takes one argument) and answers the object only; `call-with-output` over a
string signals (`with-output-to-string` is fresh-string only); `:linewise`
copy always ends lines with `terpri` (`read-line` answers one value);
`*read-eval*` nil is honored on the interpreter while the compiled runtime
readers refuse `#.` unconditionally (stricter, therefore safe).
`safe-format!` never signals. `read-file-string` keeps its pinned chunked
shape and is not redefined over the new openers. The slurp family and
`copy-stream-to-stream` never close (upstream does): `close` signals on an
already-closed stream here, so the upstream close-inside-plus-close-in-
`with-open-file` composition would signal -- the owner (the file opener, the
string-stream macro) closes exactly once, and a caller-owned stream handed
directly to a slurper stays open. Part 2 (`.todo/360`) is the temporary-file /
staging / null-stream / encodings / standard-streams half. **`call-with-temporary-file`
is the real function and `with-temporary-file` is upstream's wrapper over it**
(a function taking the options as arguments dispatches onto literal opens at run
time, the same computed-option shape as part 1's openers). `%temp-file-name` is the ONE
uniqueness mechanism, unchanged: the directory is resolved by the function itself, which
references `ensure-directory-pathname` / `default-temporary-directory` directly in its body
so the uiop fixpoint pulls them (the selection rule keyed on `with-temporary-file` is
redundant-but-harmless). `:keep t` hands the pathname back with the file still there; the
default deletes on the way out. It uses `unwind-protect` internally, which forces EH mode on
WASM -- accepted, because no test exercises the non-EH-mode `with-temporary-file`.
`tmpize-pathname` builds a uniquely-named sibling and returns a pathname (a `pathname` wrap
around the namestring); `with-staging-pathname` writes through the staging pathname and
renames onto the target only on success (`rename-file-overwriting-target`). The null device
is `#P"/dev/null"`; `call-with-null-input` runs the thunk over `with-input-from-string` of
`""` (EOF-always), `call-with-null-output` over `make-broadcast-stream` (a discarding sink)
-- each returns the thunk's own result. `with-null-input` / `with-null-output` /
`with-staging-pathname` are Java expansions over their `call-with-*`. The encodings are one
lite decision: `:utf-8` everywhere, `*default-encoding*` `:utf-8`, `*utf-8-external-format*`
`:utf-8`, the two hooks the identity functions upstream installs, `detect-encoding` answers
`:utf-8` without reading the file's contents. The standard streams are the raw underlying
streams, distinct from `*standard-output*`: `*stdin*` = `*standard-input*`, `*stdout*` =
`*standard-output*`, `*stderr*` = `*error-output*`, and `setup-*` re-derive them from the
current standard streams.

`uiop/package` (31/31), `uiop/package-local-nicknames` (3/3), `uiop/package*`
(3/3) -- `uiop-package.lisp` plus two Java built-ins (`.todo/361`). A rontolisp
symbol is a string, so the family splits: the name-level questions are real,
the surgery that moves a symbol between packages signals
`not-implemented-error` naming the operation with the "no image to upgrade"
reason (upstream needs it to hot-upgrade ASDF inside a running image, and
there is no image here) -- real definitions, not synthesized stubs, so the
message says why instead of only which name. That reason is also the
re-evaluation trigger: this sub-package is one of the things symbol identity
(the deferred intern table) would unblock.

Real: `find-package*` (now signalling `no-such-package-error`, a `type-error`
whose datum `package-designator` reads), `find-symbol*`, `intern*` (a missing
package with no error answers nil -- upstream interns into `*package*, which
the two-argument form has no default for here), `export*`/`import*` (real where
the registry can mutate: the interpreter runs the CL operator, the compiled
backends answer exactly what CL's own runtime export/import answer -- arguments
for effect plus `t`), `make-symbol*`, `symbol-shadowing-p` (nil, honestly:
runtime shadowing are non-goals), `home-package-p`, `symbol-package-name`,
`standard-common-lisp-symbol-p`, `package-names`, `packages-from-names`,
`fresh-package-name`, `rename-package-away` (a fresh name plus
`rename-package`, working wherever it works), `package-definition-form`,
`parse-define-package-form` (a pure port, pinned against uiop 3.3.7's own
`:uiop/package*` header), the `package-designator` type plus its reader, both
conditions, the nickname query (Lisp over `package-nicknames`), and
`remove-package-local-nickname` (an interpreter runtime with a literal
top-level call consumed at resolve time like the add, so it works on every
backend). `define-package` was already a resolver-level macro;
`add-package-local-nickname` was already Java.

Three places the string model shows through, all pinned on all four backends.
`standard-common-lisp-symbol-p` walks the cl externals comparing member names:
a compiled `find-symbol` builds the spelling, so its status cannot
discriminate, and a shadowed-in standard name still reads as standard.
`package-definition-form` buckets the `do-symbols` enumeration by spelling
(owned `PKG:`/`PKG::` into `:export`/`:intern`, anything else into
`:import-from` under its true home) and skips whatever the use list already
provides; it covers the DECLARED universe (a defun-defined name is not in the
registry, so only declared members round-trip), and an inherited member of a
merely-used package is skipped even when genuinely imported (the `:use`
clause still provides it). The `:local-nicknames` clause of
`parse-define-package-form` is always accepted: the lite-global machinery is
always present, so upstream's feature gate could only ever refuse. Signals:
`rehome-symbol`, both nukes, all four reify/unreify members, `unintern*`,
`shadow*`, `shadowing-import*`, `ensure-package-unused`, `delete-package*`
and `ensure-package` (whose compile-time job the `define-package` macro
already does through the resolver).

Two interpreter/compiling gaps this item closed, both outside uiop proper.
`FreeVarAnalyzer` did not know `do-symbols`/`do-external-symbols` bind their
variable (only `dolist` et al. were expanded before the walk), so any closure
around the walk failed to compile with `Cannot capture variable`: both walks
now expand them like `dolist`. And a quoted uiop TYPE name never lazy-loaded
(the loader triggers on function/variable resolution, and the compile paths
splice from the quoted occurrence): `typep`/`typecase`/`etypecase`/`ctypecase`
now run `ensureUiopTypesFor` first, which loads every library definition the
form names. Residual rule the remove consumption exposed: a resolve-time
directive is program-global on the compiled backends, so the baked listing
tables answer the END state -- a program that lists nicknames and then removes
one sees the mid-program state on the interpreter and the end state compiled;
the two are never mixed in one pinned program (ci-spec `uiop-package-surgery`
adds without removing; the remove leg lives in the backend tests with its
listings after it).

`uiop/lisp-build` (44/44, `uiop-lisp-build.lisp` plus two Java macro expansions) --
the last of the twelve, and the one whose subject rontolisp mostly does not have.
**The compiled-file TYPE answers nil**: there is no `compile-file` here, so there is no
compiled-file type, and a caller asking "is this path a fasl?" (rove's resolve-file) gets
`no` for a source path. The portable half is real -- the six condition classes
(`compile-condition` and the five warning/error subclasses, real `define-condition`s a
handler must find), the muffled-compiler/loader-conditions family (`call-with-` over
`uiop/utility:call-with-muffled-conditions` of the uninteresting lists; the two `with-`
macros are Java expansions in `LispMacroExpander`, in `UIOP_MACRO_EXPANSIONS`, with the
two new `MACRO_EXPANSION_CALLEES` rows), `load*` (the muffled loader around `cl:load`
for a pathname/string and `uiop/stream:eval-input` for a stream -- rontolisp's `load`
cannot load from a string-input-stream, so the stream arm is upstream's for the
implementations that cannot either), `load-from-string` (`load*` over
`with-input-from-string`), `reify-simple-sexp`/`unreify-simple-sexp` (a pure sexp <->
portable representation; the terminating NIL of a proper list passes through as nil,
because `reify-symbol` signals -- the reify/unreify pair is part of the image-upgrade
surgery), `check-lisp-compile-warnings`/`check-lisp-compile-results` (portable over the
two behaviour flags and the real classes), `call-around-hook`, `lispize-pathname`,
`compile-file-pathname*` (no compiled-file TYPE to derive, so the honest answer is the
explicit `output-file` merged against the input, or nil), `warnings-file-type` (answers
nil for the one implementation here -- the case has no `:rontolisp` clause) /
`warnings-file-p` / `*warnings-file-type*`, and the variables (`*base-build-directory*`,
`*compile-check*`, the behaviour flags at upstream's `:warn` defaults, the
uninteresting lists seeded empty -- no implementation-specific condition names to skip).
`compile-file*` and the deferred-warnings machinery (`save-deferred-warnings`,
`reify-deferred-warnings`, `unreify-deferred-warnings`, `check-deferred-warnings`,
`with-saved-deferred-warnings` (a defun stub a call form lowers past, dropping its
body), `combine-fasls`) resolve and signal `not-implemented-error` naming the operation:
rontolisp has no `compile-file`, no fasl and no compilation-unit protocol. The three
defensive checks -- `enable-deferred-warnings-check` / `disable-deferred-warnings-check`
/ `reset-deferred-warnings` -- are no-ops rather than errors, so a library calling them
defensively does not turn a no-op into a failure. Re-evaluation trigger: if rontolisp
ever grows a real `cl:compile-file`, `compile-file*` is the first thing that should stop
signalling.

## Selection, not pruning
`UiopLibrary.process` prepends only the definitions the program reaches, to a fixpoint on a
`PackageResolver.resolveProgram` copy. **`MACRO_EXPANSION_CALLEES` is the surface-form rule**
(mirroring `LispPreludeLibrary.referencedBySurfaceForm`): a uiop macro's expansion runs inside the
expression compilers, long after this pass, so without an entry the compiled program says
`The function UIOP/UTILITY:X is undefined` at run time while the interpreter works. Only the DIRECT
callee is listed; the fixpoint pulls the rest.

| surface macro | direct callee(s) |
|---|---|
| `with-temporary-file` | `call-with-temporary-file` (which references `ensure-directory-pathname`, `default-temporary-directory` and `%temp-file-name`; the fixpoint pulls them) |
| `with-null-input` | `call-with-null-input` |
| `with-null-output` | `call-with-null-output` |
| `with-staging-pathname` | `call-with-staging-pathname` |
| `with-muffled-conditions` | `call-with-muffled-conditions` |
| `with-muffled-compiler-conditions` | `call-with-muffled-compiler-conditions` |
| `with-muffled-loader-conditions` | `call-with-muffled-loader-conditions` |
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
