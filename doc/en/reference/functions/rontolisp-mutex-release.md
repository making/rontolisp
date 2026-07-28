# rontolisp:mutex-release

`(rontolisp:mutex-release mutex)`

Releases one acquisition of `mutex` and returns it. Releasing a mutex the calling thread
does not hold is an error on the interpreter and the JVM backend (and unnoticed on WASM,
where the primitives are no-ops). Because the lock is reentrant, a thread that acquired it
twice must release it twice before another thread can take it.

[`rontolisp:with-mutex`](../macros/rontolisp-with-mutex.md) pairs the acquire and the
release for you, including on a non-local exit; reach for the bare primitives only when the
two cannot sit in one lexical block.

```lisp
(let ((m (rontolisp:make-mutex)))
  (eq (rontolisp:mutex-release (rontolisp:mutex-acquire m)) m))  ; => T
```

## Limitations

- A value that is not a mutex handle is an error.
- On the WASM backends nothing is checked: releasing an unheld lock is silently accepted.
