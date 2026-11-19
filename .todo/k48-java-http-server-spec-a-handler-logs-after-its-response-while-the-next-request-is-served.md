# k48. java-http-server-spec: a handler logs after its response while the next request is served

Difficulty: Low

Full test 38 failed once on 2026-11-20 at `8a541e412`. The failing test was
`FetchSpecE2eTest.javaHttpServer()[1][1][41]`, and only on the JDK leg, which is the
oracle. The case is in `src/test/resources/java-http-server-spec.yaml` and runs its
server under an executor (`an executor: true`).

What the server logged, against the pinned order:

```
 | code after the head: 201
+| too many octets: java.io.IOException: too many bytes to write to stream
+| the request body after the close: java.io.IOException: Stream is closed
 | a head sent twice: java.io.IOException: headers already sent
 | a write after the close: java.io.IOException: stream closed
-| too many octets: java.io.IOException: too many bytes to write to stream
-| the request body after the close: java.io.IOException: Stream is closed
```

## Why it can fail

The handler that answers 201 (`text(exchange, 201, ...)`) logs three lines after its
response is out: "code after the head", "a head sent twice" and "a write after the close".
The client sees the 201 and sends the next request, the echo. The executor can run
`echo` on another thread before the first handler writes its last two lines.

So the program's own order is not fixed, even on a JDK. This is the same kind of race
as the timed `invokeAll` case that k25 fixed.

## To do

1. Make the order fixed without a sleep, then reproduce the race under load the way k25
   did.
   - One way: the next request is sent only after the handler says it has finished
     logging, through a latch or a `CountDownLatch` the client awaits.
   - Another way: the handler logs into its answer instead of after it.
2. Keep the lines the case pins. If they change, they change on every leg together.
3. Check whether any other handler in the file logs after its response, and give each
   one the same treatment.
