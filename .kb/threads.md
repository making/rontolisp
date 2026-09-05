# Threads (`rontolisp:make-thread` family) and the `bt2` shim package

`rontolisp:make-thread` (fn + optional bindings alist), `join-thread`, `threadp`,
`thread-alive-p`, `destroy-thread`, `current-thread` — interpreter and JVM only. Driving
consumer: clack `src/handler.lisp` (`:use-thread t`).
**Invariant: a thread handle is OPAQUE** (`.kb/mutexes.md`); only `threadp` and the join
round-trip are portable.

## Backends
- Interpreter: `LispThread` record from `AsyncRuntime.spawnThread` — the ONLY
  LispEvaluator-reachable class touching threads, so the browser playground substitutes it
  wholesale (`Target_AsyncRuntime.spawnThread` runs the body synchronously).
- JVM: `{TMARKER, Thread, FutureTask}` marker-headed `Object[]`; `JvmThreadRuntimeBuilder`'s class
  `implements Callable`, `_thread_spawn` starts a virtual thread.
- WASM (both): no handle can exist; the shim's `#+rontolisp-wasm` defuns SIGNAL at CALL time —
  never inline, which would turn a compile-time error into a silently sequential program.
  `threadp` answers nil, `bt:*supports-threads-p*` nil (WASM clackup runs `:use-thread nil`), the
  direct `rontolisp:make-thread` spelling is not compiled at all. No `BuiltinFunctionWrappers`
  entries; `--no-gc` rejects the names.

## Bindings, join, feature
- The `(symbol . VALUE)` alist binds in the NEW thread only; a spawned thread inherits NO bindings
  (`.kb/dynamic-special-variables.md`) and they die with it. Interpreter: `DynamicBindings.push`,
  names need not be special (`progvUsed`). JVM: names arrive as runtime STRINGS, resolved via
  `_dtl(name)` -> `_d$` ThreadLocal -> `_dbind`; `JvmLispCompiler` forces
  `*STANDARD-OUTPUT*`/`*STANDARD-INPUT*`/`*ERROR-OUTPUT*` into `specialVars` (clack binds them
  through the alist, invisible to `SpecialVarCollector`; `.kb/standard-output-redirect.md`) and
  ALL specials into `boundSpecialVars`. A non-special name is a clear runtime error, not a silent
  global write. A closure built inside a binding extent still reads its capture — pass `#'name`
  for the global value.
- `join-thread` RE-SIGNALS the thread's error so `handler-case` dispatches by condition type. On
  the JVM the condition cannot ride `_condTl` across threads, so `call()` completes the FutureTask
  NORMALLY with `{EMARKER, throwable, condition}` and `_thread_join` re-sets `_condTl` before
  rethrowing (the `_await` pattern). Join `Thread.join`s after the value settles, so
  `thread-alive-p` answers nil deterministically. `destroy-thread` is `Thread.interrupt` — a
  request, not a kill.
- `:thread-support` is in `Features.INTERPRETER`/`Features.JVM`, not WASM; declared statically
  because an upstream `.asd` push reaches only that file's own conditionals
  (`.kb/reader-features.md`). Consumer: `clack:clackup` (`.kb/clack.md`). Pinned by ci-spec
  `reader-features-variable` (feature count 4 vs 3).

## bt2 shim
`bordeaux-threads.lisp` (ShimLibraries) provides BOTH namespaces of the one built-in
`bordeaux-threads` system; `BT2` canonical, nickname `BORDEAUX-THREADS-2`. Cross-package IMPORT
redirects make one definition serve both spellings, so `bt2:with-lock-held` hits the existing
`LispMacroExpander` qualified-name dispatch with NO new dispatcher entries.
`bt2:make-thread`'s `:initial-bindings` maps onto the primitive's alist via
`bt2::resolve-binding-value`, which accepts `quote` forms and self-evaluating values and SIGNALS
otherwise; a library passing a variable-reference form would need eval-in-the-new-thread
(`.kb/eval-runtime.md`).

`current-thread` is **EQ-stable per thread** — what dbi's per-thread connection cache keys on
(`cache/thread.lisp`'s `steal-cache-table`). Interpreter `AsyncRuntime.currentThreadHandle()`
(ThreadLocal-cached, future never completed — self-join blocks forever); JVM `_thread_current`
caching in `_curThreadTl` (null task slot — self-join NPEs). Works for ANY thread; a spawned
body's self-handle is its own, NOT the spawner's handle for it.

## Tests
`ThreadTest`, `JvmThreadTest`,
`WasmLispCompilerIntegrationTest#bt2ThreadEntryPointsSignalWhileThreadpAnswersNil`, ci-spec
`bt2-thread-api-portable-surface` (spawn/join excluded — output cannot be identical across
backends).
