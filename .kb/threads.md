# Threads (`rontolisp:make-thread` family) and the `bt2` shim package

`.todo/227`, part of the Clack milestone `.todo/223`. Five primitives —
`rontolisp:make-thread` (fn + optional bindings alist), `join-thread`, `threadp`,
`thread-alive-p`, `destroy-thread` — make user code a thread SPAWNER on the
interpreter and the JVM backend (before this, only the runtime spawned threads:
one virtual thread per served request). The driving consumer is clack's
`src/handler.lisp`, whose default `:use-thread t` runs the acceptor on
`bt2:make-thread` with `:initial-bindings` and stops it via `threadp` /
`thread-alive-p` / `destroy-thread`.

**The invariant: a thread handle is OPAQUE** (the mutex rule, `.kb/mutexes.md`):

| backend | `make-thread` returns | spawn mechanics |
|---|---|---|
| interpreter | a `LispThread` record (Thread + result `CompletableFuture`) | `AsyncRuntime.spawnThread` — kept in AsyncRuntime because that class is the ONLY LispEvaluator-reachable place touching threads, so the browser playground substitutes it wholesale (`Target_AsyncRuntime.spawnThread` runs the body synchronously — documented playground limitation, the async-run precedent) |
| JVM | `{TMARKER, Thread, FutureTask}` (marker-headed `Object[]`, the async stream-value pattern) | `JvmThreadRuntimeBuilder`: the generated class `implements Callable`, `_thread_spawn` news a runner instance and starts a virtual thread on a `FutureTask` over its `call()` |
| WASM (both) | none — no thread value can exist | the shim's `#+rontolisp-wasm` defuns SIGNAL at call time |

Only `threadp`'s answer and the join round-trip are portable; nothing may print,
order or compare a handle.

## Dynamic bindings in the spawned thread

`make-thread`'s optional second argument is an alist of `(symbol . VALUE)` pairs,
established as thread-scoped dynamic bindings in the NEW thread before the
function runs (never in the spawner; the spawned thread inherits NO bindings —
the plain-ThreadLocal rule of `.kb/dynamic-special-variables.md`, now pinned with
user code as the spawner). No unbind is needed anywhere: the bindings die with
the thread.

- Interpreter: `DynamicBindings.push` per pair inside the spawned body; the names
  need not be proclaimed special (the progv rule — `make-thread` sets
  `progvUsed`).
- JVM: the names arrive as runtime STRINGS, so `call()` resolves each through the
  generated `_dtl(name)` dispatch (String.equals chain over the bound-special
  set) to its `_d$` ThreadLocal and `_dbind`s the value. Two forcings in
  `JvmLispCompiler` make that dispatch total: when the program uses any thread
  primitive, (1) `*STANDARD-OUTPUT*`/`*STANDARD-INPUT*`/`*ERROR-OUTPUT*` are
  forced into `specialVars` (clack binds the stream specials through
  make-thread's alist, which the static `SpecialVarCollector` cannot see — this
  activates the redirect machinery exactly as a source-level binding would,
  `.kb/standard-output-redirect.md`), and (2) ALL specials are forced into
  `boundSpecialVars` so each has a ThreadLocal ("over-collection is only a small
  read cost"). A name that is not a special of the program is a clear runtime
  error, not a silent global write.
- A closure built INSIDE a dynamic-binding extent still reads its capture in the
  new thread (the dual-bind capture rule) — pass a `defun`'s `#'name` when the
  thread must see the global/dynamic value.

## Errors and join

`join-thread` yields the function's value, RE-SIGNALING an error the thread died
on so `handler-case` around the join dispatches by condition type. On the JVM
the condition object cannot ride the thread-local `_condTl` across threads, so
`call()` completes the FutureTask NORMALLY with the async runtime's
`{EMARKER, throwable, condition}` payload and `_thread_join` re-sets `_condTl`
on the joining thread before rethrowing — the `_await` pattern, which is why the
thread runtime `ensure`s the condition channel. Join also waits for the THREAD
itself to die (Thread.join after the value settles), so `thread-alive-p` answers
nil deterministically after a join. `destroy-thread` is `Thread.interrupt`: a
request, not a kill.

## The `:thread-support` feature (todo-228)

`Features.INTERPRETER` and `Features.JVM` include **`thread-support`**; the WASM
set does not. It is the ecosystem's portable spelling of "this image spawns
threads": upstream bordeaux-threads pushes it from its `.asd` at load time, and
a push can never reach a read-time conditional here (`.todo/181`), so the
backends whose threads are real declare it statically like `:unicode`. The
driving consumer is `clack:clackup`'s `#+thread-support t #-thread-support nil`
default for `:use-thread` — t on the interpreter/JVM, nil on WASM, exactly the
documented divergence (`.kb/clack.md`). Pinned by the ci-spec
`reader-features-variable` case (feature count 4 vs 3 per backend).

## The bt2 package and the shim

`bordeaux-threads.lisp` (ShimLibraries) now provides BOTH API namespaces of the
one built-in `bordeaux-threads` system. `BT2` is the canonical package name with
built-in nickname `BORDEAUX-THREADS-2`, mirroring upstream's `apiv2/pkgdcl.lisp`.
Cross-package IMPORT redirects (the closer-common-lisp / uiop-image precedent)
make one definition serve both spellings: the v1 package imports the thread
names + `*default-special-bindings*` from `bt2` (clack `:import-from`s them from
v1), and `bt2` imports the lock subset from v1 — so `bt2:with-lock-held`
resolves to `BORDEAUX-THREADS:WITH-LOCK-HELD` and hits the existing
`LispMacroExpander` qualified-name dispatch with NO new dispatcher entries.

`bt2:make-thread (function &key name initial-bindings trap-conditions)` maps
`:initial-bindings` — upstream: `(symbol . form)` pairs whose forms are
evaluated in the new thread — onto the primitive's `(symbol . value)` alist via
`bt2::resolve-binding-value`, which accepts `quote` forms and self-evaluating
values and SIGNALS on anything else (a symbol/compound form would need the new
thread's dynamic environment). Within that subset the value is
thread-independent, so evaluating in the spawner is UNOBSERVABLE — not a
divergence. clack's bindings are exactly `',bt2:*default-special-bindings*`
plus already-evaluated stream values. Re-evaluation trigger: if a real library
passes a variable-reference form, the resolver needs eval-in-the-new-thread
(the compiled backends have an eval runtime, `.kb/eval-runtime.md` — route it
there and gate `usesEval` on the thread primitives).

## WASM divergence (the why, and its trigger)

Both WASM backends are single-threaded by construction, so the shim's
`#+rontolisp-wasm` entry points for `make-thread`/`join-thread`/`destroy-thread`
signal at CALL time — never run inline, which would turn a compile-time error
into a silently sequential program. `threadp` answers nil (correct: no handle
can exist), and `thread-alive-p` signals its ordinary not-a-thread error
(identical behavior to the other backends for every wasm-reachable input).
`bt:*supports-threads-p*` stays per-backend (`#+rontolisp-wasm nil`). A WASM
clackup therefore runs `:use-thread nil` (the host owns the socket under
`wasmtime serve`). The direct `rontolisp:make-thread` spelling is not compiled
on WASM at all (undefined function at compile time) — only the shim names
exist there. Re-evaluate if the wasm backends gain real threads
(shared-memory-threads proposal).

Like the mutexes: no `BuiltinFunctionWrappers` entries — `#'rontolisp:make-thread`
is an error; `--no-gc` rejects the names like every `rontolisp:` built-in.

## Coverage

`ThreadTest` (interpreter: join value/thread-death, EMARKER-free re-signal,
bindings-in-thread-only via `*standard-output*`, no-inherit pin, bt2 shim with
clack-shaped `:initial-bindings`, binding-form rejection, bt2 lock redirect),
`JvmThreadTest` (the same shapes compiled through the LoadInliner splice, incl.
the `_dtl` runtime-name binding and the EMARKER join re-signal),
`WasmLispCompilerIntegrationTest#bt2ThreadEntryPointsSignalWhileThreadpAnswersNil`
(spliced with `Features.WASM` so the `#+rontolisp-wasm` defuns are the ones
compiled), and the `bt2-thread-api-portable-surface` ci-spec case (all four
backends; spawn/join deliberately excluded — their output cannot be identical
across backends).
