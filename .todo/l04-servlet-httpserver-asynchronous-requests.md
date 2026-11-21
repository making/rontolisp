# l04. servlet-httpserver: asynchronous requests and non-blocking I/O

Difficulty: High

Follows k96 (`.kb/servlet-httpserver.md`). `isAsyncSupported()` is false and `startAsync`
throws `IllegalStateException`; `setReadListener` / `setWriteListener` throw too.

- `startAsync` (both forms), `AsyncContext` (`dispatch` in its three forms, `complete`,
  `start(Runnable)`, the timeout, `AsyncListener`), `DispatcherType.ASYNC` in the filter
  mappings, `Registration.setAsyncSupported` honoured per servlet and filter of the chain.
- The premise to measure first: `WebContext.handle` answers and closes the exchange when the
  handler returns. A JDK `HttpExchange` may be answered after its handler returned, by any
  thread; rontolisp's `HttpServing` closes it where the handler returns (`.todo/k15`, "An
  exchange answered after its handler returned"). So on the JVM this is the container's work
  alone, and compiled it waits for that item -- or the request thread waits for `complete()`
  inside `handle` (a latch), which works on every transport at the price of a thread a
  request. Decide with the numbers; the second is cheap and may be the first cut.
- `ReadListener` / `WriteListener`: `HttpExchange`'s streams block, so `isReady()` is always
  true and the listeners are called from a thread of the executor. Say so in the note.
- Measure the room against Tomcat and Jetty (what `complete()` after a timeout does, a
  dispatch from another thread, an error while asynchronous).
