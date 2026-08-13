# A binary response body is double-encoded on every transport that writes text

Difficulty: Medium

An `(unsigned-byte 8)` response body is a documented Clack body shape and it
does not survive the trip out. Measured on the interpreter's JDK server
(2026-08-13, jar at `6ba952a5`), a handler answering `#(#xff #xfe #x41)`:

```
$ curl -s http://localhost:18099/ | xxd
00000000: c3bf c3be 41                             ....A
```

Five octets where three were meant. The cause is one flattening followed by one
encode: the body becomes a string of code points U+00FF U+00FE U+0041
(`%http-octets-string`), and the transport then writes
`body.getBytes(UTF_8)`. Every octet >= 0x80 doubles. `.kb/http-server.md` has
carried this as a known bug for a long time; what it did NOT say is that the
comment justifying the flattening -- "the transport writes those characters back
out one byte each, so the bytes survive" -- was never true of any transport.

## What todo-341 Phase 3b already did, and what it left

The shared normalizer STOPPED flattening (`%http-body-string` now hands an
octet body through, exactly as it hands a stream through), and the reactor's
byte-shaped sink -- `env.writeResponseBody`, the WASM boundary -- is byte-exact:
`ff fe 41` crosses as `ff fe 41`, pinned by
`WasmReactorResponseBodyE2eTest`. So the octets now REACH each transport intact,
and each one that writes TEXT flattens at its own write site through
`%http-body-text`:

- `%http-serve-request` (http-server.lisp) -- the interpreter's JDK server and
  the `--component` wasi:http transport;
- `HttpHandlerJvmRuntime.toResponse` -- the JVM backend (`long[]{width, e0, ...}`
  there);
- `LispEvaluator.responseBody`'s cold arm -- the interpreter's
  `%http-server-start` acceptor (clack-handler-rontolisp);
- `%http-reactor-body-out`'s no-sink arm -- the reactor envelope.

That is the whole remaining surface, and it is why this item is Medium rather
than High: the loss is now at four NAMED write sites instead of inside the one
function every backend shares.

## The shape of the fix

The last of those four cannot be fixed and must not be: a JSON string is text,
and taking bytes out of band is exactly what the sink is for. A reactor host that
wants a binary response provides `env.writeResponseBody`. Say so where the
envelope is documented rather than trying.

The other three want the octets carried, not flattened:

- **JDK / interpreter / JVM**: `HttpHandlerSupport.Response` holds `String
  body`, and `writeResponse` does `getBytes(UTF_8)`. Give it `byte[] body` with a
  `Response.of(status, headers, String)` factory that encodes -- the shape
  `HttpHandlerSupport.Request` ALREADY has (`byte[] body` + `bodyString()` +
  `Request.of(..., String)`), so this is making the two records agree rather than
  inventing a convention. Call sites: `LispEvaluator.normalizeClackResponse`,
  `HttpHandlerJvmRuntime.toResponse`, the 500 fallback in `serve`, and whatever
  tests construct one.
- **`--component`**: `%http-write-body` hands the body to
  `%http:body-stream-write`, whose WIT type is `list<u8>` -- but the canonical
  lowering treats `list<u8>` exactly like a string
  (`WasmComponentImportCompiler`, `emitStageStringParam`), so it UTF-8 encodes.
  Either the lowering learns to take a packed `(unsigned-byte 8)` vector for a
  `list<u8>` parameter (the better answer -- it is the same gap that keeps
  `:bytes` off the component path, `.kb/wit.md`), or `%http-write-body` writes
  the octets one chunk at a time through something that does not encode.
- `%http-serve-request` is then just the plumbing: stop calling
  `%http-body-text` and let the octets reach the transport.

## The gate

A four-backend ci-spec case cannot express this on its own (it can only compare
printed values, and the double-encode happens in the transport). What it needs:

- an `HttpHandlerTest` / `HttpHandlerJvmTest` round trip asserting the RAW
  response bytes of an octet body, not its text;
- the component leg in `WasmLispCompilerIntegrationTest`'s serve cases, same
  assertion;
- the reactor sink is already pinned (`WasmReactorResponseBodyE2eTest`), and the
  envelope arm's flattening should get a comment naming this item rather than a
  fix.

Keep the ci-spec `http-response-normalizer` case as it is: it pins that the
normalizer does NOT flatten, which is the precondition for all of the above.
