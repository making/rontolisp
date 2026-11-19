# k22. Playground: a Java program that starts threads and sends HTTP requests is refused

Difficulty: High

State after k10 (2026-11-19, `.kb/java-frontend.md`, "In the browser"): in the browser a
whole Java program that starts threads is compiled to a Preview 1 module and run by the
page's own engine (`src/web/java/.../web/BrowserModule`). A Preview 1 module has no HTTP
transport, so such a program that also names `java.net.http` is refused with the
compile's reason. `HttpClient.sendAsync` reaches a pool, so the client example of
`doc/*/java/index.md` (the one asking `httpbin.ik.am`) is one: its cell printed
`200 application/json` and then froze before k10, and says why not now. A program that
starts no thread sends from the browser's interpreter as before.

## Leads

1. Give the module the page's fetch. Two shapes exist: the `--native` runner's
   (`HostFetchLibrary.processForRunner`, the `rlhttp` imports the runner answers;
   `CompileFrontend.Options.nativePlatform`) and `--host-fetch`'s (`--no-wasi`, `env.fetch`,
   `HostGlueEmitter`). The first keeps the module a Preview 1 command with k00's loop
   giving the turns; its imports would be answered in JavaScript by a synchronous
   request in the worker (`BrowserHttp.request` is the one the interpreter uses) or by
   the page's broker (`BrowserHttp.start` / `awaitResponse`, cross-origin isolated only).
   Measure what `runnerHosted()` changes beside fetch (serving, sockets, the local time
   zone, `.kb/native-output.md`) before choosing: `BrowserModule` refuses an import it
   has not by name, so nothing half works.
2. A thread waiting for a reply: on Preview 1 and `--native` it holds every other thread
   until the reply is in (`doc/*/java/deviations.md`, "The HTTP client"; `.todo/k18`).
   State it for the browser or take the page's broker as the event.
3. `web/playground-check.js` has no network. Check with a page of the docs in headless
   Chrome (`.todo/artefacts/k10-playground-a-java-program-with-threads-freezes-the-browser/`).

## Done when

- The client example of `doc/*/java/index.md` prints in its cell what it prints on the CLI,
  and `doc/*/java/deviations.md` ("Threads", the browser paragraph) says what is left.
