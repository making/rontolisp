# rontolisp:mutex-acquire

`(rontolisp:mutex-acquire mutex)`

Blocks until the calling thread holds `mutex` (created by
[`rontolisp:make-mutex`](rontolisp-make-mutex.md)), then returns the mutex. Prefer
[`rontolisp:with-mutex`](../macros/rontolisp-with-mutex.md), which releases the lock even
when the body exits by signalling an error; a bare `mutex-acquire` whose matching
[`rontolisp:mutex-release`](rontolisp-mutex-release.md) is skipped leaves the lock held
forever.

The lock is reentrant, so a thread that already holds it acquires it again immediately and
must release it once per acquisition. On both WASM backends there is only one thread, so
this is a no-op that returns its argument.

```lisp
(let ((m (rontolisp:make-mutex)))
  (rontolisp:mutex-acquire m)
  (unwind-protect :critical
    (rontolisp:mutex-release m)))  ; => :CRITICAL
```

## Limitations

- There is no non-blocking or timed acquisition.
- A value that is not a mutex handle is an error.
