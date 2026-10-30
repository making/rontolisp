# Threads (`rontolisp:make-thread` family) and the `bt2` shim package

`rontolisp:make-thread` (fn + optional bindings alist + optional name), `join-thread`, `threadp`,
`thread-alive-p`, `destroy-thread`, `current-thread`, internal `%thread-name` — interpreter and
JVM only. Driving
consumers: clack `src/handler.lisp` (`:use-thread t`); Scheme `parallel-execute`
(`.kb/scheme-frontend.md`).
**Invariant: a thread handle is OPAQUE** (`.kb/mutexes.md`); only `threadp` and the join
round-trip are portable.

## Backends
- Interpreter: `LispThread` record (thread, result, name) from `AsyncRuntime.spawnThread` — the ONLY
  LispEvaluator-reachable class touching threads, so the browser playground substitutes it
  wholesale (`Target_AsyncRuntime.spawnThread` runs the body synchronously).
- JVM: `{TMARKER, Thread, FutureTask, name}` marker-headed `Object[]`; `JvmThreadRuntimeBuilder`'s
  class `implements Callable`, `_thread_spawn` builds the handle on an UNSTARTED virtual thread,
  stores it in the runner's `_threadHandle`, then starts it.
- WASM (both): no handle can exist; the shim's `#+rontolisp-wasm` defuns SIGNAL at CALL time —
  never inline, which would turn a compile-time error into a silently sequential program.
  `threadp` answers nil, `bt:*supports-threads-p*` nil (WASM clackup runs `:use-thread nil`), the
  direct `rontolisp:make-thread` spelling is an undefined function, a call and a `#'` alike
  (`BuiltinFunctionWrappers.WASM_UNSUPPORTED`; the interpreter and the JVM have the values,
  [lisp2-namespaces.md](lisp2-namespaces.md)); `--no-gc` rejects the names.

## A value that is no thread
`join-thread`/`thread-alive-p`/`destroy-thread` of a non-thread: the operator's `type-error` expecting
`(SATISFIES RONTOLISP:THREADP)` on the interpreter (`requireThread`) and the JVM (`emitRequireThread`,
`_threadp` first); the wasm bt2 `thread-alive-p` signals the same condition and text by hand. A bindings
alist that is not a proper list of `(symbol . value)` conses is `make-thread`'s `simple-error` BEFORE a
thread starts on both (the JVM's `emitRequireBindings`; it used to spawn and fail at the join). A
non-function first argument still spawns on both and fails at the join. Pinned by `HandleOperandFixture`
([error-handling.md](error-handling.md), "A wrong-type argument names its operator").

## Bindings, join, feature
- The `(symbol . VALUE)` alist binds in the NEW thread only; a spawned thread inherits NO bindings
  (`.kb/dynamic-special-variables.md`) and they die with it. Interpreter: `DynamicBindings.push`,
  names need not be special (`progvUsed`). JVM: names arrive as runtime STRINGS, resolved via
  `_dtl(name)` -> `_d$` ThreadLocal -> `_dbind`; `JvmLispCompiler` forces
  `*STANDARD-OUTPUT*`/`*STANDARD-INPUT*`/`*ERROR-OUTPUT*` into `specialVars` (clack binds them
  through the alist, invisible to `SpecialVarCollector`; `.kb/standard-output-redirect.md`) and
  ALL specials into `boundSpecialVars`, which the thread primitives make thread-scoped (the
  program's specials are never bound shallow, `.kb/dynamic-special-variables.md` "One thread").
  A name that is one of the program's own plain (non-special) globals is a clear runtime error,
  not a silent miss; any other name (none of the program's variables, or a CL standard variable
  it never binds -- `bt:*standard-io-bindings*`'s reader variables) binds nothing, since no
  compiled read could see it (`_dtl` answers null). A closure built inside
  a binding extent captures no special: run on the new thread it reads that thread's binding,
  else the global.
- `join-thread` RE-SIGNALS the thread's error so `handler-case` dispatches by condition type. On
  the JVM the condition cannot ride `_condTl` across threads, so `call()` completes the FutureTask
  NORMALLY with `{EMARKER, throwable, condition}` and `_thread_join` records the condition under
  the throwable on the joining thread before rethrowing it (the `_await` pattern). Join
  `Thread.join`s after the value settles, so `thread-alive-p` answers nil deterministically.
  `destroy-thread` is `Thread.interrupt` — a request, not a kill.
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
caching in `_curThreadTl` (null task slot — self-join NPEs). Works for ANY thread; in a spawned
body it IS the spawner's handle (spawnThread / `call()` seed the ThreadLocal before the body
runs), which is how `bt:thread-name` of `(bt:current-thread)` finds the name (log4cl).

`bt:all-threads` is a shim registry: `bt2:make-thread` spawns and registers under one mutex,
dropping dead entries; the answer is the thread that loaded the system plus the live entries.
Threads spawned otherwise (a served request, the raw primitive) are absent.

## Tests
`ThreadTest`, `JvmThreadTest` (both run `HandleOperandFixture`),
`WasmLispCompilerIntegrationTest#bt2ThreadEntryPointsSignalWhileThreadpAnswersNil`, ci-spec
`bt2-thread-api-portable-surface` (spawn/join excluded — output cannot be identical across
backends).
