# rontolisp:then

`(rontolisp:then value fn)`

Derives a new promise from `value` (usually a promise) and a callback, modeled
on JavaScript's `Promise.prototype.then`: awaiting the derived promise awaits
`value` and applies `fn` (a one-argument function) to the result. `then` always
returns a promise, so calls chain; a callback that itself returns a promise is
flattened, like JavaScript. A plain (non-promise) `value` is allowed and is
passed to the callback as-is.

```lisp
(rontolisp:await
 (rontolisp:then (rontolisp:then 10 (lambda (x) (+ x 1)))
                 (lambda (x) (* x 3))))   ; => 33
```

The typical use is transforming a [`rontolisp:fetch`](rontolisp-fetch.md)
response:

```console
(print (rontolisp:await
        (rontolisp:then (rontolisp:fetch "https://httpbin.org/get")
                        (lambda (r) (getf r :status)))))   ; 200
```

## Timing

The callback runs lazily, when the derived promise is first
[awaited](rontolisp-await.md), and the result is memoized so the callback runs
at most once however often the promise is awaited. This at-await timing is
identical on all three backends (the WASM backend has no event loop that could
run callbacks at settlement time); a chain that is never awaited never runs its
callback.

```lisp
(setq cnt 0)
(let ((p (rontolisp:then 1 (lambda (x) (setq cnt (+ cnt 1)) x))))
  (rontolisp:await p)
  (rontolisp:await p)
  cnt)   ; => 1
```

## Errors

A failed base promise (for example a refused connection) skips the callback:
on the interpreter and JVM the failure signals from `await`; on WASM the failed
fetch resolves to `nil`, which the callback receives (see the error notes on
[`rontolisp:await`](rontolisp-await.md)). There is no error-callback
(`onRejected`) parameter.

## Backend support

Works on every backend and in every WASM mode (Preview 1 included), like
[`rontolisp:await`](rontolisp-await.md) and
[`rontolisp:promisep`](rontolisp-promisep.md).
