# rontolisp:threadp

`(rontolisp:threadp value)`

Returns `t` when `value` is a thread handle (as returned by
[`rontolisp:make-thread`](rontolisp-make-thread.md)), else `nil`. This is the one
thread operation that accepts any value.

```lisp
(list (rontolisp:threadp (rontolisp:make-thread (lambda () 1)))
      (rontolisp:threadp 42)) ; => (T NIL)
```

On the WASM backends no thread handle can exist, so the `bt2:threadp` shim constantly
answers `nil` there — which is how Clack's `stop` takes its non-threaded branch.
