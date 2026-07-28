# A cross-backend mutex primitive, and the `:postmodern-thread-safe` flip it unblocks

Split out of `.todo/201` (§2), which landed the postmodern `.asd` override with
`:postmodern-thread-safe` deliberately **OFF**. This item is what turning it ON
requires. Nothing here is postmodern-specific.

## Why it matters

rontolisp really runs concurrent code: `serve` / `http-handler` put one virtual
thread per request on the interpreter and the JVM, and `.todo/190`/`.todo/193`
are both concurrent-connection bugs. But there is no way for a program -- or a
library -- to take a lock. Postmodern is only the first library to ask: its
three locks guard the connection pool (`connect.lisp`), the prepared-statement
id counter (`prepare.lisp`) and class finalization (`query.lisp`), all of which
a two-request race corrupts today. With the feature off, the lock sites take
their `#-postmodern-thread-safe` branch and compile to `(progn ...)` -- three
in the non-MOP build, five more in `table.lisp` once `.todo/203` lands:
correct single-threaded, racy concurrent.

Declaring `:postmodern-thread-safe` without a real lock underneath would be
worse than leaving it off -- it would claim serialization and deliver none.

## Scope

1. **A mutex primitive on all four backends.** Interpreter + JVM: a real lock
   (a `ReentrantLock` behind an opaque handle, the way a socket handle is
   already carried on both). WASM Preview 1 / component: single-threaded by
   construction, so acquire/release are no-ops and the handle can be any value
   -- but they must EXIST, because an undefined function is a compile-time
   error on the compile backends, not a call-time one.
   - `java:` monitors are NOT an option: `java:` interop needs reflection
     metadata the native image lacks, so a shim written on it would work under
     `java -jar` and die on the native binary.
   - Shape to decide: `make-mutex` + `call-with-mutex` (function taking a
     thunk, so `with-...` is an ordinary macro) vs. `acquire`/`release` +
     an `unwind-protect` lowering in `LispMacroExpander`. The second keeps the
     per-backend surface to plain built-in functions.
2. **A `bordeaux-threads` shim system** (Tier 1, like `usocket` --
   `eval/ShimLibraries` + `BuiltinSystems` + a `PackageRegistry` package with
   the `bt` nickname -- upstream's `apiv1/pkgdcl.lisp` gives it exactly that,
   and `bt2` is a separate v2 package nothing here uses; upstream's own `.asd`
   hard-errors on unknown implementations, so a shim is the only route).
   postmodern calls exactly `bt:make-lock` /
   `bordeaux-threads:with-lock-held` (both spellings appear) and never spawns
   a thread, so the shim covers `make-lock`,
   `with-lock-held`, `acquire-lock`, `release-lock` and
   `*supports-threads-p*`. Thread creation stays out until something asks.
3. **Flip `:postmodern-thread-safe` on.** This needs a way to make a reader
   feature true for a system's component files, which rontolisp does not have:
   upstream pushes the feature from an `eval-when` in its `.asd`, and a
   `*features*` push is invisible to the reader (`.todo/181`). The natural
   home is a declaration in the replacement `.asd` -- e.g. a
   `:rontolisp-features (...)` defsystem option parsed by `AsdfSystems` into
   `LispSystem`, with `Features.with(...)` threaded through the interpreter's
   `loadSystem`/`loadFile` and the compile path's `spliceSystem`/`spliceFile`
   (`Ctx.features` is already there, it just never varies per system). That
   mechanism is general: it is the static encoding of every `.asd` that pushes
   features from an `eval-when`, and `.todo/203`'s MOP build needs the same
   switch for `:postmodern-use-mop`.
4. Update the header comment of
   `src/main/resources/am/ik/rontolisp/eval/postmodern-deps.asd` (it records
   this decision and points here) and the postmodern paragraph in
   `.kb/asdf.md`.

## Acceptance

A test that two concurrent virtual threads incrementing a shared counter under
the lock agree with the sequential result on the interpreter and the JVM, and
that the same program compiles and runs on both WASM backends. Plus the
postmodern connection pool exercised concurrently once `.todo/202` lands.
