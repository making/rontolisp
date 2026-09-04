# Threads (`rontolisp:make-thread` family) and the `bt2` shim package

`rontolisp:make-thread` (fn + optional bindings alist), `join-thread`, `threadp`,
`thread-alive-p`, `destroy-thread` make user code a thread SPAWNER on the interpreter and
JVM. Driving consumer: clack `src/handler.lisp` (`:use-thread t`).

**Invariant: a thread handle is OPAQUE** (`.kb/mutexes.md`). Only `threadp`'s answer and
the join round-trip are portable.

- Interpreter: a `LispThread` record (Thread + result `CompletableFuture`), spawned by
  `AsyncRuntime.spawnThread` — kept there because it is the ONLY LispEvaluator-reachable
  class touching threads, so the browser playground substitutes it wholesale
  (`Target_AsyncRuntime.spawnThread` runs the body synchronously — documented limitation).
- JVM: `{TMARKER, Thread, FutureTask}` (marker-headed `Object[]`, the async stream-value
  pattern). `JvmThreadRuntimeBuilder`: the generated class `implements Callable`,
  `_thread_spawn` news a runner and starts a virtual thread on a `FutureTask` over
  `call()`.
- WASM (both): no handle can exist; the shim's `#+rontolisp-wasm` defuns SIGNAL at call
  time.

## Dynamic bindings in the spawned thread

The optional `(symbol . VALUE)` alist becomes thread-scoped dynamic bindings in the NEW
thread only; the spawned thread inherits NO bindings (`.kb/dynamic-special-variables.md`).
No unbind needed — they die with the thread.

- Interpreter: `DynamicBindings.push` per pair inside the spawned body; names need not be
  proclaimed special (`make-thread` sets `progvUsed`).
- JVM: names arrive as runtime STRINGS, so `call()` resolves each through `_dtl(name)`
  (String.equals chain) to its `_d$` ThreadLocal and `_dbind`s it. Two forcings in
  `JvmLispCompiler` make that total when any thread primitive is used: (1)
  `*STANDARD-OUTPUT*`/`*STANDARD-INPUT*`/`*ERROR-OUTPUT*` into `specialVars` (clack binds
  them through the alist, invisible to the static `SpecialVarCollector`; this activates
  the redirect machinery, `.kb/standard-output-redirect.md`), (2) ALL specials into
  `boundSpecialVars`. A non-special name is a clear runtime error, not a silent global
  write.
- A closure built INSIDE a dynamic-binding extent still reads its capture in the new
  thread (dual-bind capture rule) — pass `#'name` to see the global value.

## Errors and join

`join-thread` RE-SIGNALS an error the thread died on so `handler-case` around the join
dispatches by condition type. On the JVM the condition cannot ride `_condTl` across
threads, so `call()` completes the FutureTask NORMALLY with
`{EMARKER, throwable, condition}` and `_thread_join` re-sets `_condTl` on the joining
thread before rethrowing (the `_await` pattern — why the thread runtime `ensure`s the
condition channel). Join also `Thread.join`s after the value settles, so
`thread-alive-p` answers nil deterministically. `destroy-thread` is `Thread.interrupt`: a
request, not a kill.

## `:thread-support` feature

In `Features.INTERPRETER` and `Features.JVM`, not WASM. Declared statically because
upstream pushes it from its `.asd`, and a `.asd` push reaches only that file's own
conditionals (`.kb/reader-features.md`). Consumer: `clack:clackup`'s
`#+thread-support t #-thread-support nil` default for `:use-thread` (`.kb/clack.md`).
Pinned by ci-spec `reader-features-variable` (feature count 4 vs 3).

## The bt2 package and the shim

`bordeaux-threads.lisp` (ShimLibraries) provides BOTH API namespaces of the one built-in
`bordeaux-threads` system. `BT2` is canonical with nickname `BORDEAUX-THREADS-2`.
Cross-package IMPORT redirects (closer-common-lisp / uiop-image precedent) make one
definition serve both spellings: v1 imports the thread names +
`*default-special-bindings*` from `bt2`; `bt2` imports the lock subset from v1, so
`bt2:with-lock-held` resolves to `BORDEAUX-THREADS:WITH-LOCK-HELD` and hits the existing
`LispMacroExpander` qualified-name dispatch with NO new dispatcher entries.

`bt2:make-thread (function &key name initial-bindings trap-conditions)` maps
`:initial-bindings` (upstream: `(symbol . form)` evaluated in the new thread) onto the
primitive's alist via `bt2::resolve-binding-value`, which accepts `quote` forms and
self-evaluating values and SIGNALS on anything else; within that subset evaluating in the
spawner is unobservable. Trigger: a library passing a variable-reference form needs
eval-in-the-new-thread (`.kb/eval-runtime.md`, gating `usesEval` on the thread
primitives).

## current-thread

`rontolisp:current-thread` (behind `bt2:current-thread`) answers the calling thread's OWN
handle and is **EQ-stable per thread** — what dbi's per-thread connection cache keys on
(`cache/thread.lisp`'s `steal-cache-table`).

- Interpreter: `AsyncRuntime.currentThreadHandle()` lazily wraps `Thread.currentThread()`
  in a `LispThread`, cached in a ThreadLocal; its future is never completed, so joining
  your own handle blocks forever.
- JVM: `_thread_current` caches `{TMARKER, Thread.currentThread(), null}` in the
  `_curThreadTl` static ThreadLocal (declared + `<clinit>`-initialized next to the
  condition channel's; the null task slot means a self-join NPEs).
- Works for ANY thread, not only spawns. A spawned body's self-handle is its own cached
  one, NOT the spawner's handle for it, so only `threadp`/`thread-alive-p` are portable.
- On WASM the shim entry point signals; nothing bundled reaches it (the dbi override
  `.asd` selects the single-threaded cache — `.kb/asdf.md`, dbi-deps).

## WASM divergence

The `#+rontolisp-wasm` entry points for `make-thread`/`join-thread`/`destroy-thread`
signal at CALL time — never run inline, which would turn a compile-time error into a
silently sequential program. `threadp` answers nil; `thread-alive-p` signals its ordinary
not-a-thread error. `bt:*supports-threads-p*` is `#+rontolisp-wasm nil`; a WASM clackup
runs `:use-thread nil`. The direct `rontolisp:make-thread` spelling is not compiled on
WASM at all — only the shim names exist. Re-evaluate if the WASM backends gain threads.

No `BuiltinFunctionWrappers` entries; `--no-gc` rejects the names.

## Tests

`ThreadTest` (join value/thread-death, EMARKER-free re-signal, bindings-in-thread-only
via `*standard-output*`, no-inherit pin, bt2 shim with clack-shaped `:initial-bindings`,
binding-form rejection, bt2 lock redirect); `JvmThreadTest` (same through the LoadInliner
splice, incl. `_dtl` runtime-name binding and the EMARKER join re-signal);
`WasmLispCompilerIntegrationTest#bt2ThreadEntryPointsSignalWhileThreadpAnswersNil`;
ci-spec `bt2-thread-api-portable-surface` (spawn/join excluded — output cannot be
identical across backends).
