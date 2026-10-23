# Time / environment built-ins (`get-universal-time`, `get-internal-real-time`, `get-internal-run-time`, the universal-time codec and the local time zone, `uiop:getenv`, and the environment-enquiry family)

## The three time built-ins
- Return an **integer on every backend**. In `LispNames` / `PackageRegistry.CL_FUNCTIONS`.
- Interpreter/JVM: `JvmTimeCompiler` via a `systemOps` methodref map on `Ctx`. WASM
  (`WasmTimeCompiler`): WASI `clock_time_get`; under `--no-wasi` all three read ONE cell a
  host writes through `__ronto_set_time` and signal while unset
  (`.kb/wasm-export-no-wasi.md`). i64 normalized through `_int_new` (`.kb/wasm-bignum.md`).
- `get-universal-time` = Unix seconds + 2208988800; `internal-time-units-per-second` is a
  reader constant **1000** beside `char-code-limit`.
- WASM clock/environ *imports* exist in both modes to keep import indices identical; the
  component's `environ_*` imports are DEAD WEIGHT kept because the fifteen preview1 slots
  (`IMPORT_FUNC_COUNT`) are index-pinned. **Pinned does not mean closed**: a WASI function
  the backend did not import before is added as an APPENDED import, not a sixteenth slot,
  and then costs nothing on a program that does not reach it (`.kb/wasm-import.md`, "This,
  not a new index-pinned preview1 slot" -- `args_sizes_get`/`args_get` and `fd_seek`).

## `uiop:getenv` is a LISP definition over a per-backend primitive
- Public name = `uiop-os.lisp`'s defun: consults the `(setf (uiop:getenv x) v)` OVERRIDE map
  (prelude `%getenv-override` / `-set` over `%getenv-overrides`), falls back to
  `rontolisp::%host-getenv` (`LispNames.HOST_GETENV`), the name every backend lowers. A write
  must be an overlay because no host lets a process rewrite its own environment
  (`.kb/uiop.md`).
- **Spelled `uiop:getenv` and ONLY that** — there is deliberately no `cl:getenv`.
  `LispNames.GETENV` / `LispNames.UIOP_GETENV` (`"UIOP/OS:GETENV"`); `PackageRegistry` exports
  from `uiop/os`, re-exports from `uiop`, keeps it OUT of `CL_FUNCTIONS`.
- Preview 1: `_getenv` (`WasmGetenvRuntimeBuilder`) scans the WASI environ buffer behind a
  FIXED 16 KiB window (`ENV_BUF_ADDR`) — a >16 KiB environment there is a live hazard.
- `--component`: `environment.lisp` over wit-imported `wasi:cli/environment@0.3.0`
  (`eval/EnvironmentLibrary`). `WasmExprCompiler` does NOT dispatch the name in component
  mode, so a skipped splice is a COMPILE error, not a runtime undefined function. The WASI 0.3
  **service** world carries no `wasi:cli/environment`, so `WasmComponentBuilder` binds
  `get-environment` from `FIXED_BLOCK_IFACES` for base/sockets and as an appended
  `appendUserImports` instance for serve; the import is conditional on a `uiop:getenv` call.
- `%host-argv` rides the same seam: `uiop/image`'s command-line family is one
  `uiop-image.lisp` definition over `LispNames.HOST_ARGV`. Preview 1 uses an `_argv` helper
  over `args_sizes_get`/`args_get` as APPENDED user imports (no preview1 slot moves).

## Universal-time codec and the local time zone
`encode-universal-time` / `decode-universal-time` / `get-decoded-time` are `LispPreludeLibrary`
defuns: era-based proleptic Gregorian integer arithmetic, exact at any year, `days*86400 +
h*3600 + m*60 + s + zone*3600` with **25567** as the 1900->1970 day offset.
`get-decoded-time` is in `LispMacroExpander.passesMultipleValues` (its tail is a call; without
the entry its eight extra values died at every caller -- `LispPreludeLibraryTest` only catches
a literal `values`).

**A missing `time-zone` is the LOCAL zone, DST included (since 2026-10-24; GMT before).**
One per-backend primitive, `%local-time-offset` (`LispNames.LOCAL_TIME_OFFSET`, CL_INTERNALS):
Unix seconds in, `offset-east-seconds * 2 + (dst ? 1 : 0)` out. The prelude's `%local-zone`
clamps the argument to +-8.64e12 s (a JS `Date`'s range) so every host can answer.
- decode: `time-zone` returned = STANDARD zone, west-positive hours, rational
  (`(/ (- (if dst 3600 0) east) 3600)`), `daylight-p` = the dst bit -- CLHS's one-hour-DST
  model, SBCL's exact formula (Lord Howe's 30-minute DST reads `T -10` like SBCL). The wall
  clock uses the real total offset.
- encode without a zone: SBCL's two lookups -- the zone at the wall-clock HOUR read as UTC,
  then at the instant that guess names; gap/overlap times land where SBCL's do (checked
  against SBCL 2.6.5 for New York 2026: 02:30 on 03-08 -> 3981940200, 01:30 on 11-01 ->
  4002499800, the EDT reading).
- Interpreter: `eval/LocalTimeZone.offset` (`ZoneId.systemDefault()`). JVM:
  `JvmLocalTimeRuntimeBuilder`'s `_localTimeOffset`, the same three java.time calls emitted into
  the class, gated on the name (no travelling runtime class: `-o X.class` stays one file).
  Browser playground: `src/web/.../Target_LocalTimeZone` -> `BrowserTimeZone` (JS `Date`;
  DST = offset east of min(Jan, Jul)). The native-image binary is the interpreter: GraalVM
  reads `TZ` at run time.
- WASM: `eval/LocalTimeLibrary`, spliced right INSIDE the prelude (keyed on the codec's
  SURFACE names, so the prelude then supplies what it needs; prunable). Three arms:
  Preview 1 / `--component` = `localtime.lisp`, a guest-side reader of `TZ` -> TZif file under
  `TZDIR`/`/usr/share/zoneinfo` (v2+ block + footer POSIX rule) or a POSIX rule string, `TZ`
  unset -> `/etc/localtime`, nothing readable -> UTC; read once per run. `--native` = a
  `rontolisp:wasm-import` of the runner's `rltime.local_offset` (`(i64) -> i64`, the host C
  library's `localtime_r`; `rontolisp-native/runner/src/localtime.rs`). `--no-wasi` = UTC.
- **Why the runner and not the guest reader under `--native`**: the stub preopens `/`, but
  `/etc/localtime` is an ABSOLUTE symlink on macOS (`/var/db/timezone/...`, and
  `/usr/share/zoneinfo` itself is one) and on Debian/Ubuntu; cap-std refuses to follow it and
  `path_readlink` refuses an absolute target (`.kb/read-load-streams.md`). Measured
  2026-10-24: `(open "/etc/localtime" ...)` under `wasmtime run --dir /` answers nil. The
  same wall stops plain `wasmtime run` with `TZ` unset -> UTC; `--env TZ=<name> --dir
  /usr/share/zoneinfo` works (the host resolves the preopen's own symlink).
- Remaining host differences (documented in `guides/clock-and-random.md`): the JVM does not
  parse a POSIX rule `TZ` (it becomes the fixed offset at JVM start); JVM zone data is the
  JDK's tzdb, WASM/native's the system's. Measured 2026-10-24 on macOS against SBCL 2.6.5 over
  UTC, Asia/Tokyo, America/New_York, Australia/Sydney, Asia/Kolkata, Australia/Lord_Howe,
  `EST5EDT,M3.2.0,M11.1.0`, `JST-9`, TZ unset: `--native` identical everywhere; interpreter/JVM
  identical except the POSIX rule; Preview 1/component (with `--env TZ --dir zoneinfo`)
  identical except TZ unset.
- Size: a `.wasm` asking for the local zone carries the reader (+2,258 B on a one-line decode,
  7,638 -> 9,896 B). A program whose every call passes a LITERAL zone (`(decode-universal-time t 0)`,
  HTTP dates) gets the UTC arm instead, so it imports nothing new (no env, no file reads).

## `rontolisp:random-bytes`
Cryptographic sibling of `random` (`Math.random` on interpreter/JVM): a prelude defun over the
per-backend `rontolisp::%random-byte` primitive. Interpreter = process-wide
`java.security.SecureRandom`; JVM = `_randomByte` from `JvmSecureRandomRuntimeBuilder`, gated
on a reference so an entropy-free program never loads `java.security`; WASM = low byte of WASI
`random_get`.

## `sleep`
`LispMacroExpander.expandSleep` does the shared `(round (* n 1000))` conversion and the
non-positive guard. The wait is per backend: interpreter/JVM park via `%SLEEP-MS`
(`JvmSleepCompiler`'s `Thread.sleep(J)`, pool entries minted at the call site); Preview 1
spins on the clock (`expandSleep`'s `spin` arm); `--no-wasi` SIGNALS at call time naming
`sleep`, argument still evaluated (`WasmExprCompiler`, before the spin arm), because its clock
only moves on a host write; `--component` is NOT a lowering but the spliced `wait.lisp` DEFUN
(`eval/WaitForLibrary`, triggered by `rontolisp:wait-for` OR `sleep`) FORCING a
`wasi:clocks/monotonic-clock` timer future through `rontolisp::%future-force`.

The component arm must stay a FORCING defun: `await` is legal only at top level or inside
`async-defun`/`async-lambda`, a lowering would run too late for the async pass and yield a
`#<FUTURE>`, and only a defun makes `#'sleep` work. `#'sleep` is in
`BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS` — ungated it would put `(sleep x)` into
EVERY component. Accepted cost: the host timer puts the module in async (therefore EH) mode.

## Environment-enquiry family (CLHS 25.1.5)
**All nine are CONSTANTS, and only `machine-type` differs between backends.**
`LispPreludeLibrary` defuns, so `#'software-type` is first-class. `lisp-implementation-type` =
`"rontolisp"`; `lisp-implementation-version` = `Version.getVersion()` BAKED into the prelude
source at class-init (equals `(getf (rontolisp:version) :version)`); `software-type` =
`"Unix"`; `machine-type` = `"JVM"` / `"WASM32"` via the one per-backend piece
`%target-machine-type`; `software-version`, `machine-version`, `machine-instance`,
`short-site-name`, `long-site-name` = `nil`.

**The host is NOT consulted anywhere, including interpreter and JVM**:
`System.getProperty("os.name"/"os.arch")` are deliberately unused — a compiled `.class` runs on
a machine the compiler never saw, and a run-time query would break emitted-output determinism.
Everything unknowable answers `nil`, never a fabricated string.

## Tests
Local zone: `LocalTimeLibraryTest` (the guest reader against java.time around every
transition 1972-2045, POSIX rules, the arms), `LocalTimeZoneTest` (interpreter, JVM, Preview 1,
component as child processes under `TZ`, against SBCL's lines),
`NativeOutputE2eTest#theLocalTimeZoneIsTheOneTzNamesAsWasmtimeGivenTheZoneFilesDecodesIt`, the
runner's `localtime::tests`, ci-spec `time-functions` (TZ-independent round trips).
`LispEvaluatorTest#evalGetenv`, `#bareGetenvIsNotACommonLispFunction`,
`#evalSleepParksAndReturnsNil`, `#environmentEnquiryFamilyAnswersPerBackendConstants`;
`JvmLispCompilerTest#compileAndRunGetenv`, `#compileAndRunSleep`;
`Jvm/WasmLispCompilerTest#namestringHalvesNstringCaseAndEnvironmentEnquiry`;
`WasmLispCompilerTest#getenvInServeModeImportsWasiCliEnvironment`,
`#getenvInBaseComponentBindsTheBlocksEnvironmentInstance`;
`WasmLispCompilerIntegrationTest#componentGetenvFromWasiEnvironment`,
`#preview1GetenvDoesNotCorruptNewline`, `#sleepSpinsOnTheClockOnPreview1`,
`#componentSleepUsesTheHostTimerInsteadOfSpinning`; ci-spec
`getenv-does-not-corrupt-newline`, `clack-enablement-builtins`,
`namestring-halves-nstring-case-and-environment-enquiry` (its `expectedByBackend` carries the
one `machine-type` divergence).
