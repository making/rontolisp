# Time / environment built-ins (`get-universal-time`, `get-internal-real-time`, `get-internal-run-time`, `uiop:getenv`)

Implemented in all three backends, returning an **integer everywhere** (the three time built-ins). Interpreter/JVM: `JvmTimeCompiler` via a `systemOps` methodref map on `Ctx`; `JvmGetenvCompiler`. WASM (`WasmTimeCompiler`) reads WASI `clock_time_get` -- or, under `--no-wasi`, the cell a host writes through the exported `__ronto_set_time` hook, all three names off the ONE cell and signalling while it is unset (`.kb/wasm-export-no-wasi.md` has the rule and the reason a host-supplied time is not the fabrication a stubbed clock would be) -- computes in i64 and normalizes through `_int_new`, so the value is an exact boxed integer (`TYPE_BIGNUM`, `.kb/wasm-bignum.md`) and `integerp` answers like the other backends. (Historical: before the boxed exact-integer path existed the WASM value was a FLOAT because the magnitudes exceed the `i31` range; that divergence is retired.) `uiop:getenv` on **Preview 1** uses a `_getenv` runtime helper (`WasmGetenvRuntimeBuilder`) scanning the host-filled WASI environ buffer; **every component variant** instead reads `wasi:cli/environment@0.3.0` through the `environment.lisp` library (see below). `get-universal-time` is seconds since the 1900 CL epoch (Unix + 2208988800). The three time built-ins are registered in `LispNames`/`PackageRegistry.CL_FUNCTIONS`. The WASM clock/environ *imports* exist in both modes (Preview 1 -> real host; component -> adapter over `wasi:clocks@0.3.0`/`wasi:cli/environment@0.3.0`), keeping import indices identical -- but since 2026-07-30 the component's `environ_*` imports are DEAD WEIGHT on every variant (`_getenv` is no longer the component getenv; the base adapter's environ decode and the serve bridge's zero-entry stub are both unreachable from Lisp), kept only because the eight preview1 import slots are index-pinned.

**`uiop:getenv` is a LISP definition over a per-backend primitive (2026-08-15, todo-356)**: the public name is `uiop-os.lisp`'s defun, which consults the `(setf (uiop:getenv x) v)` OVERRIDE map (the prelude's `%getenv-override` / `%getenv-override-set` over one `%getenv-overrides` alist) and falls back to `rontolisp::%host-getenv` -- `LispNames.HOST_GETENV`, the name every backend below now lowers, and the name `EnvironmentLibrary` keys its component splice on. No host lets a process rewrite its own environment (the JVM cannot at all, WASI's is read-only), so a WRITE has to be an overlay, and putting the read in Lisp is what makes the overlay visible on all four backends from one definition; `.kb/uiop.md` (`uiop/os`'s decisions) has the full shape, including the interpreter's third lazy-load trigger for a setf PLACE. The paragraphs below describe `%host-getenv`, which is what they always described -- only the name at the top changed.

**Reading an environment variable is spelled `uiop:getenv`, and ONLY that (2026-07-29)**: ANSI Common Lisp has no `getenv`, so homing one in `cl` would have shipped a name no other implementation answers to and that a portability `#-`/`#+` could not see coming. It lives in the `uiop` package instead -- the spelling implementation-independent libraries already use -- as one of the uiop members with a real definition (`.kb/uiop.md`). Mechanics: `LispNames.GETENV` is the member name and `LispNames.UIOP_GETENV` (`"UIOP/OS:GETENV"` -- getenv's HOME sub-package, which is what a `uiop:getenv` occurrence resolves to) the canonical qualified spelling; `PackageRegistry` exports it from `uiop/os` and re-exports it from `uiop`, and NOT from `CL_FUNCTIONS` (so `rontolisp:list-functions` and `symbol-function` do not know a bare `GETENV`); the interpreter registers `%host-getenv` in `Environment.createGlobal` and both compilers dispatch that internal name in their `compileCons` name switch (it was the QUALIFIED uiop name before todo-356, matched through `UiopExports.denotes` ahead of the function-call path; the public name is a real defun now, so the dispatch moved down to the primitive). There is deliberately no `cl:getenv` alias: a compatibility alias would keep the non-standard spelling alive in user code, which is the thing being retired. Pinned by `LispEvaluatorTest#evalGetenv` + `#bareGetenvIsNotACommonLispFunction`, `JvmLispCompilerTest#compileAndRunGetenv`, `WasmLispCompilerIntegrationTest#componentGetenvFromWasiEnvironment`/`#httpHandlerReadsTheEnvironmentUnderWasmtimeServe`/`#preview1GetenvDoesNotCorruptNewline`, and the ci-spec case `getenv-does-not-corrupt-newline`.

**On `--component` getenv is a Lisp library, not an adapter path (todo 217, 2026-07-30)**:
`uiop:getenv` under `--component` is `environment.lisp` over a wit-imported
`wasi:cli/environment@0.3.0` (`src/main/resources/am/ik/rontolisp/eval/environment.{lisp,wit}`,
spliced by `eval/EnvironmentLibrary` when the program references the name -- the
sockets.lisp / wait.lisp pattern), and `WasmExprCompiler` therefore does NOT dispatch the
name in component mode: the call resolves to that defun (with an explicit compile error if
the splice was skipped, so a pipeline that forgets it cannot fall through to a runtime
"undefined function"). `get-environment` hands back the whole environment as
`list<tuple<string,string>>` -- a Lisp list of two-element lists -- and the defun walks it,
so unset answers nil and an empty value answers "".

Why it is ONE binding for base AND serve, and why that mattered: the served component was
the broken case (`uiop:getenv` -> nil for every variable, silently, whatever
`wasmtime serve --env` / `-S inherit-env=y` said) because the WASI 0.3 **service** world
carries no `wasi:cli/environment`, so `adapter-http-server-p1.wat` answers `environ_*` with
a zero-entry environment and `import-block-http-server.bin` declares no such interface. The
fix is deliberately NOT a second copy of the base adapter's environ decode: the base /
sockets blocks already declare the interface, so `WasmComponentBuilder` binds
`get-environment` FROM the block (`FIXED_BLOCK_IFACES` + the block's own instance index for
`lowerFixedFromBlock`'s `instanceOf` map -- a user's own `rontolisp:wit-import` of the
interface now rides the same path instead of being rejected), while serve, whose block has
none, gets the same binding as an appended `appendUserImports` instance. Two consequences to
keep: the import is **conditional** on the program actually calling `uiop:getenv` (a
getenv-free component declares nothing extra and still runs on a host that provides only the
service world -- wasmCloud is the case that motivated the conditionality; verified
byte-identical output across preview1 / base / serve / fetch / sockets / keyvalue-serve /
`--no-gc` before-and-after), and the base path no longer has the adapter's FIXED 16 KiB
environ window (`ENV_BUF_ADDR`), since the canonical ABI allocates the lifted strings --
Preview 1 keeps that window, and a >16 KiB environment there is still the old hazard.
Re-evaluation trigger: if the service world ever gains `wasi:cli/environment` (or the base
block is regenerated), the adapter/bridge `environ_*` bodies and `_getenv` become removable
in component mode -- they are already unreachable from Lisp. Compile-level pins:
`WasmLispCompilerTest#getenvInServeModeImportsWasiCliEnvironment` (the serve user import +
the emitted WIT line) and `#getenvInBaseComponentBindsTheBlocksEnvironmentInstance` (exactly
ONE import of the name off serve).

**The universal-time codec (`encode-universal-time` / `decode-universal-time`,
2026-07-26)**: both are `LispPreludeLibrary` defuns -- pure era-based proleptic
Gregorian arithmetic over integers, so one definition runs on every backend and
is exact at any year (no table, no host calendar). `encode` composes
`days*86400 + h*3600 + m*60 + s + zone*3600` with 25567 as the 1900->1970 day
offset; `decode` inverts it and returns the nine CL values. Two documented lite
deviations: a missing (or nil) `time-zone` means **GMT, not the machine's local
zone** -- no backend-portable local-zone source exists (WASI exposes no
timezone at all) and defaulting to the one zone every backend agrees on keeps
the pair backend-identical -- and `daylight-p` always decodes as nil.
`internal-time-units-per-second` is a reader constant (1000) beside
`char-code-limit`, matching `get-internal-real-time`'s milliseconds.

**`rontolisp:random-bytes` (2026-07-26)** is the cryptographic-entropy sibling
of `random`, and deliberately NOT the same generator: `random` is an ordinary
PRNG (`Math.random` on the interpreter/JVM), while `random-bytes` draws from
`java.security.SecureRandom` there and from the WASI `random_get` host function
on both wasm backends (`wasi:random` under `--component`). The public function
is a prelude defun over the internal per-backend `rontolisp::%random-byte`
primitive (one byte per call): the interpreter registers it in `Environment`
over a process-wide `SecureRandom`, the JVM emits `_randomByte` from
`JvmSecureRandomRuntimeBuilder` (gated on a reference to the primitive, so an
entropy-free program never loads `java.security` and stays byte-identical), and
WASM loads the low byte of a `random_get` draw and boxes it as an i31. This is
what retired `ironclad-prng.lisp`'s signalling stub -- see `.kb/asdf.md`.

**`sleep`: a real host timer everywhere but Preview 1 (todo-225)**.
`LispMacroExpander.expandSleep` does the shared seconds-to-whole-milliseconds conversion
(`(round (* n 1000))`, so `0.5` is 500 ms) and the non-positive guard; the wait itself is
per backend.

- **Interpreter / JVM**: park, through the `%SLEEP-MS` internal primitive -- an
  `Environment` registration and `JvmSleepCompiler`'s `Number.longValue` +
  `Thread.sleep(J)`, whose two constant-pool entries are minted at the call site so a
  sleep-free program keeps its bytes.
- **`--component`**: `sleep` is NOT a compiler lowering at all but the spliced
  `wait.lisp` DEFUN (`eval/WaitForLibrary`, whose trigger is `rontolisp:wait-for` OR
  `sleep`), which FORCES a `wasi:clocks/monotonic-clock` timer future through
  `rontolisp::%future-force`. Measured: a 2 s sleep costs 0 CPU above the empty-program
  baseline, where the spin cost 2.16 s of it.
- **WASM Preview 1**: the clock spin (`expandSleep`'s `spin` arm) -- its nine imports
  include a clock but no timer to wait on, so burning the interval is the only way to
  elapse it. **Re-evaluation trigger**: a `poll_oneoff` import would retire it.
- **WASM `--no-wasi`** (both entry shapes): SIGNALS, a call-time error naming `sleep`
  with the argument still evaluated for effect (`WasmExprCompiler`, before the spin
  arm). It has no timer AND no clock that can advance while a call runs -- its clock is
  a cell only a host write moves (`__ronto_set_time`, `.kb/wasm-export-no-wasi.md`) --
  so the Preview 1 spin would be an infinite loop rather than a wait. **Re-evaluation
  trigger**: a `--host-clock` import (the `--host-random` shape) would make the spin
  terminate and could restore it.

**Why the component arm is a defun and FORCES rather than AWAITS** -- three constraints
that between them leave exactly one shape, all three found by trying the alternatives:
(1) an `await` is only legal at top level or inside an `async-defun`/`async-lambda`
(`RONTOLISP:AWAIT is only allowed inside ...`), and `sleep` has to be callable from
anywhere -- clack's handler `stop` calls it inside a plain defun -- so the wait must be
`%future-force`, the same synchronous/asynchronous split sockets.lisp's `tcp-*` surface
uses (`.kb/tcp-sockets.md`); (2) the lowering cannot live in `WasmExprCompiler`, because
the `await`/future machinery it introduces has to be in the program BEFORE
`WasmLispCompiler`'s async pass runs and that compiler runs long after it -- a lowering
emitted there compiles to a `#<FUTURE>` value instead of a wait; (3) being a defun is what
makes `#'sleep` work, since no built-in wrapper is injected for a name the program
defines. `WasmExprCompiler` therefore only handles the Preview 1 spin and, in component
mode, raises an explicit compile error when the splice is missing rather than letting the
call fall through to a runtime "undefined function" (the `uiop:getenv` pattern).
`#'sleep` is in `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS` for the sharper
version of the same reason: an ungated wrapper would put `(sleep x)` into EVERY component,
including the ones the splice skipped.

**Cost of the component arm, accepted deliberately**: awaiting a host timer puts the
module in async -- and therefore EH -- mode, so a sleeping component needs
`-W exceptions=y` where the old spin needed no flag. That is a real change to the "a
program without those forms keeps its flags" line, taken because a busy-wait under
`--component` blocks the whole instance and burns a core.

Pinned by `LispEvaluatorTest#evalSleepParksAndReturnsNil`,
`JvmLispCompilerTest#compileAndRunSleep`,
`WasmLispCompilerIntegrationTest#sleepSpinsOnTheClockOnPreview1` +
`#componentSleepUsesTheHostTimerInsteadOfSpinning` (which also pins the plain-defun call
site and `#'sleep`), and the ci-spec case `clack-enablement-builtins`.
