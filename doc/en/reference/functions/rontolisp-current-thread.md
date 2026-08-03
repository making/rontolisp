# rontolisp:current-thread

`(rontolisp:current-thread)`

Returns the calling thread's own **opaque thread handle**. It works for any thread —
the main thread and served requests included, not only
[`rontolisp:make-thread`](rontolisp-make-thread.md) spawns — and it is `eq`-stable:
repeated calls from one thread return the same handle, so it can key an `eq` hash
table. That property is what the `bt2:current-thread` shim (and through it `dbi`'s
per-thread connection cache) relies on.

```lisp
(let ((h (rontolisp:current-thread)))
  (list (rontolisp:threadp h)
        (eq h (rontolisp:current-thread))
        (rontolisp:thread-alive-p h))) ; => (T T T)
```

Threads are real on the interpreter and the JVM backend. Both WASM backends are
single-threaded by construction and do not compile this function; the
`bordeaux-threads`/`bt2` shim's `current-thread` signals a clear error there at call
time.

## Limitations

- The handle a spawned function sees for itself is its own cached one, not the handle
  its spawner got from `make-thread` — only the
  [`rontolisp:threadp`](rontolisp-threadp.md) /
  [`rontolisp:thread-alive-p`](rontolisp-thread-alive-p.md) answers are portable on a
  handle either way.
- Passing your own handle to [`rontolisp:join-thread`](rontolisp-join-thread.md)
  blocks forever (joining yourself does upstream too).
- These primitives have no function value: `#'rontolisp:current-thread` is an error.
