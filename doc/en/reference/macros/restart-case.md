# restart-case

`(restart-case form (restart-name (arg...) [:report r] [:interactive i] [:test t] body...)...)`

Evaluates `form` with one **restart** established per clause for its dynamic extent. Nothing happens on normal completion — the form's values are returned and the restarts are disestablished. When code running inside the form (typically a [`handler-bind`](handler-bind.md) handler, running at the signal point) invokes one of the restarts with [`invoke-restart`](../functions/invoke-restart.md), control unwinds back to the `restart-case` (running intervening `unwind-protect` cleanups) and the clause body runs **in the restart-case's own lexical environment** with the invocation arguments bound to `arg...` — so a clause body can `return-from` an enclosing function or `go` to a tag of an enclosing `tagbody` (the retry-loop idiom). The restart name may be a symbol or a keyword; [`find-restart`](../functions/find-restart.md) returns the innermost active restart as a first-class object and [`compute-restarts`](../functions/compute-restarts.md) lists them all. The `:report`, `:interactive` and `:test` options are accepted and stored in the restart record (nothing in rontolisp renders reports or invokes restarts interactively — there is no debugger).

Supported on every backend except `--no-gc`, which keeps the historical lowering to the primary form. A restart-system program compiles in EH mode on the wasm-GC backends (`wasmtime -W exceptions=y`). Lite deviations: `&optional` clause parameters take `nil` instead of their default when not supplied, restarts are not associated with conditions (the optional condition argument of `find-restart` is ignored), and a restart object prints as a plain list rather than `#<RESTART ...>`.

```lisp
(restart-case (+ 1 2)
  (continue () 99)) ; => 3
```

A handler invokes the restart by keyword name, with arguments; the clause body's value becomes the value of the whole form:

```lisp
(handler-bind ((error (lambda (c) (invoke-restart :reconnect "db-1"))))
  (restart-case (error "connection lost")
    (:reconnect (host) (list :reconnected host)))) ; => (:RECONNECTED "db-1")
```

The retry idiom — a clause body `go`ing back into an enclosing `tagbody`:

```lisp
(let ((n 0))
  (handler-bind ((error (lambda (c) (invoke-restart 'retry))))
    (tagbody start
      (restart-case
          (progn (setq n (+ n 1)) (when (< n 3) (error "again")))
        (retry () (go start)))))
  n) ; => 3
```
