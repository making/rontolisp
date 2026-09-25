# The component's fetch body stream ends at the first read that finds nothing

Difficulty: High

On `--component` a fetched `:body` stream ends at the first read that finds no octets there
yet: a reply that pauses mid-body arrives cut short, and a body larger than what has arrived
reads as empty.

```
;; origin: chunked, "first-", then after 200 ms "second"
(print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch url)) :body))))
```

prints `"first-"` under `wasmtime run -S http=y` (wasmtime 49.0.0), `"first-second"` on the
interpreter, the JVM and a `--native` output; a 300,000-octet body drained with `stream-read`
reads as 0 octets (checked 2026-09-25, linux x86_64). The opt-in
`WasmLispCompilerIntegrationTest.componentPendingBodyReadOverlapsTimer` (`RONTOLISP_HTTP_E2E=1`)
fails the same way -- and its expected text is stale besides: it expects `(drained ...)` in lower
case, which the reader has not printed since symbols read upcased.

The fetch corpus found two more component divergences:

- a REJECTED fetch future awaited a second time answers NIL (every other leg signals again);
- a fetch that cannot start (a runtime-built unsupported method, a URL with a space) answers NIL
  rather than signalling at the call or failing the future. `.kb/fetch-http.md` records NIL as
  the component's contract, which contradicts "options validated at fetch time"; settle which
  one is the contract.

## Goal

The component answers what the other legs answer: remove the `component:` entries under `skip:`
in `src/test/resources/fetch-spec.yaml` (`FetchSpecE2eTest`), or record a deliberate divergence
in `.kb/fetch-http.md`, "The cross-backend corpus and its known divergences". Repair the opt-in
test too.

## Starting points

- `http.lisp`'s `%http-body-value` read thunk and the `stream<u8>` read it issues; a read the host
  reports BLOCKED must park on the scheduler (`EVENT_STREAM_READ`, `.kb/async-await.md`), not end
  the stream.
- The rejected-future re-await: `WasmFutureRuntimeBuilder`'s settle/reject state and `_poll`.
