# 193 - Concurrent connect churn drops an occasional connection during auth

Repeated bursts of concurrent requests against `examples/db/postgres-web.lisp`
(a cl-postgres connection per request) intermittently lose 1-2 of 12 connects
INSIDE the trust-auth handshake -- on the interpreter AND the JVM class alike,
so this is not the (fixed) special-variable race and not backend-specific.

## Measured 2026-07-27 (postgres:17-alpine in Docker, trust auth, port 54329)

Four bursts of 12 concurrent POSTs per host:

- JVM class: `12/12`, `10/12`, `10/12 + one curl 000`, `12/12`
- interpreter: `10/12`, `12/12`, `12/12`, `12/12`

The failures (visible only with a locally patched `HttpHandlerSupport.dispatch`
-- the 500 swallows the trace, `.todo/191`):

```
java.lang.RuntimeException: read-byte: end of file
    at App.CL-POSTGRES$colon$colonREAD-UINT1
    at App.CL-POSTGRES$colon$colonAUTHENTICATE
    at App.CL-POSTGRES$colon$colonINITIATE-CONNECTION
java.lang.RuntimeException: PostgreSQL protocol error: Unexpected message received:   0
    at App.CL-POSTGRES$colon$colonAUTHENTICATE
```

So the server (or the Docker port forward) accepts the TCP connection and then
closes it -- or delivers a zero byte -- while the driver is reading the auth
exchange. The stray curl `000` against OUR OWN http-handler port in one round
smells like the same accept-side churn on the JVM lambda.

## To investigate

- Raw concurrent connects WITHOUT rontolisp are clean (measured same day: six
  rounds of 12 parallel `psql -c 'select 1'` against the same forwarded port,
  72/72) -- so the Docker proxy / postgres fork pressure alone does not explain
  it. Prime suspect shifts to the rontolisp side of the handshake: the shared
  Java socket runtime (`eval/SocketSupport` -- the SAME classes serve the
  interpreter and a JVM class run with the jar on the classpath, matching the
  backend-independence) or the driver's read loop under concurrent virtual
  threads. No obviously-shared mutable buffer jumped out of a first grep.
- A driver-side bounded retry on EOF-before-auth-completes would mask it
  regardless of cause (a connection that dies before the first ReadyForQuery
  carries no session state, so a retry is safe) -- but find the cause first.
- Whether the http-handler's accept backlog (the `000`) needs raising.
- Sequential requests never fail; a browser never sees this; only load tests.

## Aside noticed in the same logs

`WARNING: PostgreSQL warning: ~A~@[~%~A~]` -- the warning path prints the
format CONTROL STRING raw instead of rendering it with its arguments (both
interpreter and JVM). Small, separate.
