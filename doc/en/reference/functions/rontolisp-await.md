# rontolisp:await

`(rontolisp:await value)`

Resolves a promise: blocks until the promise settles and returns its value. A
value that is not a promise is returned unchanged, like a JavaScript `await` on
a non-promise, so `await` can be applied uniformly to a value that may or may
not be a promise.

```lisp
(rontolisp:await 42)                                          ; => 42
(rontolisp:await (rontolisp:then 20 (lambda (x) (+ x 1))))    ; => 21
```

For a [`rontolisp:fetch`](rontolisp-fetch.md) promise the settled value is the
response property list
`(:status <integer> :body <string> :headers <alist>)`, where `:headers` is an
alist of `(name . value)` response-header pairs:

```console
(let ((p (rontolisp:fetch "https://httpbin.org/get")))
  (getf (rontolisp:await p) :status))   ; 200
```

A settled promise can be awaited more than once (the result is memoized, so a
[`rontolisp:then`](rontolisp-then.md) callback runs at most once), and promises
can be awaited in any order — each `await` returns the result of the request
its promise belongs to:

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))
  (print (getf (rontolisp:await p2) :status))
  (print (getf (rontolisp:await p1) :status)))
```

## Errors

A request failure (for example a refused connection) surfaces here — the same
timing as a JavaScript `await` rejection, not at `fetch` time — and skips any
[`rontolisp:then`](rontolisp-then.md) callbacks chained on the failed promise:

- **Interpreter / JVM**: `await` raises an error describing the failure.
- **WASM**: `await` returns `nil` (the nil-on-failure convention of that
  backend). A `nil` promise (a fetch that could not be started) also awaits to
  `nil`. A chained callback does receive the `nil` (this backend cannot
  distinguish a failure from a `nil` value).

## Backend support

`await` itself is a generic promise operation and works on every backend and in
every WASM mode (Preview 1 included) — only [`rontolisp:fetch`](rontolisp-fetch.md)
is restricted to `--component` mode. In the browser playground `await` blocks
the interpreter's worker until the browser delivers the response.
