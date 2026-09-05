# Time / environment built-ins (`get-universal-time`, `get-internal-real-time`, `get-internal-run-time`, `uiop:getenv`, and the environment-enquiry family)

## The three time built-ins
- Return an **integer on every backend**. In `LispNames` / `PackageRegistry.CL_FUNCTIONS`.
- Interpreter/JVM: `JvmTimeCompiler` via a `systemOps` methodref map on `Ctx`. WASM
  (`WasmTimeCompiler`): WASI `clock_time_get`; under `--no-wasi` all three read ONE cell a
  host writes through `__ronto_set_time` and signal while unset
  (`.kb/wasm-export-no-wasi.md`). i64 normalized through `_int_new` (`.kb/wasm-bignum.md`).
- `get-universal-time` = Unix seconds + 2208988800; `internal-time-units-per-second` is a
  reader constant **1000** beside `char-code-limit`.
- WASM clock/environ *imports* exist in both modes to keep import indices identical; the
  component's `environ_*` imports are DEAD WEIGHT kept because the eight preview1 slots are
  index-pinned.

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

## Universal-time codec
`encode-universal-time` / `decode-universal-time` are `LispPreludeLibrary` defuns: era-based
proleptic Gregorian integer arithmetic, exact at any year, `days*86400 + h*3600 + m*60 + s +
zone*3600` with **25567** as the 1900->1970 day offset. Deviations: a missing or nil
`time-zone` means **GMT, not local** (WASI exposes no timezone); `daylight-p` decodes nil.

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
