# Time / environment built-ins (`get-universal-time`, `get-internal-real-time`, `get-internal-run-time`, `uiop:getenv`)

Implemented in all three backends, returning an **integer everywhere** (the three time built-ins). Interpreter/JVM: `JvmTimeCompiler` via a `systemOps` methodref map on `Ctx`; `JvmGetenvCompiler`. WASM (`WasmTimeCompiler`) reads WASI `clock_time_get`, computes in i64 and normalizes through `_int_new`, so the value is an exact boxed integer (`TYPE_BIGNUM`, `.kb/wasm-bignum.md`) and `integerp` answers like the other backends. (Historical: before the boxed exact-integer path existed the WASM value was a FLOAT because the magnitudes exceed the `i31` range; that divergence is retired.) `uiop:getenv` on WASM uses a `_getenv` runtime helper (`WasmGetenvRuntimeBuilder`) scanning the WASI environ buffer. `get-universal-time` is seconds since the 1900 CL epoch (Unix + 2208988800). The three time built-ins are registered in `LispNames`/`PackageRegistry.CL_FUNCTIONS`. The WASM clock/environ imports exist in both modes (Preview 1 -> real host; component -> adapter over `wasi:clocks@0.3.0`/`wasi:cli/environment@0.3.0`), keeping import indices identical.

**Reading an environment variable is spelled `uiop:getenv`, and ONLY that (2026-07-29)**: ANSI Common Lisp has no `getenv`, so homing one in `cl` would have shipped a name no other implementation answers to and that a portability `#-`/`#+` could not see coming. It lives in the `uiop` package instead -- the spelling implementation-independent libraries already use -- alongside `file-exists-p`/`merge-pathnames*`/`add-package-local-nickname` as one of the four uiop members with a real definition (`.kb/asdf.md`). Mechanics: `LispNames.GETENV` is the member name and `LispNames.UIOP_GETENV` (`"UIOP:GETENV"`) the canonical qualified spelling; `PackageRegistry` lists it in `uiopExternals` and NOT in `CL_FUNCTIONS` (so `rontolisp:list-functions` and `symbol-function` do not know a bare `GETENV`); the interpreter registers it in `Environment.createGlobal` under the qualified name; both compilers dispatch on the qualified name in their `compileCons` package block (the `usocket:with-*` pattern) **before** the function-call path consults `LispMacroExpander.expandUiopStubCall`, which would otherwise lower it to the uiop stub's undefined-function error. There is deliberately no `cl:getenv` alias: a compatibility alias would keep the non-standard spelling alive in user code, which is the thing being retired. Pinned by `LispEvaluatorTest#evalGetenv` + `#bareGetenvIsNotACommonLispFunction`, `JvmLispCompilerTest#compileAndRunGetenv`, `WasmLispCompilerIntegrationTest#componentGetenvFromWasiEnvironment`/`#preview1GetenvDoesNotCorruptNewline`, and the ci-spec case `getenv-does-not-corrupt-newline`.

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
