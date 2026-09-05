# Mutexes (`rontolisp:make-mutex` / `with-mutex`) and the `bordeaux-threads` shim

`serve` / `http-handler` run one virtual thread per request (interpreter, JVM).
**Invariant: a mutex handle is OPAQUE** — only identity through acquire/release is
portable; never print, order or do arithmetic on one.

## Handles
- interpreter: index into `Environment.registerMutexes`; real.
- JVM: the `ReentrantLock` itself as a plain `Object` (`JvmMutexRuntimeBuilder`:
  `_mutexNew`/`_mutexAcquire`/`_mutexRelease`); real. Not an index — a lazy static
  table's initialization would itself race.
- WASM P1 + component: i31 constant `0` (`WasmMutexCompiler`), identity only. Names must
  EXIST even single-threaded: an undefined function is a compile error.
- Reentrant. Releasing an unheld mutex: `LispEvalException` /
  `IllegalMonitorStateException` / unnoticed (WASM).
- `--no-gc` rejects the names; no `BuiltinFunctionWrappers` entry, so
  `#'rontolisp:make-mutex` is an error.

## `with-mutex`
`LispMacroExpander.expandWithMutex`, dispatched on the qualified name from
`LispEvaluator.evalCons`, `JvmExprCompiler`, `WasmExprCompiler`; binds the mutex form
ONCE to `__mutex_lock`, releases under `unwind-protect`. WASM passes
`unwindProtect = ctx.ehMode` but does NOT flip the module into EH mode (absent from
`WasmLispCompiler.programUsesEhForm`). In `WasmAwaitNormalizer`'s non-strict head list.

## `bordeaux-threads` (nickname `bt`)
Tier-1 shim (`ShimLibraries` + `BuiltinSystems` + a `PackageRegistry` package). Lock half
`make-lock`, `acquire-lock`, `release-lock`, `with-lock-held`, `*supports-threads-p*`;
thread half is `bt2` (`.kb/threads.md`).
- `with-lock-held` is the same built-in expansion as `with-mutex`, not a shim defun.
- Supersets: `make-lock` REENTRANT and `&rest` (name ignored); `:wait-p` ignored.
- `bt:*supports-threads-p*` is per-backend, so **`ShimLibraries.forms` takes the TARGET
  backend's `Features`** (`.kb/reader-features.md`).

## Tests
`MutexTest`, `JvmMutexTest`,
`WasmLispCompilerIntegrationTest#mutexPrimitivesAreNoOpsThatStillCompose`, ci-spec
`mutex-primitives`.
