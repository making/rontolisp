# rontolisp:then

`(rontolisp:then future function)`

Attaches a transform to a future as a value: returns a **fresh** future that,
on the input's successful settlement, invokes `function` with the settled
value and settles to the function's return value. If `function` returns a
future, `await` on the returned future flattens it (users never observe
`future<future<T>>`). On upstream error the callback is skipped and the
condition propagates through the returned future unchanged.

Use it to compose asynchronous work when the future crosses a boundary as
a value -- the plain caller does not have to be an `rontolisp:async-defun`
just because its callee is:

```lisp
(rontolisp:async-defun some-future-producer () 21)
(defun caller ()
  (rontolisp:then (some-future-producer) (lambda (v) (* 2 v))))
(rontolisp:await (caller))   ; => 42
```

A non-future first argument is a `type-error` -- there is no JavaScript-style
auto-coercion to a resolved promise.

## Backend support

Supported on the interpreter, the JVM backend and WASM `--component`.
Preview 1 WASM supports the success-only shape (the degenerate synchronous
semantics of the async surface there: an errored body signals at the call
rather than at await, so the error-propagation contract falls back to the
enclosing `handler-case`). `--no-gc` rejects the whole async surface at
compile time.
