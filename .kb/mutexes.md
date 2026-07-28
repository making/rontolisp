# Mutexes (`rontolisp:make-mutex` / `with-mutex`) and the `bordeaux-threads` shim

`.todo/204`. rontolisp really runs concurrent code -- `serve` / `http-handler`
put **one virtual thread per request** on the interpreter and the JVM -- so a
program (or a loaded library) needs a way to take a lock. These are it.

**The invariant: a mutex handle is OPAQUE.** Every backend hands out a different
kind of value, and nothing portable may print, order or do arithmetic on one:

| backend | `make-mutex` returns | acquire / release |
|---|---|---|
| interpreter | an integer index into the global environment's lock table (`Environment.registerMutexes`, a `ConcurrentHashMap<Long, ReentrantLock>` + `AtomicLong`) | real `lock()` / `unlock()` |
| JVM | the `java.util.concurrent.locks.ReentrantLock` **itself**, flowing as an ordinary `Object` (`JvmMutexRuntimeBuilder`: `_mutexNew`/`_mutexAcquire`/`_mutexRelease`) | real `lock()` / `unlock()` |
| WASM Preview 1 + component | the i31 constant `0` (`WasmMutexCompiler`) | the identity on the argument |

Only the identity through acquire/release is portable, and it is what the
`mutex-primitives` ci-spec case asserts. The JVM handle is the lock object
rather than an index **on purpose**: an index needs a table, a table needs a
lazily initialized static field, and that initialization would itself race --
which is the bug the primitive exists to fix. `java:` monitors were never an
option (no reflection metadata on the native binary), and a `synchronized`
region cannot express acquire / body / release as three separate calls anyway.

**Why they exist on WASM at all.** Both WASM backends are single-threaded by
construction, so exclusion there is a tautology, not a lie. They must still
EXIST because an undefined function is a **compile-time** error on the compile
backends and only a call-time one on the interpreter: a library that takes a
lock on a path a WASM program never runs would otherwise not build. Same reason
the three SCRAM names had to land before cl-postgres could be compiled at all
(`.kb/asdf.md`).

**`with-mutex` is a `LispMacroExpander` expansion** dispatched on the qualified
name (`LispMacroExpander.expandWithMutex`, the `rontolisp:with-arena` pattern),
wired into `LispEvaluator.evalCons`, `JvmExprCompiler` and `WasmExprCompiler`.
It binds the mutex form ONCE to `__mutex_lock`, acquires, and releases under
`unwind-protect`. The WASM leg passes `unwindProtect = ctx.ehMode` like the
`usocket:with-*` family -- but unlike them **`with-mutex` does NOT flip a module
into EH mode** (it is absent from `WasmLispCompiler.programUsesEhForm`): paying
the EH lowering to guarantee that a no-op runs would buy nothing. It is also in
the non-strict head list of `WasmAwaitNormalizer` (it takes a lock SPEC and a
body, so its "arguments" must not be hoisted).

Reentrancy is deliberate (`ReentrantLock`, and the nested-acquire case is
pinned). Releasing a mutex the calling thread does not hold is an error --
`LispEvalException` on the interpreter, `IllegalMonitorStateException` from
`unlock()` on the JVM, unnoticed on WASM.

Thread CREATION stays out: no backend can spawn a thread from Lisp, and a shim
that pretended otherwise would turn a compile-time error into a silently
sequential program.

**`bordeaux-threads` (nickname `bt`)** is a Tier-1 shim system
(`ShimLibraries` + `BuiltinSystems` + a `PackageRegistry` package) covering
`make-lock`, `acquire-lock`, `release-lock`, `with-lock-held` and
`*supports-threads-p*` -- upstream's own `.asd` hard-errors on an unknown
implementation, so a shim is the only route. `with-lock-held` is NOT a shim
defun but the same built-in expansion as `with-mutex` (dispatched on its
qualified name in all three dispatchers), so one lowering serves every backend.
Two deliberate divergences, both supersets: `make-lock` returns a REENTRANT lock
(upstream's is not -- a program that would deadlock there merely proceeds here),
and `acquire-lock`'s `:wait-p` is accepted and ignored (the acquisition always
blocks).

`bt:*supports-threads-p*` is per-backend, and getting it there is why
**`ShimLibraries.forms` now takes the TARGET backend's `Features`** instead of
hardcoding `Features.INTERPRETER`: a `#+rontolisp-wasm` in a shim source now
says what it means on the backend being built for. It is a claim libraries act
on, not decoration -- same category as `:unicode` (`.kb/reader-features.md`).

Coverage: `MutexTest` (interpreter semantics + 4 virtual threads x 2000
increments), `JvmMutexTest` (the same property against the compiled class,
invoked from Java threads the way `serve` does), the
`mutexPrimitivesAreNoOpsThatStillCompose` case in
`WasmLispCompilerIntegrationTest`, and the `mutex-primitives` ci-spec case
(all four backends). The JVM test is the one that measures: with the
`with-mutex` dropped it reports ~3200 of 8000 increments, so the lock is doing
real work and the test is not vacuous.

Not wired: `--no-gc` (rejects the names like the other `rontolisp:` built-ins),
and no `BuiltinFunctionWrappers` entry -- `#'rontolisp:make-mutex` is an error,
like `#'rontolisp:tcp-connect`.
