# Mutexes (`rontolisp:make-mutex` / `with-mutex`) and the `bordeaux-threads` shim

`serve` / `http-handler` run one virtual thread per request (interpreter, JVM).

**Invariant: a mutex handle is OPAQUE** — only identity through acquire/release is
portable; never print, order or do arithmetic on one.

| backend | handle | acquire/release |
|---|---|---|
| interpreter | index into `Environment.registerMutexes` (`ConcurrentHashMap<Long,ReentrantLock>` + `AtomicLong`) | real |
| JVM | the `ReentrantLock` itself as a plain `Object` (`JvmMutexRuntimeBuilder`: `_mutexNew`/`_mutexAcquire`/`_mutexRelease`) | real |
| WASM P1 + component | i31 constant `0` (`WasmMutexCompiler`) | identity |

- JVM handle is the lock, not an index: an index needs a lazy static table whose
  initialization would itself race. `java:` monitors: no reflection metadata on the
  native binary. `synchronized` cannot express acquire / body / release as three calls.
- WASM is single-threaded, but the names must EXIST: an undefined function is a
  compile-time error on the compile backends.
- Reentrancy deliberate. Releasing an unheld mutex: `LispEvalException` / 
  `IllegalMonitorStateException` / unnoticed (WASM).
- `--no-gc` rejects the names; no `BuiltinFunctionWrappers` entry, so
  `#'rontolisp:make-mutex` is an error.

## `with-mutex`

`LispMacroExpander.expandWithMutex`, dispatched on the qualified name (the
`rontolisp:with-arena` pattern) from `LispEvaluator.evalCons`, `JvmExprCompiler`,
`WasmExprCompiler`. Binds the mutex form ONCE to `__mutex_lock`, acquires, releases
under `unwind-protect`.

- WASM passes `unwindProtect = ctx.ehMode` but does NOT flip a module into EH mode
  (absent from `WasmLispCompiler.programUsesEhForm`).
- In `WasmAwaitNormalizer`'s non-strict head list (lock SPEC + body must not be hoisted).

## `bordeaux-threads` (nickname `bt`)

Tier-1 shim (`ShimLibraries` + `BuiltinSystems` + a `PackageRegistry` package);
upstream's `.asd` hard-errors on an unknown implementation. Lock half: `make-lock`,
`acquire-lock`, `release-lock`, `with-lock-held`, `*supports-threads-p*`; thread half is
`bt2` (`.kb/threads.md`).

- `with-lock-held` is not a shim defun but the same built-in expansion as `with-mutex`.
- Deliberate supersets: `make-lock` is REENTRANT; `acquire-lock`'s `:wait-p` accepted
  and ignored.
- `make-lock` takes `&rest`, so v1 positional name and v2 `:name "..."` both reach one
  symbol; name ignored. `bt2:with-lock-held ((lock-form))` uses the same
  one-element-spec expansion.
- `bt:*supports-threads-p*` is per-backend, which is why **`ShimLibraries.forms` takes
  the TARGET backend's `Features`** rather than hardcoding `Features.INTERPRETER`
  (`.kb/reader-features.md`).

## Tests

`MutexTest`; `JvmMutexTest` (drop `with-mutex` and it reports ~3200 of 8000 increments,
so it is not vacuous);
`WasmLispCompilerIntegrationTest#mutexPrimitivesAreNoOpsThatStillCompose`; ci-spec
`mutex-primitives`.
