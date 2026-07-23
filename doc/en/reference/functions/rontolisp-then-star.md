# rontolisp:then*

`(rontolisp:then* future &rest functions)`

Variadic chain sugar for `rontolisp:then`: threads the value through each
`function` in order without the parenthesis nesting a manual chain would
require. Each function receives the previous stage's (auto-flattened via
`await`) settled value; a stage returning a future is flattened on the next
stage's read. With no callbacks the operator returns the input future
unchanged (documented degenerate identity).

```lisp
(rontolisp:async-defun produce () 40)
(rontolisp:await (rontolisp:then* (produce) #'1+ #'1+))   ; => 42
```

A non-future first argument is a `type-error`.

Note on the name: this is `then` + `*` (the CL convention for a variadic
sibling of a two-argument operator), not `thenCompose`/`thenApply` from
Java's `CompletableFuture`; because `await` flattens on read, the "compose"
and "apply" distinctions collapse into the same shape here.

## Backend support

Same as [`rontolisp:then`](rontolisp-then.md): interpreter, JVM, WASM
`--component`, and the success-only shape on Preview 1 WASM. `--no-gc`
rejects at compile time.
