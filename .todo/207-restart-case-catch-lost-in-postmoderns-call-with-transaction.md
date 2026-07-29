# A `restart-case`'s catch frame is gone by the time `invoke-restart` fires, in postmodern's `call-with-transaction`

Found 2026-07-29 landing `.todo/202`. `pomo:retry-transaction` works on the
INTERPRETER and dies on both compile backends (JVM and the WASM component --
so the bug is in the shared AST lowering, not in codegen):

```
Exception in thread "main" java.lang.RuntimeException: THROW: no enclosing catch for the tag
	at Rst3._lambda_2299(Unknown Source)      <- the restart record's INVOKER
	at Rst3._invoke_1$6(Unknown Source)
	at Rst3._invoke_1(Unknown Source)
	at Rst3.INVOKE-RESTART(Unknown Source)
	at Rst3.POSTMODERN$colonRETRY-TRANSACTION(Unknown Source)
	at Rst3._lambda_2457(Unknown Source)      <- the with-transaction body
	at Rst3.POSTMODERN$colon$colonCALL-WITH-TRANSACTION(Unknown Source)
	at Rst3._top$2(Unknown Source)
```

`find-restart` FINDS the restart (an absent one signals postmodern's own "no
such restart is active" error instead), and the clause body is compiled inline
as `.kb/error-handling.md` requires -- so what fails is the transfer itself:
the invoker throws the restart-case's fresh-cons tag and no `catch` for it is
on the stack, even though `CALL-WITH-TRANSACTION` -- the function whose
`restart-case` established it -- is right there in the frame list.

## Reproduction (needs a live PostgreSQL; ~20 s)

```lisp
(ql:quickload "postmodern")
(pomo:with-connection '("mydb" "myuser" "mypass" "127.0.0.1" :port 5432)
  (let ((tries 0))
    (pomo:with-transaction ()
      (incf tries)
      (when (< tries 2) (pomo:retry-transaction)))
    (print (list :tries tries))))
```

Interpreter prints `(:TRIES 2)`. `-o Probe.class` + `java Probe` throws;
`--component` + `wasmtime run -W gc=y -W exceptions=y -S tcp=y
-S inherit-network=y` throws the same way. No query inside the transaction body
is needed, so the inner `restart-case` of cl-postgres' `exec-query` is not
involved.

## What has been ruled out

A faithful hand-written copy of `postmodern/transaction.lisp`'s knot in
`cl-user` -- `(tagbody start (restart-case (let ((h ...)) (execute ...)
(unwind-protect (return-from f (multiple-value-prog1 (let ((*a* ...) (*b* ...))
(funcall body h)) (commit ...))) (abort ...))) (retry () :report "..."
(go start))))`, with `retry` invoked through a separate defun that does
`(invoke-restart (find-restart 'retry ...))`, a `with-transaction`-shaped macro
around it, a CLOS `transaction-handle` with `defmethod` commit/abort, an
`&optional (isolation-level *isolation-level*)` parameter, and a nested
`restart-case` inside the `execute` -- **compiles and runs correctly on the JVM**,
before and after `(ql:quickload "postmodern")`. So the shape alone does not
reproduce it; something about the LIBRARY's own compilation of that function
does. Candidates not yet eliminated: the `LibraryDefunPruner` /
`%begin-system` provenance path a spliced system takes, and the exception-table
range the JVM emits for a `catch` region that a lexical `return-from` jumps out
of (the WASM component failing the same way argues against a JVM-only
range bug, and for the shared lowering).

## Why it did not block `.todo/202`

The milestone program does not retry. `PostmodernE2eTest` runs the reconnect
half of its restarts exercise on all three backends and the
`retry-transaction` half on the interpreter only, naming this file; widen it
back to all three when this lands. `.todo/196`'s own restart pins are
unaffected -- this is a shape none of them has.

## Verification

- The reproduction above green on the interpreter, the JVM and the component.
- A socket-free minimal case (once the trigger is isolated) in `ci-spec.yaml`
  and in the per-backend compiler tests, so the shape is pinned without a
  database.
- `PostmodernE2eTest.retryTransactionOn{Jvm,WasmComponent}` restored.
