# rontolisp:make-mutex

`(rontolisp:make-mutex)`

Returns a fresh mutual-exclusion lock, as an **opaque handle**. Pass it to
[`rontolisp:with-mutex`](../macros/rontolisp-with-mutex.md) (or to
[`rontolisp:mutex-acquire`](rontolisp-mutex-acquire.md) /
[`rontolisp:mutex-release`](rontolisp-mutex-release.md)) and to nothing else: what the
handle actually is differs per backend, so printing one, comparing two with `<`, or doing
arithmetic on it is not portable. Comparing a handle with `eq`/`eql` to itself does work.

rontolisp really runs concurrent code — [`rontolisp:http-handler`](rontolisp-http-handler.md)
puts one virtual thread per request on the interpreter and the JVM backend — which is what
a lock is for. On both WASM backends there is only ever one thread, so the primitives are
no-ops there; the same source runs everywhere.

```lisp
(let ((m (rontolisp:make-mutex)))
  (rontolisp:with-mutex (m) :guarded))  ; => :GUARDED
```

The lock is **reentrant**: the thread holding it may acquire it again, and must release it
as many times as it acquired it.

## Limitations

- There is no way to spawn a thread from Lisp; the concurrency comes from the runtime
  (one virtual thread per served request).
- The handle is opaque and backend-dependent — do not print or order it.
- Macros and these primitives have no function value: `#'rontolisp:make-mutex` is an error.
