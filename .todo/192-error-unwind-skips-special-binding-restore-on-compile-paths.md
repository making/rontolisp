# 192 - Error unwind skips the special-binding restore on the compile paths

A special `let` restores its dynamic binding on normal exit and on
`return`/`return-from` (`Ctx.specialBindScopes`), but NOT when an error thrown
inside the body is caught by a `handler-case` OUTSIDE the `let`. The
interpreter's `finally` restores on every exit.

## Reproduction (measured 2026-07-27, JVM class)

```lisp
(defvar *x* 1)
(print (handler-case (let ((*x* 2)) (error "boom")) (error (e) *x*)))
(print *x*)
```

interpreter: `1` / `1`. JVM: `2` / `2` -- the handler still sees the binding
and it stays leaked after the handler returns. Same shape expected on both WASM
backends (the EH trampoline knows nothing of the save slots,
`.kb/dynamic-special-variables.md` limitation 2).

Since the JVM thread-scoping (`.todo/189` work), the leak lands in the
THREAD's dynamic store rather than the process global, so a per-request virtual
thread takes it to the grave; long-lived threads (main, a thread pool) keep the
stale binding.

## Sketch

- JVM: `JvmHandlerCaseCompiler` (and `JvmUnwindProtectCompiler`'s catch-any
  path) would restore every `specialBindScopes` entry pushed inside the
  protected region before running the handler/cleanup -- the entries carry
  `{tlField, saveSlot, blockDepth}` already; what is missing is a
  region-entry-depth marker analogous to the unwind scopes' `blockDepth`.
- WASM: the trampoline cascade needs the same, or the special save/restore has
  to move into the EH-mode landing pads.
- The `go`-across-a-special-`let` hole and the WASM trampolined plain-`return`
  hole (both pre-existing, same kb list) belong to the same mechanism if one is
  built.

Related: `.kb/dynamic-special-variables.md` (limitation 2 lists all three
holes), `.kb/error-handling.md`.
