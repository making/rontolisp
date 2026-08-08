# A condition signaled and CAUGHT inside a `--component` served request traps

Difficulty: High

Under `wasmtime serve` on a `--component` build, a request whose handler
signals a condition and **catches it** kills the request with

```
wasm trap: wasm `unreachable` instruction executed
error: worker trapped or panicked
```

and the host answers 500. The handler never gets to return its response. The
same program answers correctly on the interpreter, on the JVM and (for the
`--no-wasi` reactor shape) in workerd.

Found 2026-08-08 writing `examples/net/httpbin-tiny-routes.lisp`. It is NOT
that file's bug: `examples/net/httpbin-clack.lisp`, committed long before, has
it too.

## Minimal reproduction

```lisp
;; probe.lisp
(ql:quickload "clack")
(defun app (env)
  (declare (ignore env))
  (list 200 '(:content-type "text/plain")
        (list (format nil "~a~%" (handler-case (parse-integer "x") (error () "caught"))))))
(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
```

```bash
rontolisp probe.lisp -o probe.wasm --component --optimize
wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y \
  --addr 127.0.0.1:8085 probe.wasm
curl -i http://127.0.0.1:8085/          # 500, and the worker trapped
```

Change `"x"` to `"42"` -- so the `handler-case` is entered but nothing is
signaled -- and the same build answers `200 42`. So it is the SIGNAL-and-catch
that traps, not the `handler-case` form and not EH mode being on.

The same `handler-case` outside a served request is fine on the same backend:

```lisp
(print (handler-case (parse-integer "x") (error () 'caught)))   ; => CAUGHT
```

compiled `--component` and run under `wasmtime run -W gc=y -W exceptions=y`
prints `CAUGHT`. What is special is signaling inside the handler the async
(stackful) lift calls -- the serve path, not the command path.

## What it costs today

- `examples/net/httpbin-clack.lisp` and `examples/net/httpbin.lisp` document
  answering `"json": null` for an unparseable body; that answer comes from a
  `handler-case` around `json-parse`, so on the component build those requests
  500 instead.
- `examples/net/httpbin-tiny-routes.lisp` answers 404 for `/status/teapot`
  (a `:code` that is not a number, caught by `handler-case`, declining into
  the catch-all); on the component build that request 500s. Every other route
  of that file -- including the 405 decline path, which signals nothing --
  answers identically on all four backends.

## Why nothing caught it

`examples/examples.yaml` lists the `net/*` servers as **compile-only**
(`jvm-compile`, `wasm-component`): a blocking server never returns, so the
driver only builds them. Nothing in the suite drives a component build over
HTTP, which is exactly the leg where this lives.

Worth fixing together: give the E2E suite a way to run a served component and
curl it (start `wasmtime serve`, probe, stop), so this class of divergence is
covered rather than found by hand.

## Where to look

`.kb/wasm-condition-catching.md` (EH mode, the throw/catch lowering) plus the
async lift the component serve path uses -- `.kb` on the callback/async
cutover and the `task.return` shape. The suspicion is that the exception
handler frame and the stackful-lift frame disagree about who owns the current
task, so unwinding past the catch inside a lifted call reaches an
`unreachable`.
