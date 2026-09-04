# Time / environment built-ins (`get-universal-time`, `get-internal-real-time`, `get-internal-run-time`, `uiop:getenv`, and the environment-enquiry family)

## The three time built-ins

- Return an **integer on every backend**. In `LispNames` /
  `PackageRegistry.CL_FUNCTIONS`.
- Interpreter/JVM: `JvmTimeCompiler` via a `systemOps` methodref map on `Ctx`.
- WASM (`WasmTimeCompiler`): WASI `clock_time_get`; under `--no-wasi` the single cell a
  host writes through the exported `__ronto_set_time` hook -- all three names read that ONE
  cell and signal while it is unset (`.kb/wasm-export-no-wasi.md`). Computes in i64,
  normalizes through `_int_new`, so the value is an exact boxed integer (`TYPE_BIGNUM`,
  `.kb/wasm-bignum.md`) and `integerp` agrees.
- `get-universal-time` = Unix seconds + 2208988800 (1900 CL epoch).
- `internal-time-units-per-second` is a reader constant **1000** beside `char-code-limit`.

WASM clock/environ *imports* exist in both modes (Preview 1 -> host; component -> adapter
over `wasi:clocks@0.3.0` / `wasi:cli/environment@0.3.0`), keeping import indices identical.
The component's `environ_*` imports are DEAD WEIGHT on every variant, kept only because the
eight preview1 import slots are index-pinned.

## `uiop:getenv` is a LISP definition over a per-backend primitive

- Public name = `uiop-os.lisp`'s defun: consults the `(setf (uiop:getenv x) v)` OVERRIDE
  map (prelude `%getenv-override` / `%getenv-override-set` over one `%getenv-overrides`
  alist), falls back to `rontolisp::%host-getenv` (`LispNames.HOST_GETENV`) -- the name
  every backend lowers and `EnvironmentLibrary` keys its component splice on. No host lets
  a process rewrite its own environment, so a WRITE must be an overlay; the read in Lisp
  makes the overlay visible on all four backends from one definition (`.kb/uiop.md`, incl.
  the interpreter's third lazy-load trigger for a setf PLACE).
- **Reading an environment variable is spelled `uiop:getenv` and ONLY that** -- ANSI CL has
  no `getenv`, and there is deliberately no `cl:getenv` alias. `LispNames.GETENV` is the
  member name, `LispNames.UIOP_GETENV` (`"UIOP/OS:GETENV"`) the canonical qualified
  spelling; `PackageRegistry` exports from `uiop/os`, re-exports from `uiop`, and keeps it
  OUT of `CL_FUNCTIONS` (so `symbol-function` does not know a bare `GETENV`). Interpreter
  registers `%host-getenv` in `Environment.createGlobal`; both compilers dispatch that
  internal name in `compileCons`.
- Preview 1: `_getenv` runtime helper (`WasmGetenvRuntimeBuilder`) scanning the host-filled
  WASI environ buffer behind a FIXED 16 KiB window (`ENV_BUF_ADDR`) -- a >16 KiB
  environment there is a live hazard.

Pins: `LispEvaluatorTest#evalGetenv`, `#bareGetenvIsNotACommonLispFunction`,
`JvmLispCompilerTest#compileAndRunGetenv`,
`WasmLispCompilerIntegrationTest#componentGetenvFromWasiEnvironment` /
`#httpHandlerReadsTheEnvironmentUnderWasmtimeServe` /
`#preview1GetenvDoesNotCorruptNewline`, ci-spec `getenv-does-not-corrupt-newline`.

## `%host-argv` rides the same seam

`uiop/image`'s command-line family is one `uiop-image.lisp` definition over
`LispNames.HOST_ARGV`, answering `(program-name user-arg ...)` everywhere. `--component`
shares the environment seam (`environment.lisp` binds `get-arguments` beside
`get-environment`, which is why the fixed import block had to declare it); interpreter uses
the vector the CLI threads in, JVM `main`'s `String[]` behind the class name, Preview 1 an
`_argv` helper over `args_sizes_get` / `args_get` bound as APPENDED user imports (no
preview1 slot and no adapter export list moves). `.kb/uiop.md`.

## `--component` getenv is a Lisp library, not an adapter path

- `environment.lisp` over wit-imported `wasi:cli/environment@0.3.0`
  (`src/main/resources/am/ik/rontolisp/eval/environment.{lisp,wit}`, spliced by
  `eval/EnvironmentLibrary` on reference -- the sockets.lisp / wait.lisp pattern).
  `WasmExprCompiler` does NOT dispatch the name in component mode; a skipped splice is an
  explicit COMPILE error, not a runtime "undefined function".
- `get-environment` returns `list<tuple<string,string>>`; the defun walks it, so unset
  answers nil and an empty value answers `""`.
- The WASI 0.3 **service** world carries no `wasi:cli/environment`, so serve's adapter
  answered `environ_*` with a zero-entry environment (silent nil under
  `wasmtime serve --env`). `WasmComponentBuilder` now binds `get-environment` FROM the
  fixed block for base/sockets (`FIXED_BLOCK_IFACES` + the block's instance index for
  `lowerFixedFromBlock`'s `instanceOf` map -- a user `rontolisp:wit-import` of the
  interface rides the same path), and for serve as an appended `appendUserImports` instance.
- The import is **conditional** on the program calling `uiop:getenv`, so a getenv-free
  component still runs on a host providing only the service world.
- The base path has no 16 KiB environ window (the canonical ABI allocates lifted strings).
- Re-evaluation trigger: if the service world gains `wasi:cli/environment` (or the base
  block is regenerated), the adapter/bridge `environ_*` bodies and `_getenv` become
  removable in component mode.
- Pins: `WasmLispCompilerTest#getenvInServeModeImportsWasiCliEnvironment`,
  `#getenvInBaseComponentBindsTheBlocksEnvironmentInstance`.

## Universal-time codec

`encode-universal-time` / `decode-universal-time` are `LispPreludeLibrary` defuns: pure
era-based proleptic Gregorian integer arithmetic, one definition everywhere, exact at any
year. `encode` composes `days*86400 + h*3600 + m*60 + s + zone*3600` with **25567** as the
1900->1970 day offset; `decode` inverts and returns the nine CL values. Lite deviations: a
missing or nil `time-zone` means **GMT, not local** (no backend-portable local-zone source;
WASI exposes no timezone), and `daylight-p` always decodes nil.

## `rontolisp:random-bytes`

Cryptographic sibling of `random` (which is `Math.random` on interpreter/JVM), a prelude
defun over the per-backend `rontolisp::%random-byte` primitive (one byte per call):
interpreter = a process-wide `java.security.SecureRandom` in `Environment`; JVM =
`_randomByte` from `JvmSecureRandomRuntimeBuilder`, gated on a reference to the primitive so
an entropy-free program never loads `java.security` and stays byte-identical; WASM = low
byte of a WASI `random_get` draw (`wasi:random` under `--component`), boxed as an i31.
Retired `ironclad-prng.lisp`'s signalling stub (`.kb/asdf.md`).

## `sleep`

`LispMacroExpander.expandSleep` does the shared conversion `(round (* n 1000))` (so `0.5`
is 500 ms) and the non-positive guard. The wait is per backend:

- **Interpreter / JVM**: park via the `%SLEEP-MS` primitive -- an `Environment`
  registration and `JvmSleepCompiler`'s `Number.longValue` + `Thread.sleep(J)`, its two
  constant-pool entries minted at the call site so a sleep-free program keeps its bytes.
- **`--component`**: NOT a compiler lowering -- the spliced `wait.lisp` DEFUN
  (`eval/WaitForLibrary`, triggered by `rontolisp:wait-for` OR `sleep`) FORCES a
  `wasi:clocks/monotonic-clock` timer future through `rontolisp::%future-force`.
- **Preview 1**: clock spin (`expandSleep`'s `spin` arm); its nine imports include a clock
  but no timer. Trigger to revisit: a `poll_oneoff` import.
- **`--no-wasi`** (both entry shapes): SIGNALS at call time naming `sleep`, argument still
  evaluated for effect (`WasmExprCompiler`, before the spin arm) -- its clock only moves on
  a host write, so the spin would never terminate. Trigger to revisit: a `--host-clock`
  import (the `--host-random` shape).

Three constraints force the component arm to be a defun that FORCES rather than awaits:
`await` is only legal at top level or inside `async-defun`/`async-lambda` while `sleep`
must be callable anywhere (clack's handler `stop` calls it in a plain defun), so the wait is
`%future-force` -- the split `sockets.lisp`'s `tcp-*` surface uses (`.kb/tcp-sockets.md`);
a lowering in `WasmExprCompiler` runs after the point `WasmLispCompiler`'s async pass needs
the future machinery, so it would compile to a `#<FUTURE>` value instead of a wait; and
being a defun is what makes `#'sleep` work. `#'sleep` is in
`BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS` -- an ungated wrapper would put
`(sleep x)` into EVERY component, including ones the splice skipped.

Accepted cost: the host timer puts the module in async (therefore EH) mode, where the spin
needed neither -- a deliberate exception to "a program without those forms keeps its flags".

Pins: `LispEvaluatorTest#evalSleepParksAndReturnsNil`,
`JvmLispCompilerTest#compileAndRunSleep`,
`WasmLispCompilerIntegrationTest#sleepSpinsOnTheClockOnPreview1`,
`#componentSleepUsesTheHostTimerInsteadOfSpinning` (also pins the plain-defun call site and
`#'sleep`), ci-spec `clack-enablement-builtins`.

## Environment-enquiry family (CLHS 25.1.5)

**All nine are CONSTANTS, and only `machine-type` differs between backends.**
`LispPreludeLibrary` defuns, not per-backend built-ins -- which makes `#'software-type` and
friends first-class for free.

| name | answer |
| --- | --- |
| `lisp-implementation-type` | `"rontolisp"` |
| `lisp-implementation-version` | `Version.getVersion()`, BAKED into the prelude source at class-init, so all four backends report the build that COMPILED them; equals `(getf (rontolisp:version) :version)` |
| `software-type` | `"Unix"` (matches `uiop/os`: `os-unix-p` -> `t`, `operating-system` -> `:unix`) |
| `software-version` | `nil` |
| `machine-type` | `"JVM"` / `"WASM32"`, via `%target-machine-type` |
| `machine-version` | `nil` |
| `machine-instance` | `nil` (no host-identity primitive; `uiop:hostname` agrees) |
| `short-site-name` / `long-site-name` | `nil` |

**The host is NOT consulted anywhere, including interpreter and JVM**:
`System.getProperty("os.name"/"os.arch")` are deliberately unused -- a compiled `.class`
runs on a machine the compiler never saw, and a run-time query would make the JVM answer
what the WASM backends structurally cannot, breaking emitted-output determinism.
`machine-type` names the ABI for the same reason `uiop:architecture` does (`.kb/uiop.md`).
Everything unknowable answers `nil`, never a fabricated string.

`%target-machine-type` is the one per-backend piece: `Environment.createGlobal` defines it
(`"JVM"`), `JvmExprCompiler` lowers to the `"JVM"` literal, `WasmExprCompiler` to
`"WASM32"`. Re-evaluation trigger: a third compile target needs a third arm here and
nowhere else; a backend gaining a real host-identity or OS primitive should revisit
`machine-instance` / `software-type` / `uiop:hostname` TOGETHER.

Pins: `LispEvaluatorTest#environmentEnquiryFamilyAnswersPerBackendConstants`,
`Jvm/WasmLispCompilerTest#namestringHalvesNstringCaseAndEnvironmentEnquiry` (plus the
component twin), ci-spec `namestring-halves-nstring-case-and-environment-enquiry`, whose
`expectedByBackend` carries the one `machine-type` divergence and spells the version as an
AGREEMENT with `rontolisp:version` rather than a literal.
