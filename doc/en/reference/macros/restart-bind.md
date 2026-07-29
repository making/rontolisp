# restart-bind

`(restart-bind ((restart-name function [:report-function r]...)...) body...)`

The primitive sibling of [`restart-case`](restart-case.md): evaluates `body...` with one restart record per binding on the dynamic restart stack, but an invoked restart **calls `function` at the invocation point** — there is no non-local transfer back to the `restart-bind` frame, and `invoke-restart` returns whatever the function returns (the CL semantics; the function must transfer control itself if it wants to). The function receives the invocation arguments (at most 7 on the WASM backends). `:report-function` is accepted and stored; other per-binding keyword options are accepted and ignored. Supported on every backend except `--no-gc`.

```lisp
(let ((hit nil))
  (restart-bind ((poke (lambda (v) (setq hit v))))
    (invoke-restart 'poke 9)
    hit)) ; => 9
```
