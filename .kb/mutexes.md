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
- `--no-gc` rejects the names. `#'rontolisp:make-mutex` and the other two are function values on
  all four (`NATIVE_VALUE_FUNCTIONS`, [lisp2-namespaces.md](lisp2-namespaces.md)).

## `with-mutex`
`LispMacroExpander.expandWithMutex`, dispatched on the qualified name from
`LispEvaluator.rareOperatorExpansion`, `JvmExprCompiler`, `WasmExprCompiler`; binds the mutex form
ONCE to `__mutex_lock`, releases under `unwind-protect`. WASM passes
`unwindProtect = ctx.ehMode` but does NOT flip the module into EH mode (absent from
`WasmLispCompiler.programUsesEhForm`). In `WasmAwaitNormalizer`'s non-strict head list.

## `bordeaux-threads` (nickname `bt`)
Tier-1 shim (`ShimLibraries` + `BuiltinSystems` + a `PackageRegistry` package): bordeaux-threads
0.9's v1 export list, the same-named `bt2` members imported (thread half: `.kb/threads.md`).
- **A lock is a `bt:lock` STRUCTURE over the mutex handle**, not the handle: serapeum
  specializes a method on `(class-of (bt:make-recursive-lock))`, which answered INTEGER on the
  interpreter. `bt:recursive-lock` is a `deftype` of it.
- `with-lock-held` / `with-recursive-lock-held` are `expandWithMutex`, not shim macros; for a
  `BORDEAUX-THREADS` head the expansion calls `bt:acquire-lock` / `bt:release-lock` (which
  unwrap), for `rontolisp:with-mutex` the mutex primitives.
- Supersets: `make-lock` REENTRANT and `&rest` (name ignored); `:wait-p` ignored.
- Condition variables: `bt::condition-variable` structure over `rontolisp::%condition-variable-new`
  / `%condition-wait` (variable, mutex, nanoseconds or nil -> t / nil on timeout) /
  `%condition-notify` (variable, all). Both backends run `runtime/RontoConditionVariable`: a
  ticket queue under its own monitor, the mutex released by every hold only once the ticket is
  queued (no lost notify) and retaken outside the monitor (no deadlock against a notifier
  holding the mutex). Interpreter: registered in `registerMutexes` (the mutex table); JVM:
  `invokestatic` on the class, which travels (`.kb/jvm-export.md`). WASM: `condition-wait`
  signals, notify/broadcast are no-ops.
- Semaphores are upstream's portable ones in Lisp (count + lock + condition variable), so they
  count on WASM too and only a wait that would block signals there.
- `interrupt-thread`, `with-timeout` signal everywhere (upstream's behaviour without them).
- `bt:*supports-threads-p*` is per-backend, so **`ShimLibraries.forms` takes the TARGET
  backend's `Features`** (`.kb/reader-features.md`).

## Tests
`MutexTest`, `JvmMutexTest`,
`WasmLispCompilerIntegrationTest#mutexPrimitivesAreNoOpsThatStillCompose`, ci-spec
`mutex-primitives`; the shim's condition variables, semaphores and lock class:
`ThreadTest`/`JvmThreadTest#btConditionVariablesAndSemaphoresHandOffBetweenNamedThreads`,
`WasmLispCompilerIntegrationTest#bt2ThreadEntryPointsSignalWhileThreadpAnswersNil`, ci-spec
`bt2-thread-api-portable-surface`.
