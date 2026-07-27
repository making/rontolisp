# 189 - Special bindings are not thread-scoped on the compile paths

A `let` of a special variable is **shallow binding over one process-global
location** on the JVM and both WASM backends (a static field / a module global;
`.kb/dynamic-special-variables.md`). The interpreter instead keeps per-name
value stacks in a `ThreadLocal` and says why: the HTTP handler serves **one
virtual thread per request**.

So the concurrency property the interpreter deliberately has, the compiled
backends silently lack. Two overlapping requests that bind the same special
clobber each other's binding, and one of them also restores the *other's* saved
value on the way out.

## Reproduction (measured 2026-07-27)

```lisp
(defvar *v* :none)
(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
(defun peek () *v*)

(rontolisp:async-defun handle (request)
  (let ((*v* (rontolisp:query-param (getf request :query) "v")))
    (let ((f (fib 27)))                 ; real work: the requests must OVERLAP
      (list :status 200 :body (format nil "~a ~a~%" (peek) f)))))

(rontolisp:http-handler 'handle 8080)
```

Eight concurrent `GET /?v=A` .. `?v=H`:

| backend | answers |
| --- | --- |
| interpreter | `A B C D E F G H` -- every request sees its own binding |
| JVM (`-o Race.class`) | `A B C D E F H NONE` -- one request read the GLOBAL default, another's value was lost |

Note the busy-loop trap: a `(dotimes (i 300000) nil)` delay is optimized away
and the requests then do not overlap at all, which makes the bug look absent.
The work has to produce a value that is used.

## How it was found

`examples/db/postgres-web.lisp` with a per-request connection. Sequential
requests: 12/12 succeed on every backend. Twelve concurrent: 11 of 12 answer
500 on the JVM, and the trace is

```
java.lang.NullPointerException: Cannot invoke "Object.equals(Object)" because
  the return value of "App._strv(Object)" is null
    at App.CL-POSTGRES$colon$colonINITIATE-CONNECTION
```

cl-postgres' `initiate-connection` does exactly the thing that breaks:

```lisp
(let ((socket ...) (finished nil)
      (*connection-params* (make-hash-table :test 'equal)))   ; <- special
  (setf (connection-parameters conn) *connection-params*)
  ... (authenticate socket conn) ...                          ; fills it
  (if (string= (gethash "integer_datetimes" (connection-parameters conn)) "on")
```

Concurrent connects land their ParameterStatus rows in whichever table the
global currently points at, so a connection ends up with a table that has no
`integer_datetimes` -> `nil` -> `string=` on `nil`.

Any library with a `(let ((*special* ...)) ...)` on its request path has the
same exposure; cl-postgres is just the one we happened to run concurrently.

## What to do

Make a compiled special binding thread-scoped, the way the interpreter's is.
Sketch of the options, none free:

- **`ThreadLocal` (or Java 25 `ScopedValue`) per special** on the JVM. Matches
  the interpreter exactly. Cost lands on EVERY special read, including the
  cl-ppcre `#.*standard-optimize-settings*` paths that made the current
  static-field design worth having -- measure before committing.
- **Bind only what is dynamically bound**: keep the static field for a special
  that is never `let`-bound (the overwhelming majority) and switch a name to the
  thread-scoped representation only when `SpecialVarCollector` sees a binding
  form for it. Keeps the hot path, pays only where the semantics need it.
- WASM has no threads, and the reproduction above is **clean on both wasm
  hosts** (8/8 correct under `wasmtime serve` and under `wash dev`), so the
  module global may be able to stay as it is. Do not read that as "the wasm
  backends are concurrency-safe" -- see the separate wasmCloud failure below,
  which is a different bug.

Whatever lands, the dual-bind/lexical-capture rule, the dynamic-first read rule
and the `Ctx.specialBindScopes` exit restores all have to keep working -- see
`.kb/dynamic-special-variables.md` for what each of them exists for.

## The full concurrency matrix that turned this up

`examples/db/postgres-web.lisp` (a connection per request), 12 concurrent POSTs,
all four hosts, measured 2026-07-27. Sequential requests are 12/12 everywhere.

| host | result |
| --- | --- |
| interpreter | 12/12 |
| JVM class | **1/12** -- the special-variable race above, isolated |
| `wasmtime serve` component | 12/12 (the log shows interleaved task ids, so they did overlap) |
| wasmCloud `wash dev` component | **10/12** -- two `wasm trap: cast failure`, NOT isolated |

**The wasmCloud failure is a DIFFERENT bug and is tracked separately in
`.todo/190`** -- the reproduction at the top of this file is 8/8 clean under
`wash dev`, so fixing this item will not fix that one. Do not close 190 with
189.

## Split out of this item

- `.todo/190` -- the wasmCloud `cast failure` under concurrency (different
  cause, not isolated).
- `.todo/191` -- `HttpHandlerSupport.dispatch` discards the exception behind its
  500, which is why the NPE above needed a local patch to see at all.
