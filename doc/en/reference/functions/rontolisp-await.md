# rontolisp:await

`(rontolisp:await promise)`

Blocks until the promise returned by [`rontolisp:fetch`](rontolisp-fetch.md)
settles, and returns the response property list
`(:status <integer> :body <string> :headers <alist>)`, where `:headers` is an
alist of `(name . value)` response-header pairs.

```lisp
(let ((p (rontolisp:fetch "https://httpbin.org/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

A settled promise can be awaited more than once, and promises can be awaited in
any order — each `await` returns the result of the request its promise belongs
to:

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))
  (print (getf (rontolisp:await p2) :status))
  (print (getf (rontolisp:await p1) :status)))
```

## Errors

A request failure (for example a refused connection) surfaces here — the same
timing as a JavaScript `await` rejection, not at `fetch` time:

- **Interpreter / JVM**: `await` raises an error describing the failure.
- **WASM**: `await` returns `nil` (the nil-on-failure convention of that
  backend). A `nil` promise (a fetch that could not be started) also awaits to
  `nil`.

Passing a value that is not a promise is an error in the interpreter; the
compiled backends treat the promise as an opaque handle and do not check it.

## Backend support

Same as [`rontolisp:fetch`](rontolisp-fetch.md): interpreter, JVM, and WASM in
`--component` mode (run with `-S http=y` plus the async flags); a compile error
in WASM Preview 1 mode. In the browser playground the promise is already
settled when `fetch` returns, so `await` simply unwraps it.
