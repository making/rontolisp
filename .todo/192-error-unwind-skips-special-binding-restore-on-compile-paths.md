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

## Fourth hole: cross-lambda `return-from` (found 2026-08-08, via the todo-297 probes)

`Ctx.specialBindScopes` covers a `return`/`return-from` compiled as a direct
branch inside the binding function. A `return-from` that CROSSES a lambda
boundary is lowered to the block-exit throw/catch (the todo-166 machinery), and
that landing pad restores nothing either:

```lisp
(defvar *box* (make-array 0))
(defun mk (n)
  (lambda (s)
    (block scan
      (let ((*box* *box*))
        (when (plusp n) (setq *box* (make-array n :initial-element nil)))
        (funcall (lambda () (when (string= s "miss") (return-from scan nil))))
        (values 1 *box*)))))
(defparameter *c1* (mk 1))
(defparameter *c2* (mk 0))
(print (multiple-value-list (funcall *c1* "hit")))   ; (1 #(NIL)) everywhere
(print (multiple-value-list (funcall *c1* "miss")))  ; inner lambda throws the block exit -> restore skipped
(print (multiple-value-list (funcall *c2* "hit")))   ; interpreter (1 #()); JVM + both wasm-GC (1 #(NIL))
```

This is not a corner case -- it is cl-ppcre's scanner shape verbatim: the scan
closure self-shadows `(*reg-starts* *reg-starts*)` (and the other four match
specials) and allocates fresh arrays only `(when (plusp reg-num))`; every
`do-scans` loop (`all-matches`, `regex-replace-all`, `split`, ...) ends on a
failing scan whose exit crosses the `advance-fn`/`match-fn` lambdas. After one
such loop over a REGISTER regex, any later zero-register scan returns the
leaked arrays. Measured on 2026-08-08:

```lisp
(ql:quickload "cl-ppcre")
(defparameter *r* "(a)b")
(ppcre:all-matches *r* "ab")
(print (multiple-value-list (ppcre:scan (ppcre:create-scanner "a+b") "xaab")))
```

interpreter `(1 4 #() #())`; JVM and wasm-GC `(1 4 #(NIL) #(NIL))`.
`ClPpcreE2eTest` passes today only by case order: its one register-exhausting
loop (`split "(,)" ... :with-registers-p t`) is the final case.

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
- **The fourth hole above invalidates the catch-site-restore half of this
  sketch for any throw that crossed a FRAME**: the save slots live in the
  thrower's dead frames, so the catcher cannot reach them. The mechanism that
  covers all four holes at once is a dynamic-binding SAVE STACK (thread-local
  on the JVM like `_d$*`, a module global on wasm): bind pushes
  `{cell, old value}`, normal exit pops, and every landing pad (handler-case,
  unwind-protect catch-any, block-exit catch, trampoline hop) truncates to the
  depth it captured at region entry. On wasm the truncation needs a generated
  per-module dispatch function (a global cannot be `global.set` by runtime
  index), switching on a special's index to restore its global.

Related: `.kb/dynamic-special-variables.md` (limitation 2 lists all four
holes), `.kb/error-handling.md`.
