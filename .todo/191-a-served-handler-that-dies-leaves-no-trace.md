# 191 - A served handler that dies leaves no trace

`HttpHandlerSupport.dispatch` answers 500 and throws the cause away:

```java
try {
    response = handler.handle(request);
}
catch (RuntimeException ex) {
    writeResponse(exchange, new Response(500, List.of(), "Internal Server Error"));
    return;
}
```

Nothing is logged, anywhere. A handler that dies gives the operator a status
code and no other signal -- not the exception type, not the Lisp function it
died in. The interpreter and the JVM backend both go through this path.

Found while diagnosing `.todo/189`: the failing requests there were 500s with an
empty stderr, and the NullPointerException that named the offending function
(`CL-POSTGRES::INITIATE-CONNECTION`) only appeared after patching a
`printStackTrace` in locally. Without that patch the bug was invisible.

## What to do

Report the cause on stderr. Points to settle:

- **Always, or gated?** A stack trace per bad request is noise for a public
  endpoint and exactly what you want in development. `wasmtime serve` prints
  its own trap traces unconditionally, which is the precedent.
- **How much?** The exception's `toString` plus the Lisp-level frames is what
  identifies the fault; the full JVM trace is mostly runtime internals. The
  compiled frames carry the Lisp names already (`App.CL-POSTGRES$colon$colon...`),
  so a plain trace is usable as-is.
- The WASM backends do not share this code path; a component's trap surfaces
  through the host (wasmtime/wash print it). Only the interpreter/JVM server is
  silent.

Pin whatever lands in `HttpHandlerTest` / `HttpHandlerJvmTest` -- a handler that
signals, asserting the report reaches stderr and the client still gets its 500.
