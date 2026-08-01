# bordeaux-threads: bt2 package + real thread creation

Difficulty: 中〜高 (the shim edit is easy; the real work is deciding and
pinning the interpreter/JVM thread-spawn semantics — the evaluator already
survives one-virtual-thread-per-request concurrency, but `make-thread` makes
user code the spawner, and JVM special rebinding across threads must be
re-verified under todo-189's bound-only ThreadLocal hybrid)

Part of the Clack milestone `.todo/223`.

## Why

`clack/src/handler.lisp` uses the v0.9 "bt2" API namespace directly:
`bt2:*default-special-bindings*`, `bt2:make-thread` (with
`:initial-bindings`), `bt2:threadp`, `bt2:thread-alive-p`,
`bt2:destroy-thread`, and `(:import-from :bordeaux-threads :threadp
:make-thread :thread-alive-p :destroy-thread)`. The current shim is the v1
LOCKING subset only (deliberately — see the shim header) with nickname `bt`;
there is no `bt2` package at all, so loading clack's handler.lisp dies at read
time ("No such package: BT2" — spike).

clackup's DEFAULT is `:use-thread t`: run the acceptor on a background thread
and return a handler struct so `(clack:stop handler)` works. The spike ran with
`:use-thread nil` (blocking) — fine for a first cut, but the default shape is
the real Clack developer experience (REPL keeps running).

## Work

1. Register a `bt2` package (real bordeaux-threads v0.9 exposes the modern API
   from package `bordeaux-threads-2`, nicknames `bt2`; mirror that: one package,
   both spellings) exporting at least: `*default-special-bindings*`,
   `make-thread`, `join-thread`, `threadp`, `thread-alive-p`, `destroy-thread`,
   plus re-homing the existing lock subset so `bt2:with-lock-held` also works.
   Widen the v1 `bordeaux-threads` package with the same thread names
   (clack's `:import-from` needs them exported there too).
2. A real thread primitive on the interpreter and the JVM:
   `rontolisp:make-thread` (or shim-internal) spawning a virtual thread running
   a zero-arg Lisp function; handle = opaque (mutex-handle precedent,
   `.kb/mutexes.md`). `join-thread`, `thread-alive-p`, `destroy-thread`
   (interrupt), `threadp` over the handle. `:initial-bindings` /
   `*default-special-bindings*`: bind the given specials around the body — on
   the JVM this NEEDS the todo-189 `_d$` ThreadLocal machinery to be correct;
   verify with a test that rebinds `*standard-output*` in the spawned thread
   (exactly what clack.handler:run does).
3. WASM backends: single-threaded by construction. Keep
   `bt:*supports-threads-p*` nil there; `make-thread` = call-time error, so a
   WASM clackup must pass `:use-thread nil` (document in the guide). Do NOT
   silently run inline — the shim header's own rule.
4. Revisit the shim header's "thread creation is deliberately absent" clause —
   the reason no longer holds once a spawn primitive exists; retire the
   divergence in the same pass (working principle: leave no stale "why").

## Test

- Deterministic thread test via the java:-interop trick: `Thread.start`/`join`
  through `java:` interop makes the interleaving explicit (the todo-189 JVM
  special-thread-scoping tests use exactly this).
- clack-shaped case: make-thread + initial-bindings rebinding
  `*standard-output*`, join, output lands in the right stream, on interpreter
  and JVM.
- `ClackE2eTest` (`.todo/228`) drives the default `:use-thread t` path:
  clackup returns, curl answers, `(clack:stop handler)` stops the acceptor.
