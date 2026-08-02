# rontolisp:make-thread

`(rontolisp:make-thread function &optional bindings)`

Spawns a new (virtual) thread running the zero-argument `function` and returns an
**opaque thread handle** immediately. Pass the handle to
[`rontolisp:join-thread`](rontolisp-join-thread.md),
[`rontolisp:thread-alive-p`](rontolisp-thread-alive-p.md) or
[`rontolisp:destroy-thread`](rontolisp-destroy-thread.md), and test for one with
[`rontolisp:threadp`](rontolisp-threadp.md); like a mutex handle, what it actually is
differs per backend, so printing or ordering one is not portable.

`bindings` is an alist of `(symbol . value)` pairs, each established as a thread-scoped
dynamic binding in the **new** thread before `function` runs. The spawned thread inherits
no dynamic bindings from its spawner: without an entry here it reads every special
variable's global value. Binding `*standard-output*` this way routes the new thread's
print family into a stream of your choice — the shape the `bordeaux-threads`/`bt2`
libraries (and Clack's handler) use.

```lisp
(defvar *cap* (make-string-output-stream))
(rontolisp:join-thread
 (rontolisp:make-thread (lambda () (princ "from the thread"))
                        (list (cons '*standard-output* *cap*))))
(get-output-stream-string *cap*) ; => "from the thread"
```

Threads are real on the interpreter and the JVM backend. Both WASM backends are
single-threaded by construction and do not compile this function; the
`bordeaux-threads`/`bt2` shim's `make-thread` signals a clear error there at call time.

## Limitations

- WASM: not available (see above) — a Clack app there runs with `:use-thread nil`.
- The bindings' values are used as given; unlike upstream `bordeaux-threads`, there is no
  form evaluation in the new thread (the `bt2:make-thread` shim accepts `quote` forms and
  self-evaluating values in `:initial-bindings` and signals on anything else).
- These primitives have no function value: `#'rontolisp:make-thread` is an error.
